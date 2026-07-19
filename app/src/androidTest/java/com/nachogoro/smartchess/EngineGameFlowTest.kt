package com.nachogoro.smartchess

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nachogoro.smartchess.ui.AndroidUIModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import scp.engine.carballo.CarballoAdapter
import scp.game.GameController
import scp.orchestrator.Orchestrator
import scp.protocol.BoardCapabilities
import scp.protocol.BoardCapabilityLevel
import scp.protocol.BoardError
import scp.protocol.BoardErrorEvent
import scp.protocol.BoardModule
import scp.protocol.BestMoveResult
import scp.protocol.BoardState
import scp.protocol.Color
import scp.protocol.EngineAdapterModule
import scp.protocol.EngineCapabilities
import scp.protocol.EngineError
import scp.protocol.EngineErrorEvent
import scp.protocol.EngineGameConfig
import scp.protocol.GetBestMoveParams
import scp.protocol.GameMode
import scp.protocol.Piece
import scp.protocol.PieceType
import scp.protocol.Result
import scp.protocol.SquareState

/**
 * Plays a real game against Carballo through the actual orchestrator, on a device.
 *
 * The unit-level engine test proves Carballo can produce a move; this proves the whole engine mode
 * hangs together - mode selection, the configuration handshake, game creation, move inference from
 * a board reading, and the engine's reply coming back to the UI module.
 *
 * The physical board is the one part that cannot participate: it speaks BLE, which no emulator
 * provides. It is replaced here by a fake reporting whatever position the test sets. Everything
 * above it - orchestrator, game controller, native rules engine, engine adapter, and the app's own
 * UI module - is the production code path.
 */
@RunWith(AndroidJUnit4::class)
class EngineGameFlowTest {

    @Before
    fun setUp() {
        // Mirrors SmartChessApplication, which does not run for instrumentation-only contexts.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        System.setProperty("jna.library.path", context.applicationInfo.nativeLibraryDir)
    }

    @Test
    fun playsAGameAgainstTheEngine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ui = AndroidUIModule()
        val board = FakeBoard(STARTING_FEN)
        val orchestrator = Orchestrator(
            board = board,
            gameController = GameController(scope),
            ui = ui,
            engines = listOf(CarballoAdapter()),
        )

