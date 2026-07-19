package com.nachogoro.smartchess

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import scp.engine.carballo.CarballoAdapter
import scp.protocol.GetBestMoveParams
import scp.protocol.Result

/**
 * Verifies that the Carballo engine runs on a device.
 *
 * Carballo is a plain JVM library pulled from JitPack, so it compiles and dexes without complaint;
 * the risk is entirely at runtime. It loads its opening book from a classpath resource, which is a
 * common way for a desktop-oriented library to fail on Android, and its search is CPU-bound in a
 * way that a desktop machine hides. Neither shows up until the engine is actually asked to move.
 */
@RunWith(AndroidJUnit4::class)
class CarballoEngineTest {

    @Test
    fun reportsItsCapabilities() = runBlocking {
        val caps = CarballoAdapter().getCapabilities()
        assertTrue("getCapabilities failed: $caps", caps is Result.Success)

        val value = (caps as Result.Success).value
        assertTrue("Engine reported no name", value.name.isNotBlank())
        // The setup form is generated from these, so an empty list would silently mean "no options".
        assertTrue("Engine declared no parameters", value.parameters.isNotEmpty())
    }

    @Test
    fun playsAMoveFromTheStartingPosition() = runBlocking {
        val engine = CarballoAdapter()

        // Explicitly exercise the opening book: it is loaded from a classpath resource, which is
        // the part most likely to behave differently on Android than on a desktop JVM.
        val configured = engine.configure(mapOf("UseBook" to true, "Hash" to 16))
        assertTrue("configure failed: $configured", configured is Result.Success)
        assertTrue(engine.newGame() is Result.Success)

        // The orchestrator asks for a move with no time or depth limit, so this is the same
        // fixed-depth search a real game performs - and therefore a fair measure of how long a
        // phone takes to answer.
        val result = withTimeout(ENGINE_MOVE_TIMEOUT_MS) {
            engine.getBestMove(GetBestMoveParams(fen = STARTING_FEN))
        }

        assertTrue("getBestMove failed: $result", result is Result.Success)
        val move = (result as Result.Success).value.move
        assertEquals("Move is not UCI long algebraic: $move", 4, move.length)
        assertTrue("Implausible opening move: $move", move in LEGAL_OPENING_MOVES)
    }

    @Test
    fun rejectsUnknownParameters() = runBlocking {
        // The setup form must only submit parameters the adapter declared, including filtering out
        // ones hidden by visible_when. If that ever regresses, configure starts failing here.
        val result = CarballoAdapter().configure(mapOf("NotARealOption" to 1))
        assertTrue("Unknown parameter was accepted", result is Result.Failure)
    }

    private companion object {
        const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        const val ENGINE_MOVE_TIMEOUT_MS = 60_000L

        /** Every legal first move for white, in UCI. */
        val LEGAL_OPENING_MOVES = setOf(
            "a2a3", "a2a4", "b2b3", "b2b4", "c2c3", "c2c4", "d2d3", "d2d4",
            "e2e3", "e2e4", "f2f3", "f2f4", "g2g3", "g2g4", "h2h3", "h2h4",
            "b1a3", "b1c3", "g1f3", "g1h3",
        )
    }
}
