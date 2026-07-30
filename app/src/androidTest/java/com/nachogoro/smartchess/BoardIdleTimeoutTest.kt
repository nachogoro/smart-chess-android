package com.nachogoro.smartchess

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nachogoro.smartchess.ble.BleBoardConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "BoardIdle"

/**
 * Measures how long the board keeps an idle connection open.
 *
 * A game generates board reads minutes apart, so whether the link survives that gap decides
 * whether the app needs reconnect-on-demand or merely error reporting. This connects, then sits
 * silent and samples the connection state until it drops or the probe window expires, logging the
 * elapsed time and the GATT status of the drop.
 *
 * Requires the board powered on and in range. Diagnostic, not a regression check.
 */
@RunWith(AndroidJUnit4::class)
class BoardIdleTimeoutTest {

    // Block body, not `= runBlocking {...}`: an expression body takes the type of the last
    // statement, and JUnit rejects a test method that is not void.
    @Test
    fun measureIdleTimeout() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = BleBoardConnection(context)

        try {
            val connectResult = connection.connect(scanTimeoutMs = 20_000)
            Log.i(TAG, "connect() success=${connectResult.isSuccess} state=${connection.state.value}")
            assertTrue("connect() failed: ${connection.state.value}", connectResult.isSuccess)
            val connectedAt = System.currentTimeMillis()
            Log.i(TAG, "connected, going idle")

            val probeWindowMs = 8 * 60_000L
            val sampleMs = 15_000L
            var elapsed = 0L

            while (elapsed < probeWindowMs && connection.isReady) {
                delay(sampleMs)
                elapsed = System.currentTimeMillis() - connectedAt
                Log.i(TAG, "idle ${elapsed / 1000}s state=${connection.state.value}")
            }

            val survived = connection.isReady
            Log.i(TAG, "DROPPED=${!survived} after ${elapsed / 1000}s")

            // Whatever the state claims, find out what a real read does after the idle gap.
            val readOutcome = runCatching { connection.readBoardState().size }
            Log.i(TAG, "read after idle: $readOutcome")
        } finally {
            connection.close()
        }
    }
}
