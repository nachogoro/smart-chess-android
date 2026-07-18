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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val TAG = "BleBoard"

/** Lifecycle of the link to the physical board, as far as the UI is concerned. */
sealed interface BoardConnectionState {
    data object Idle : BoardConnectionState
    data object Scanning : BoardConnectionState
    data class Connecting(val deviceName: String?) : BoardConnectionState
    data class Ready(
        val deviceName: String?,
        val capabilities: BoardCapabilitiesPacket,
    ) : BoardConnectionState

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

    val isReady: Boolean get() = _state.value is BoardConnectionState.Ready

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

        return try {
            _state.value = BoardConnectionState.Scanning
            val device = withTimeout(scanTimeoutMs) { scanForBoard() }

            _state.value = BoardConnectionState.Connecting(device.safeName())
            connectGatt(device)

            val capsBytes = readCharacteristic(BoardBle.CAPABILITIES_UUID)
            when (val caps = parseCapabilities(capsBytes)) {
                is PacketResult.Invalid -> {
                    close()
                    fail("Incompatible board: ${caps.reason}")
                }

                is PacketResult.Ok -> {
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
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
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

    private suspend fun connectGatt(device: BluetoothDevice) {
        val deferred = CompletableDeferred<Unit>()
        pendingConnection = deferred

        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        withTimeout(20_000) { deferred.await() }
    }

    /**
     * Performs a characteristic read, serialised against any other GATT operation.
     *
     * Reads before service discovery completes fail silently on Android, hence the explicit
     * service lookup and the [BoardConnectionState] gating in [connect].
     */
    private suspend fun readCharacteristic(uuid: UUID): ByteArray = opMutex.withLock {
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

        try {
            withTimeout(10_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            throw BleOperationException("Timed out reading from the board")
        } finally {
            pendingRead = null
        }
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
                        _state.value = BoardConnectionState.Failed("Board disconnected")
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
}
