package com.nachogoro.smartchess

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import scp.engine.remote.RemoteStockfishAdapter
import scp.protocol.GetBestMoveParams
import scp.protocol.Result

/**
 * Exercises the remote Stockfish adapter on a device, against a mock server.
 *
 * Like the Lichess adapter, this one was moved from the JDK's `HttpClient` to OkHttp so it could
 * run on Android at all, and that rewrite had never executed under ART. The adapter takes its API
 * URL as a constructor parameter, so none of this needs the real chess-api.com service.
 */
@RunWith(AndroidJUnit4::class)
class RemoteStockfishEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: RemoteStockfishAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = RemoteStockfishAdapter(apiUrl = server.url("/v1").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun declaresItsParameters() = runBlocking {
        val caps = engine.getCapabilities()
        assertTrue("getCapabilities failed: $caps", caps is Result.Success)

        val value = (caps as Result.Success).value
        // The setup form is generated from these, so their names are what the form submits back.
        assertEquals(setOf("Depth", "MaxThinkingTime"), value.parameters.map { it.name }.toSet())
        assertTrue("Engine reported no name", value.name.isNotBlank())
    }

    @Test
    fun rejectsOutOfRangeSettings() = runBlocking {
        // The descriptors declare min/max and the form enforces them, but the adapter re-checks.
        // If this ever stopped failing, an out-of-range value would reach the API instead.
        assertTrue(engine.configure(mapOf("Depth" to 99)) is Result.Failure)
        assertTrue(engine.configure(mapOf("MaxThinkingTime" to 5_000)) is Result.Failure)
        assertTrue(engine.configure(mapOf("Depth" to 12)) is Result.Success)
    }

    @Test
    fun refusesToMoveBeforeBeingConfigured() = runBlocking {
        val result = RemoteStockfishAdapter(apiUrl = server.url("/v1").toString())
            .getBestMove(GetBestMoveParams(fen = STARTING_FEN))

        assertTrue("Expected NotConfigured, got $result", result is Result.Failure)
    }

    @Test
    fun asksTheApiForAMoveAndParsesTheReply() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"move":"e2e4","depth":12,"eval":0.3,"continuationArr":["e7e5","g1f3"]}
                    """.trimIndent()
                )
        )

        assertTrue(engine.configure(mapOf("Depth" to 12, "MaxThinkingTime" to 50)) is Result.Success)
        val result = withTimeout(TIMEOUT_MS) {
            engine.getBestMove(GetBestMoveParams(fen = STARTING_FEN))
        }

        assertTrue("getBestMove failed: $result", result is Result.Success)
        val best = (result as Result.Success).value
        assertEquals("e2e4", best.move)
        assertEquals(12, best.depth)
        assertEquals(listOf("e7e5", "g1f3"), best.pv)

        // The configured settings must actually reach the wire, not just be stored.
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(STARTING_FEN, body.getString("fen"))
        assertEquals(12, body.getInt("depth"))
        assertEquals(50, body.getInt("maxThinkingTime"))
    }

    @Test
    fun reportsAFailedRequestRatherThanHanging() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        assertTrue(engine.configure(mapOf("Depth" to 12)) is Result.Success)
        val result = withTimeout(TIMEOUT_MS) {
            engine.getBestMove(GetBestMoveParams(fen = STARTING_FEN))
        }

        // A network engine will fail in the field; it has to surface as a failure the orchestrator
        // can report, not as an exception or a silent stall.
        assertTrue("Expected a failure, got $result", result is Result.Failure)
    }

    private companion object {
        const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        const val TIMEOUT_MS = 20_000L
    }
}
