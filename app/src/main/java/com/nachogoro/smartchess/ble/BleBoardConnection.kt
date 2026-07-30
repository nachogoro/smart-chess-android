package com.nachogoro.smartchess.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val TAG = "BleBoard"

/**
 * The first connect follows a scan, so the board was just seen advertising and the only thing left
 * that can be slow is service discovery. A generous budget costs nothing here.
 */
private const val INITIAL_CONNECT_TIMEOUT_MS = 20_000L

/**
 * Budget for one attempt at bringing a dropped link back up, and how many attempts to make.
 *
 * Reconnecting to a board that is there takes about a second, so these attempts are for the cases
 * where it is not: they bound what a board that is switched off or out of range costs the caller,
 * and they re-issue the connection rather than sitting on an attempt that has wedged.
 *
 * The board is otherwise always reachable: it advertises continuously, even while a client is
 * connected, and is visible again within about 30ms of a drop. A failure here means the board is
 * genuinely gone rather than briefly unavailable.
 */
private const val RECONNECT_ATTEMPT_TIMEOUT_MS = 4_000L
private const val RECONNECT_ATTEMPTS = 3
private const val RECONNECT_RETRY_DELAY_MS = 500L

/** Lifecycle of the link to the physical board, as far as the UI is concerned. */
sealed interface BoardConnectionState {
    data object Idle : BoardConnectionState
    data object Scanning : BoardConnectionState
    data class Connecting(val deviceName: String?) : BoardConnectionState
    /**
     * A board that has been found and validated.
     *
     * Whether the link is up right now is a detail of BLE power management, not something a caller
     * deciding "can we play?" should have to reason about, so both live and idle links share this
     * type.
     */
    sealed interface Identified : BoardConnectionState {
        val deviceName: String?
        val capabilities: BoardCapabilitiesPacket
    }

    data class Ready(
        override val deviceName: String?,
        override val capabilities: BoardCapabilitiesPacket,
    ) : Identified

    /**
     * The board is identified but the link is down, which is the routine state between moves.
     *
     * A game reads the board minutes apart and the link is torn down within about 30s of the last
     * operation, so this is not an error: the next read reconnects to the remembered board. Kept
     * distinct from [Failed] so the UI does not present ordinary idling as something to fix.
     */
    data class Disconnected(
        override val deviceName: String?,
        override val capabilities: BoardCapabilitiesPacket,
    ) : Identified

    /** Terminal-ish: the user is expected to retry. [recoverable] drives whether we offer that. */
    data class Failed(val reason: String, val recoverable: Boolean = true) : BoardConnectionState
}

/** Raised when a GATT operation fails; surfaced to the SCP layer as a BoardError. */
class BleOperationException(message: String) : Exception(message)

/**
 * Owns the BLE link to the board: scanning, connecting, service discovery and characteristic
 * reads.
 *
 * Threading: Android delivers every GATT callback on a binder thread and permits only one
 * outstanding GATT operation at a time. Reads are therefore serialised behind [opMutex], with the
 * in-flight operation represented by a [CompletableDeferred] that the callback completes.
 *
 * This class is deliberately transport-only: it knows about bytes, not about chess. Packet
 * interpretation lives in [BoardPackets.kt] and the SCP mapping in [BleBoardModule].
 */
@SuppressLint("MissingPermission") // Callers gate on BlePermissions before invoking.
class BleBoardConnection(context: Context) {

    private val appContext = context.applicationContext

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow<BoardConnectionState>(BoardConnectionState.Idle)
    val state: StateFlow<BoardConnectionState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null

    private val opMutex = Mutex()

    @Volatile
    private var pendingRead: CompletableDeferred<ByteArray>? = null

    @Volatile
    private var pendingConnection: CompletableDeferred<Unit>? = null

    @Volatile
    private var scanCallback: ScanCallback? = null

