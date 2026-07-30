package com.nachogoro.smartchess

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nachogoro.smartchess.ble.BleBoardConnection
import com.nachogoro.smartchess.ble.BoardBle
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "BoardAdv"

/**
 * Measures how soon the board can be reached again after the link drops.
 *
 * A reconnect needs the board in connectable advertising - BLE has no way to reach a peripheral
 * that is not advertising - so this delay is what bounds how long a move takes to register when the
 * player happens to tap shortly after the idle teardown. It also settles whether the board
 * advertises while a client is connected.
 *
 * The method is to scan throughout: connect, keep scanning across the teardown, and timestamp every
 * advertisement. Requires the board powered on and in range; diagnostic, not a regression check.
 */
@RunWith(AndroidJUnit4::class)
class BoardAdvertisingTest {

    @Test
    fun measureReadvertisingDelay() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = BleBoardConnection(context)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter.bluetoothLeScanner

        val sightings = CopyOnWriteArrayList<Long>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                sightings += System.currentTimeMillis()
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
            }
        }

        try {
            assertTrue("connect() failed", connection.connect(scanTimeoutMs = 20_000).isSuccess)
            val connectedAt = System.currentTimeMillis()

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BoardBle.SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, callback)
            Log.i(TAG, "connected, scanning while connected and across the drop")

            // Wait out the idle teardown, then keep scanning to catch the first advertisement.
            var droppedAt: Long? = null
            val deadline = connectedAt + 120_000
            while (System.currentTimeMillis() < deadline) {
                delay(250)
                if (droppedAt == null && !connection.isReady) {
                    droppedAt = System.currentTimeMillis()
                    Log.i(TAG, "dropped after ${droppedAt - connectedAt} ms")
                }
                // Once dropped, stop as soon as the board is visible again.
                if (droppedAt != null && sightings.any { it > droppedAt!! }) break
            }

            val drop = droppedAt
            val whileConnected = sightings.count { drop == null || it <= drop }
            Log.i(TAG, "advertisements seen while connected: $whileConnected")

            if (drop == null) {
                Log.i(TAG, "link never dropped inside the window")
            } else {
                val firstAfter = sightings.firstOrNull { it > drop }
                if (firstAfter == null) {
                    Log.i(TAG, "NOT SEEN again within ${System.currentTimeMillis() - drop} ms")
                } else {
                    Log.i(TAG, "re-advertised ${firstAfter - drop} ms after the drop")
                }
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
            connection.close()
        }
    }
}
