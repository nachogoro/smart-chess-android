package com.nachogoro.smartchess

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nachogoro.smartchess.ble.BleBoardConnection
import com.nachogoro.smartchess.ble.BoardConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "BoardDump"

/**
 * Records what the physical board actually puts on the wire.
 *
 * The integration guide (`GUIA_APP_MOVIL_BLE_CHESSBOARD_V1-0-0_ES.md`) is unverified against real
 * firmware, so it is a description of the wire format rather than evidence of it. Every other
 * assumption in this app can be checked by reading source; this one can only be checked against the
 * device. This test connects, reads each characteristic and lets the transport trace in
 * [BleBoardConnection] log the raw bytes, so the guide can be corrected from observation.
 *
 * Requires the board to be powered on and in range, and the app to hold the BLE runtime
 * permissions. It is a diagnostic, not a regression check: it fails when the board is absent, which
 * is why it is not part of any automated suite.
 */
@RunWith(AndroidJUnit4::class)
class BoardWireFormatTest {

    @Test
    fun dumpWireFormat() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = BleBoardConnection(context)

        try {
            assertTrue("Bluetooth is not enabled on this device", connection.isBluetoothEnabled())

            val result = connection.connect(scanTimeoutMs = 20_000)
            Log.i(TAG, "connect() success=${result.isSuccess} state=${connection.state.value}")

            // Read the rest even when Capabilities was rejected: the point is to capture bytes.
            if (connection.isReady) {
                val state = connection.state.value as BoardConnectionState.Ready
                Log.i(TAG, "device=${state.deviceName} caps=${state.capabilities}")
                Log.i(TAG, "BoardState size=${connection.readBoardState().size}")
                Log.i(TAG, "Status=${connection.readStatus()}")
            }

            assertTrue("connect() failed: ${connection.state.value}", result.isSuccess)
        } finally {
            connection.close()
        }
    }
}