    /**
     * The board once it has been identified, retained across disconnections.
     *
     * Remembering the device is what lets a reconnect skip the scan, and remembering the
     * capabilities is what lets callers ask about the board while the link happens to be down -
     * they are static metadata, not a live reading.
     */
    @Volatile
    private var knownDevice: BluetoothDevice? = null

    @Volatile
    private var knownCapabilities: BoardCapabilitiesPacket? = null

    /**
     * The board's name as read while it was reachable.
     *
     * [BluetoothDevice.getName] returns null once there is nothing cached for a device that is no
     * longer connected, so re-reading it on disconnection would swap the board's name for a generic
     * label every time the link idles out.
     */
    @Volatile
    private var knownDeviceName: String? = null

    /** True only while the link is actually up. */
    val isReady: Boolean get() = _state.value is BoardConnectionState.Ready

    /** True once a board has been identified, whether or not the link is currently up. */
    val isBoardKnown: Boolean get() = knownDevice != null && knownCapabilities != null

    /** Capabilities of the identified board, available even while disconnected. */
    val capabilities: BoardCapabilitiesPacket? get() = knownCapabilities

    /** True when Bluetooth exists on the device and is currently switched on. */
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun hasBluetooth(): Boolean = adapter != null

    /**
     * Scans for the board, connects, discovers services and reads Capabilities.
     *
     * Suspends until the board is usable or the attempt fails. Safe to call again after a
     * failure; a successful connection short-circuits.
     */
    suspend fun connect(scanTimeoutMs: Long = 15_000): Result<Unit> {
        if (isReady) return Result.success(Unit)

        val adapter = adapter
            ?: return fail("This device has no Bluetooth adapter", recoverable = false)
        if (!adapter.isEnabled) {
            return fail("Bluetooth is switched off")
        }

        // A board already identified can be re-reached directly; scanning again would cost seconds
        // and, on a failed scan, the full timeout.
        if (isBoardKnown && reconnectToKnownBoard()) {
            return Result.success(Unit)
        }

        return try {
            _state.value = BoardConnectionState.Scanning
            val device = withTimeout(scanTimeoutMs) { scanForBoard() }

            _state.value = BoardConnectionState.Connecting(device.safeName())
            connectGatt(device, INITIAL_CONNECT_TIMEOUT_MS)

            val capsBytes = readCharacteristic(BoardBle.CAPABILITIES_UUID)
            when (val caps = parseCapabilities(capsBytes)) {
                is PacketResult.Invalid -> {
                    // The byte count alone rarely identifies the firmware; the hex does.
                    Log.w(
                        TAG,
                        "Rejected Capabilities (${capsBytes.size} bytes): ${capsBytes.toHex()}"
                    )
                    close()
                    fail("Incompatible board: ${caps.reason}")
                }

                is PacketResult.Ok -> {
                    knownDevice = device
                    knownDeviceName = device.safeName()
                    knownCapabilities = caps.value
                    _state.value = BoardConnectionState.Ready(device.safeName(), caps.value)
                    Result.success(Unit)
                }
            }
        } catch (_: TimeoutCancellationException) {
            stopScan()
            close()
            fail("No board found. Make sure it is powered on and nearby.")
        } catch (e: Exception) {
            stopScan()
            close()
            fail(e.message ?: "Could not connect to the board")
        }
    }

    /** Reads and returns the raw 18-byte BoardState packet. */
    suspend fun readBoardState(): ByteArray = readCharacteristic(BoardBle.BOARD_STATE_UUID)

