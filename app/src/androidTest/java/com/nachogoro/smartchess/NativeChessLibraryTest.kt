package com.nachogoro.smartchess

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import scp.game.GameController
import scp.protocol.CreateGameParams
import scp.protocol.Result
import scp.protocol.SubmitMoveParams

/**
 * Verifies that the native chess library actually loads and runs on a device.
 *
 * This is the single riskiest part of the port. `simplechess` reaches its C library through JNA,
 * and its stock loading strategy (extract the `.so` from JAR resources into a temp directory and
 * point `jna.library.path` at it) cannot work on Android, because the linker refuses to `dlopen`
 * from an app-writable location. The app instead ships the binaries in `jniLibs/` and relies on
 * the loader falling through to the system path.
 *
 * A compile-time check cannot catch a mistake there; only executing a real move can. If this test
 * passes, the rules engine works on device.
 */
@RunWith(AndroidJUnit4::class)
class NativeChessLibraryTest {

    @Before
    fun setUp() {
        // Mirrors SmartChessApplication, which does not run for instrumentation-only contexts.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        System.setProperty("jna.library.path", context.applicationInfo.nativeLibraryDir)
    }

    @Test
    fun playsMovesThroughTheNativeRulesEngine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = GameController(scope)

        try {
            val created = controller.create(
                CreateGameParams(whiteName = "White", blackName = "Black")
            )
            assertTrue("Game creation failed: $created", created is Result.Success)
            val gameId = (created as Result.Success).value.gameId

            // A legal opening move must be accepted and reported in SAN.
            val e4 = controller.submitMove(SubmitMoveParams(gameId, "e2e4"))
            assertTrue("e2e4 was rejected: $e4", e4 is Result.Success)
            assertEquals("e4", (e4 as Result.Success).value.san)

            // ...and an illegal one must be rejected, proving real validation is happening rather
            // than a stub quietly accepting everything.
            val illegal = controller.submitMove(SubmitMoveParams(gameId, "e2e5"))
            assertTrue("Illegal move was accepted", illegal is Result.Failure)

            val state = controller.getState(gameId)
            assertTrue(state is Result.Success)
            val fen = (state as Result.Success).value.fen
            assertTrue("Unexpected FEN after 1.e4: $fen", fen.startsWith("rnbqkbnr/pppppppp/8/8/4P3"))
        } finally {
            scope.cancel()
        }
    }
}
