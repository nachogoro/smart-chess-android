package com.nachogoro.smartchess

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nachogoro.smartchess.data.GameHistoryDatabase
import com.nachogoro.smartchess.data.GameRecord
import com.nachogoro.smartchess.data.toPgn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import scp.protocol.Color
import scp.protocol.GameHistoryMove
import scp.protocol.GameMode
import scp.protocol.GameResult
import scp.protocol.GameSummary
import scp.protocol.Termination
import scp.protocol.TimeControl
import scp.protocol.TimeControlType

/**
 * Covers storing finished games and rendering them back out.
 *
 * Room generates its implementation at build time, so a schema mistake is only visible when a real
 * database is opened on a device, which is what this does, in memory.
 */
@RunWith(AndroidJUnit4::class)
class GameHistoryTest {

    private lateinit var database: GameHistoryDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, GameHistoryDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun storesAndReadsBackAGame() = runBlocking {
        val dao = database.games()
        dao.insert(GameRecord.from(summary(), playedAt = PLAYED_AT))

        val stored = dao.find("game-1")!!

        assertEquals("Alice", stored.whiteName)
        assertEquals("Bob", stored.blackName)
        assertEquals("1-0", stored.result)
        assertEquals(Termination.Checkmate.code, stored.terminationCode)
        assertEquals(GameMode.LOCAL.name, stored.mode)
        // The move list survives the round trip through its JSON column, in order and complete.
        assertEquals(listOf("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#"), stored.moves.map { it.san })
        assertEquals("e2e4", stored.moves.first().uci)
        assertTrue("Move kept no position", stored.moves.first().fen.isNotBlank())
    }

    @Test
    fun listsMostRecentFirst() = runBlocking {
        val dao = database.games()
        dao.insert(GameRecord.from(summary(gameId = "older"), playedAt = PLAYED_AT))
        dao.insert(GameRecord.from(summary(gameId = "newer"), playedAt = PLAYED_AT + 60_000))

        val ids = dao.observeAll().first().map { it.gameId }

        assertEquals(listOf("newer", "older"), ids)
    }

    @Test
    fun reportingTheSameGameTwiceDoesNotDuplicateIt() = runBlocking {
        // Adapters replay their last event, so a game can be reported finished more than once.
        val dao = database.games()
        dao.insert(GameRecord.from(summary(), playedAt = PLAYED_AT))
        dao.insert(GameRecord.from(summary(), playedAt = PLAYED_AT))

        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun deletesAGame() = runBlocking {
        val dao = database.games()
        dao.insert(GameRecord.from(summary(), playedAt = PLAYED_AT))

        dao.delete("game-1")

        assertNull(dao.find("game-1"))
    }

    @Test
    fun recordsAGameAsSoonAsItIsReportedFinished() = runBlocking {
        // Wired exactly as the view model wires it. The point is that persistence is driven by an
        // explicit flow rather than by observing Compose state, which outside a composition would
        // only fire if something pumped snapshot notifications.
        val ui = com.nachogoro.smartchess.ui.AndroidUIModule()
        val dao = database.games()

        val recorder = launch { ui.gameEnded.collect { dao.insert(GameRecord.from(it, PLAYED_AT)) } }
        try {
            ui.onGameEnded(summary())

            // The game must be stored without anyone dismissing a dialog first.
            val deadline = System.currentTimeMillis() + 5_000
            while (dao.find("game-1") == null && System.currentTimeMillis() < deadline) {
                delay(25)
            }
            assertEquals("1-0", dao.find("game-1")?.result)
        } finally {
            recorder.cancel()
        }
    }

    @Test
    fun rendersImportablePgn() {
        val pgn = GameRecord.from(summary(), playedAt = PLAYED_AT).toPgn()

        // The seven mandatory tags, in the order the export format requires.
        val tagNames = pgn.lineSequence()
            .takeWhile { it.startsWith("[") }
            .map { it.substringAfter('[').substringBefore(' ') }
            .toList()
        assertEquals(
            listOf("Event", "Site", "Date", "Round", "White", "Black", "Result"),
            tagNames.take(7),
        )

        assertTrue("Player names missing: $pgn", "[White \"Alice\"]" in pgn)
        assertTrue("Result tag missing: $pgn", "[Result \"1-0\"]" in pgn)
        // 15+10 in PGN's seconds+increment form.
        assertTrue("Time control not converted: $pgn", "[TimeControl \"900+10\"]" in pgn)

        // A blank line must separate the tags from the movetext.
        assertTrue("No blank line before movetext: $pgn", "\"]\n\n1." in pgn)

        val movetext = pgn.substringAfter("\n\n")
        assertTrue("Movetext not numbered per full move: $movetext", movetext.startsWith("1. e4 e5 2. Qh5"))
        assertTrue("Movetext not terminated by the result: $movetext", movetext.trim().endsWith("1-0"))
        // Every line must stay within the 80-column export limit.
        assertTrue(
            "A movetext line exceeded 80 columns: $movetext",
            movetext.lines().all { it.length <= 80 },
        )
    }

    @Test
    fun wrapsLongGamesAtEightyColumns() {
        val longGame = summary(moves = (1..120).map { move("Nf3", "g1f3") })

        val movetext = GameRecord.from(longGame, PLAYED_AT).toPgn().substringAfter("\n\n")

        assertTrue("Expected several lines, got: $movetext", movetext.lines().size > 1)
        assertTrue(
            "A movetext line exceeded 80 columns",
            movetext.lines().all { it.length <= 80 },
        )
        // Wrapping must not lose or invent tokens.
        assertEquals(120, Regex("\\bNf3\\b").findAll(movetext).count())
    }

    private companion object {
        const val PLAYED_AT = 1_700_000_000_000L

        fun move(san: String, uci: String) = GameHistoryMove(
            move = uci,
            san = san,
            fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            whiteMs = null,
            blackMs = null,
            timestamp = "2023-11-14T22:13:20Z",
        )

        fun summary(
            gameId: String = "game-1",
            moves: List<GameHistoryMove> = listOf(
                move("e4", "e2e4"), move("e5", "e7e5"),
                move("Qh5", "d1h5"), move("Nc6", "b8c6"),
                move("Bc4", "f1c4"), move("Nf6", "g8f6"),
                move("Qxf7#", "h5f7"),
            ),
        ) = GameSummary(
            gameId = gameId,
            mode = GameMode.LOCAL,
            whiteName = "Alice",
            blackName = "Bob",
            playerColor = Color.WHITE,
            timeControl = TimeControl(TimeControlType.FISCHER, initialMs = 900_000, incrementMs = 10_000),
            result = GameResult.WHITE_WINS,
            termination = Termination.Checkmate,
            finalFen = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4",
            moves = moves,
        )
    }
}