    /** Reads the optional Status characteristic, or null if the board does not expose it. */
    suspend fun readStatus(): BoardStatusPacket? = try {
        when (val parsed = parseStatus(readCharacteristic(BoardBle.STATUS_UUID))) {
            is PacketResult.Ok -> parsed.value
            is PacketResult.Invalid -> null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Drops the connection and releases the GATT client.
     *
     * The board firmware accepts a single central at a time, so leaving a connection open makes
     * the board invisible to every other phone until it times out. Always call this when the app
     * stops.
     */
    fun close() {
        stopScan()
        pendingRead?.cancel()
        pendingRead = null
        pendingConnection?.cancel()
        pendingConnection = null
        discardGatt()
        // A deliberate teardown also forgets the board: the next connect should discover afresh
        // rather than reconnect to whatever was in range last time the app ran.
        knownDevice = null
        knownCapabilities = null
        knownDeviceName = null
        if (_state.value !is BoardConnectionState.Failed) {
            _state.value = BoardConnectionState.Idle
        }
    }

    // ---- internals -------------------------------------------------------------------------

    private fun fail(reason: String, recoverable: Boolean = true): Result<Unit> {
        _state.value = BoardConnectionState.Failed(reason, recoverable)
        return Result.failure(BleOperationException(reason))
    }

    /**
     * Scans until a device advertising the board's service UUID appears.
     *
     * Filtering by service UUID (rather than by advertised name) is done at the OS level: it is
     * cheaper on battery, ignores every unrelated BLE device nearby, and keeps working if the
     * board is renamed in a future firmware revision.
     */
    private suspend fun scanForBoard(): BluetoothDevice {
        val scanner = adapter?.bluetoothLeScanner
            ?: throw BleOperationException("Bluetooth scanner unavailable")

        val found = CompletableDeferred<BluetoothDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result.device)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.firstOrNull()?.let { found.complete(it.device) }
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(
                    BleOperationException("Bluetooth scan failed (error $errorCode)")
                )
            }
        }
        scanCallback = callback

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BoardBle.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, callback)
        return try {
            found.await()
        } finally {
            // Scanning is expensive; stop as soon as we have a candidate.
            stopScan()
        }
    }

    private fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private suspend fun connectGatt(device: BluetoothDevice, timeoutMs: Long) {
        val deferred = CompletableDeferred<Unit>()
        pendingConnection = deferred

        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            // Reported as a board failure rather than left as a CancellationException: callers
            // retry on BleOperationException, and swallowing a cancellation to do so would break
            // the caller's own cancellation.
            throw BleOperationException("Timed out connecting to the board")
        }
    }

    /**
     * Performs a characteristic read, serialised against any other GATT operation, reconnecting
     * first if the link has gone away.
     *
     * Reads before service discovery completes fail silently on Android, hence the explicit service
     * lookup in [readLocked] rather than trusting the link to be usable.
     */
    private suspend fun readCharacteristic(uuid: UUID): ByteArray = opMutex.withLock {
        ensureConnected()
        try {
            readLocked(uuid)
        } catch (first: BleOperationException) {
            // A link that died while idle only reveals itself when the next read is attempted, so
            // one reconnect-and-retry turns the expected case into a slower read rather than a
            // failure the caller has to interpret.
            if (!isBoardKnown) throw first
            Log.i(TAG, "Read of $uuid failed (${first.message}); reconnecting once")
            discardGatt()
            ensureConnected()
            readLocked(uuid)
        }
    }

    /**
     * Brings the link up if it is down, reusing the remembered board.
     *
     * Callers must hold [opMutex]: reconnecting is a GATT operation like any other, and two reads
     * arriving together must not each start their own connection.
     */
    private suspend fun ensureConnected() {
        if (gatt != null) return

        val device = knownDevice ?: throw BleOperationException("Board is not connected")
        val caps = knownCapabilities ?: throw BleOperationException("Board is not connected")

        Log.i(TAG, "Reconnecting to remembered board ${device.address}")
        // Deliberately not published as Connecting: a reconnect happens on the way to serving a
        // read, and announcing it would make the UI flicker between "no board" and "connected"
        // around every move. The manual path shows its own progress from the view model.
        var lastFailure: BleOperationException? = null
        repeat(RECONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RECONNECT_RETRY_DELAY_MS)
            try {
                connectGatt(device, RECONNECT_ATTEMPT_TIMEOUT_MS)
                // Capabilities are static, so the reconnect does not re-read them.
                _state.value = BoardConnectionState.Ready(knownDeviceName, caps)
                return
            } catch (e: BleOperationException) {
                Log.i(TAG, "Reconnect attempt ${attempt + 1}/$RECONNECT_ATTEMPTS: ${e.message}")
                // connectGatt assigns the GATT client before awaiting discovery, so a failed
                // attempt would otherwise leave a half-open one behind to be trusted later.
                discardGatt()
                lastFailure = e
            }
        }
        throw lastFailure ?: BleOperationException("Could not reach the board")
    }

    /** Reconnect attempt for [connect]; returns whether the link came back up. */
    private suspend fun reconnectToKnownBoard(): Boolean = opMutex.withLock {
        discardGatt()
        runCatching { ensureConnected() }
            .onFailure { Log.i(TAG, "Reconnect failed (${it.message}); falling back to a scan") }
            .isSuccess
    }

    /** Releases the GATT client without touching [_state] or the remembered board. */
    private fun discardGatt() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private suspend fun readLocked(uuid: UUID): ByteArray {
        val gatt = gatt ?: throw BleOperationException("Board is not connected")
        val service = gatt.getService(BoardBle.SERVICE_UUID)
            ?: throw BleOperationException("Board service not found")
        val characteristic = service.getCharacteristic(uuid)
            ?: throw BleOperationException("Characteristic $uuid not found")

        val deferred = CompletableDeferred<ByteArray>()
        pendingRead = deferred

        if (!gatt.readCharacteristic(characteristic)) {
            pendingRead = null
            throw BleOperationException("Could not start read of $uuid")
        }

        val value = try {
            withTimeout(10_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            throw BleOperationException("Timed out reading from the board")
        } finally {
            pendingRead = null
        }

        // The board's wire format is the one part of this integration that cannot be checked
        // against source, only against the device, so every read stays traceable.
        Log.d(TAG, "Read $uuid: ${value.size} bytes [${value.toHex()}]")
        return value
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Nothing is readable until discovery completes.
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasReady = _state.value is BoardConnectionState.Ready
                    // status distinguishes a board-initiated idle drop from a link-layer failure.
                    Log.i(TAG, "Disconnected (status=$status, wasReady=$wasReady)")
                    pendingRead?.completeExceptionally(
                        BleOperationException("Board disconnected")
                    )
                    pendingRead = null
                    pendingConnection?.completeExceptionally(
                        BleOperationException("Board disconnected during connection (status $status)")
                    )
                    pendingConnection = null

                    runCatching { gatt.close() }
                    this@BleBoardConnection.gatt = null

                    if (wasReady) {
                        val device = knownDevice
                        val caps = knownCapabilities
                        // Android hangs up 30s after the link opens whatever the traffic: the HCI
                        // trace shows an explicit host Disconnect, not a supervision timeout, and
                        // reads every 8s do not postpone it. A game reads the board minutes apart,
                        // so a dropped link is the resting state rather than a fault: the next
                        // read reconnects.
                        _state.value = if (device != null && caps != null) {
                            BoardConnectionState.Disconnected(knownDeviceName, caps)
                        } else {
                            BoardConnectionState.Failed("Board disconnected")
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt.getService(BoardBle.SERVICE_UUID) != null) {
                pendingConnection?.complete(Unit)
            } else {
                pendingConnection?.completeExceptionally(
                    BleOperationException("Board service not found on this device")
                )
            }
            pendingConnection = null
        }

        // API 33+ delivers the value directly.
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(value, status)
        }

        // Pre-33 the value must be read off the characteristic itself.
        @Deprecated("Required for API < 33", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                completeRead(characteristic.value ?: ByteArray(0), status)
            }
        }

        private fun completeRead(value: ByteArray, status: Int) {
            val deferred = pendingRead ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                deferred.complete(value.copyOf())
            } else {
                Log.w(TAG, "Characteristic read failed with status $status")
                deferred.completeExceptionally(
                    BleOperationException("Board read failed (status $status)")
                )
            }
        }
    }

    private fun BluetoothDevice.safeName(): String? = runCatching { name }.getOrNull()

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