        try {
            scope.launch { orchestrator.start() }

            ui.requestNewGame()

            // The orchestrator asks the questions; answer them as the setup screen would.
            awaitUntil("game mode prompt") { ui.pendingGameModeSelection != null }
            ui.pendingGameModeSelection!!.complete(GameMode.ENGINE)

            awaitUntil("engine configuration prompt") { ui.pendingEngineGameConfig != null }
            val prompt = ui.pendingEngineGameConfig!!
            // An empty settings map is valid and means "engine defaults"; sending a key the
            // adapter did not declare would be rejected.
            assertTrue("Engine declared no parameters", prompt.input.parameters.isNotEmpty())
            prompt.complete(
                EngineGameConfig(
                    playerColor = Color.WHITE,
                    engineSettings = emptyMap(),
                    timeControl = null,
                )
            )

            awaitUntil("game to start") { ui.lastGameStartedInfo != null }
            val gameId = ui.lastGameStartedInfo!!.gameId

            // Play 1.e4 by moving the pieces on the board and then "pressing the clock". The
            // orchestrator infers the move by diffing this reading against the game position.
            board.setPosition(AFTER_E4_FEN)
            ui.signalMoveReady(gameId)

            awaitUntil("engine to reply", timeoutMs = ENGINE_REPLY_TIMEOUT_MS) {
                ui.lastOpponentMove != null
            }

            val reply = ui.lastOpponentMove!!
            assertEquals("Engine reply is not UCI long algebraic: ${reply.move}", 4, reply.move.length)
            assertTrue("Engine did not move a black piece: ${reply.move}", reply.move[1] in "78")
            assertNotNull("Engine reply carried no resulting position", reply.fen)
            // It is black's move that just happened, so it is white's turn again.
            assertTrue("Unexpected side to move after the reply: ${reply.fen}", " w " in reply.fen)
        } finally {
            orchestrator.stop()
            scope.cancel()
        }
    }

    @Test
    fun asksWhichEngineToPlayWhenMoreThanOneIsRegistered() = runBlocking {
        // With a single engine the orchestrator picks it silently, so this prompt - and the picker
        // that answers it - only became reachable when a second engine was added.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ui = AndroidUIModule()
        val board = FakeBoard(STARTING_FEN)
        // A legal black reply to 1.e4 - the stub plays second, so a white move would be
        // rejected by the rules engine and never reach the UI.
        val stub = StubEngine(name = "Stub Engine", move = "d7d5")
        val orchestrator = Orchestrator(
            board = board,
            gameController = GameController(scope),
            ui = ui,
            engines = listOf(CarballoAdapter(), stub),
        )

        try {
            scope.launch { orchestrator.start() }
            ui.requestNewGame()

            awaitUntil("game mode prompt") { ui.pendingGameModeSelection != null }
            ui.pendingGameModeSelection!!.complete(GameMode.ENGINE)

            awaitUntil("engine selection prompt") { ui.pendingEngineSelection != null }
            val choices = ui.pendingEngineSelection!!.input
            assertEquals(2, choices.size)
            assertTrue("Engines not named: $choices", choices.all { it.name.isNotBlank() })

            // Pick the second one. The orchestrator resolves the answer back to an adapter by
            // name, so choosing anything other than the first is what proves the mapping works.
            ui.pendingEngineSelection!!.complete("Stub Engine")

            awaitUntil("engine configuration prompt") { ui.pendingEngineGameConfig != null }
            assertEquals("Stub Engine", ui.pendingEngineGameConfig!!.input.engineName)
            ui.pendingEngineGameConfig!!.complete(
                EngineGameConfig(Color.WHITE, emptyMap(), timeControl = null)
            )

            awaitUntil("game to start") { ui.lastGameStartedInfo != null }
            val gameId = ui.lastGameStartedInfo!!.gameId

            board.setPosition(AFTER_E4_FEN)
            ui.signalMoveReady(gameId)

            awaitUntil("engine to reply") { ui.lastOpponentMove != null }
            // The chosen engine played, not the other one. Carballo would essentially never
            // pick this reply, so the move identifies which adapter answered.
            assertEquals("d7d5", ui.lastOpponentMove!!.move)
        } finally {
            orchestrator.stop()
            scope.cancel()
        }
    }

    /**
     * An engine that always plays the same move, so it is distinguishable from any real one.
     *
     * The point is to tell which adapter the orchestrator actually routed to; a real second engine
     * would answer plausibly either way and prove nothing.
     */
    private class StubEngine(
        private val name: String,
        private val move: String,
    ) : EngineAdapterModule {
        override val errors: SharedFlow<EngineErrorEvent> = MutableSharedFlow()

        override suspend fun getCapabilities(): Result<EngineError, EngineCapabilities> =
            Result.Success(EngineCapabilities(name = name, author = "test", parameters = emptyList()))

        override suspend fun configure(settings: Map<String, Any>): Result<EngineError, Unit> =
            Result.Success(Unit)

        override suspend fun getBestMove(params: GetBestMoveParams): Result<EngineError, BestMoveResult> =
            Result.Success(BestMoveResult(move = move))

        override suspend fun stop(): Result<EngineError, Unit> = Result.Success(Unit)

        override suspend fun newGame(): Result<EngineError, Unit> = Result.Success(Unit)
    }

    /**
     * A stand-in for the BLE board that reports whatever position the test last set.
     *
     * Reports [BoardCapabilityLevel.FULL] so move inference is unambiguous; the real board reports
     * colour only, which is why the orchestrator sometimes has to ask the user to disambiguate.
     */
    private class FakeBoard(initialFen: String) : BoardModule {
        @Volatile
        private var squares: Map<String, SquareState> = squaresFromFen(initialFen)

        override val errors: SharedFlow<BoardErrorEvent> = MutableSharedFlow()

        fun setPosition(fen: String) {
            squares = squaresFromFen(fen)
        }

        override suspend fun getCapabilities(): Result<BoardError, BoardCapabilities> =
            Result.Success(BoardCapabilities(BoardCapabilityLevel.FULL))

        override suspend fun getState(): Result<BoardError, BoardState> =
            Result.Success(BoardState(squares))
    }

    private companion object {
        const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        const val AFTER_E4_FEN = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        const val ENGINE_REPLY_TIMEOUT_MS = 60_000L

        /** Expands the placement field of a FEN into the per-square map a board would report. */
        fun squaresFromFen(fen: String): Map<String, SquareState> {
            val squares = mutableMapOf<String, SquareState>()
            val ranks = fen.substringBefore(' ').split('/')
            require(ranks.size == 8) { "Malformed FEN placement: $fen" }

            for ((index, rank) in ranks.withIndex()) {
                val rankNumber = 8 - index
                var file = 0
                for (symbol in rank) {
                    if (symbol.isDigit()) {
                        file += symbol - '0'
                        continue
                    }
                    val square = "${'a' + file}$rankNumber"
                    squares[square] = SquareState.OccupiedByPiece(pieceOf(symbol))
                    file++
                }
            }

            // Squares the FEN skipped are empty; the board reports all 64, not just occupied ones.
            for (file in 'a'..'h') {
                for (rank in 1..8) {
                    squares.putIfAbsent("$file$rank", SquareState.Empty)
                }
            }
            return squares
        }

        fun pieceOf(symbol: Char): Piece {
            val color = if (symbol.isUpperCase()) Color.WHITE else Color.BLACK
            val type = when (symbol.lowercaseChar()) {
                'p' -> PieceType.PAWN
                'n' -> PieceType.KNIGHT
                'b' -> PieceType.BISHOP
                'r' -> PieceType.ROOK
                'q' -> PieceType.QUEEN
                'k' -> PieceType.KING
                else -> error("Unknown piece in FEN: $symbol")
            }
            return Piece(type, color)
        }

        /** Polls [condition], failing with a useful message rather than hanging on timeout. */
        suspend fun awaitUntil(what: String, timeoutMs: Long = 10_000L, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                delay(50)
            }
            throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what")
        }
    }
}
