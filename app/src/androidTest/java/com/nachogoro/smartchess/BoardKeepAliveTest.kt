package com.nachogoro.smartchess

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nachogoro.smartchess.ble.BleBoardConnection
import com.nachogoro.smartchess.ble.BoardConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "BoardKeepAlive"

/**
 * Decides whether traffic can hold the link open, or whether it dies a fixed time after it opens.
 *
 * Every earlier measurement put its read at the start of the connection, so "about 30s after the
 * last operation" and "about 30s after connecting" fit the data equally well, and they imply
 * opposite things about whether a keep-alive is worth having. This reads at a shorter cadence than
 * the observed lifetime: if the link still dies on schedule, traffic does not extend it.
 *
 * Status is read rather than BoardState because the simulated board advances its scripted position
 * on every BoardState read, which would destroy the fixture.
 *
 * Requires the board powered on and in range; diagnostic, not a regression check.
 */
@RunWith(AndroidJUnit4::class)
class BoardKeepAliveTest {

    @Test
    fun doesTrafficHoldTheLinkOpen() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = BleBoardConnection(context)

        try {
            assertTrue("connect() failed", connection.connect(scanTimeoutMs = 20_000).isSuccess)
            val connectedAt = System.currentTimeMillis()
            Log.i(TAG, "connected; polling Status every 8s")

            // Counted from the state flow, not sampled after a read: a read reconnects
            // transparently, so by the time it returns the link looks healthy again and polling
            // isReady would report no drops however many there were.
            val drops = AtomicInteger()
            val watcher = launch {
                connection.state.collect {
                    if (it is BoardConnectionState.Disconnected) {
                        drops.incrementAndGet()
                        Log.i(TAG, "drop #${drops.get()} at t=${(System.currentTimeMillis() - connectedAt) / 1000}s")
                    }
                }
            }

            while (System.currentTimeMillis() - connectedAt < 75_000) {
                delay(8_000)
                val elapsed = System.currentTimeMillis() - connectedAt
                val readOk = connection.readStatus() != null
                Log.i(TAG, "t=${elapsed / 1000}s statusRead=$readOk drops=${drops.get()}")
            }
            watcher.cancel()

            if (drops.get() == 0) {
                Log.i(TAG, "VERDICT: traffic holds the link open - no drop in 75s")
            } else {
                Log.i(TAG, "VERDICT: ${drops.get()} drops despite reads every 8s - a keep-alive cannot help")
            }
        } finally {
            connection.close()
        }
    }
}
