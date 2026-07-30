package com.nachogoro.smartchess

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nachogoro.smartchess.ble.BleBoardConnection
import com.nachogoro.smartchess.ble.BoardConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "BoardReconnect"

/**
 * Proves the board survives the read pattern a real game produces.
 *
 * Moves arrive minutes apart, and the link is torn down about 29s after the last operation (see
 * [BoardIdleTimeoutTest]), so every read in a real game starts from a dead link. That makes
 * reconnect-on-demand the load-bearing path rather than an edge case, and it cannot be verified off
 * the device: only real firmware and a real Bluetooth stack reproduce the teardown.
 *
 * Requires the board powered on and in range. Run it with:
 * ```
 * ./gradlew :app:installDebug :app:installDebugAndroidTest
 * adb shell pm grant com.nachogoro.smartchess android.permission.BLUETOOTH_SCAN
 * adb shell pm grant com.nachogoro.smartchess android.permission.BLUETOOTH_CONNECT
 * adb shell am instrument -w -e class com.nachogoro.smartchess.BoardReconnectTest \
 *     com.nachogoro.smartchess.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * `connectedAndroidTest` uninstalls the app afterwards, which revokes the runtime permissions the
 * next run needs, so these BLE tests are driven through `am instrument` instead.
 */
@RunWith(AndroidJUnit4::class)
class BoardReconnectTest {

    @Test
    fun readsSurviveIdleDisconnections() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = BleBoardConnection(context)

        try {
            assertTrue("connect() failed", connection.connect(scanTimeoutMs = 20_000).isSuccess)
            assertEquals(18, connection.readBoardState().size)

            // Well past the measured teardown, so the link is certainly gone.
            val idleMs = 60_000L
            Log.i(TAG, "idling ${idleMs / 1000}s")
            delay(idleMs)

            Log.i(TAG, "state after idling: ${connection.state.value}")
            assertTrue(
                "an idle drop should not look like a failure, was ${connection.state.value}",
                connection.state.value is BoardConnectionState.Disconnected,
            )
            // Capabilities are metadata and must outlive the link, otherwise starting a game after
            // a pause reports the board as unreadable.
            assertNotNull("capabilities lost with the link", connection.capabilities)
            assertTrue("board should still be known", connection.isBoardKnown)

            // The read itself must bring the link back, with no user-visible reconnect step.
            assertEquals(18, connection.readBoardState().size)
            Log.i(TAG, "read after idle succeeded; state=${connection.state.value}")

            // A second gap proves reconnection is repeatable, not a one-shot recovery.
            delay(idleMs)
            assertEquals(18, connection.readBoardState().size)
            Log.i(TAG, "second read after idle succeeded")
        } finally {
            connection.close()
        }
    }
}
