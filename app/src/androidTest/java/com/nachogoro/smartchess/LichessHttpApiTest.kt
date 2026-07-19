package com.nachogoro.smartchess

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import scp.remote.lichess.LichessApiException
import scp.remote.lichess.LichessHttpApi

/**
 * Exercises the Lichess HTTP layer on a device.
 *
 * The adapter's own state machine is platform-independent and already covered by tests in the
 * protocol repo. What those cannot cover is this layer running under ART: the HTTP client was
 * rewritten from the JDK's `HttpClient` to OkHttp precisely because the former does not exist on
 * Android below API 34, and that rewrite had never actually executed on Android.
 *
 * The riskiest part is the NDJSON streaming used for the event and game streams - a long-lived
 * connection read line by line, with no read timeout, cancelled by cancelling the call. A mock
 * server stands in for Lichess so none of this needs an account or a token.
 */
@RunWith(AndroidJUnit4::class)
class LichessHttpApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LichessHttpApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = LichessHttpApi(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun readsTheAccountAndSendsTheBearerToken() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"nacho","username":"Nacho"}""")
        )

        val account = withTimeout(TIMEOUT_MS) { api.getAccount("test-token") }

        assertEquals("nacho", account.id)
        assertEquals("Nacho", account.username)

        val request = server.takeRequest()
        assertEquals("/api/account", request.path)
        // Without this header every call is anonymous and Lichess rejects it.
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun surfacesHttpErrorsAsApiExceptions() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val error = runCatching { api.getAccount("bad-token") }.exceptionOrNull()

        assertTrue("Expected a LichessApiException, got $error", error is LichessApiException)
        // An invalid token is the single most likely failure in the field, so the status code has
        // to survive: it is the only way to tell it apart from the platform being down.
        assertEquals(401, (error as LichessApiException).statusCode)
    }

    @Test
    fun streamsNdjsonLineByLine() = runBlocking {
        // Lichess sends one JSON object per line and holds the connection open, padding with blank
        // keep-alive lines. Those must not surface as events.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(
                    """
                    {"type":"gameStart","game":{"gameId":"abc123"}}

                    {"type":"gameFinish","game":{"gameId":"abc123"}}
                    """.trimIndent() + "\n"
                )
        )

        val lines = withTimeout(TIMEOUT_MS) { api.streamEvents("test-token").toList() }

        assertEquals("Blank keep-alive lines leaked into the stream: $lines", 2, lines.size)
        assertTrue(lines[0].contains("gameStart"))
        assertTrue(lines[1].contains("gameFinish"))
    }

    @Test
    fun stopsReadingWhenTheCollectorStops() = runBlocking {
        // A collector that abandons a stream must not be left waiting on the connection. The
        // streaming client deliberately has no read timeout, so nothing else would rescue it -
        // cancelling the collecting coroutine has to cancel the underlying call.
        //
        // The body is throttled so it is still arriving when the collector walks away: with a body
        // that completes on its own, this would pass whether or not cancellation worked.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setChunkedBody(
                    "{\"type\":\"gameStart\"}\n" + "{\"type\":\"later\"}\n".repeat(20),
                    16,
                )
                .throttleBody(16, 200, TimeUnit.MILLISECONDS)
        )

        val startedAt = System.currentTimeMillis()
        val first = withTimeout(TIMEOUT_MS) {
            api.streamEvents("test-token").take(1).toList()
        }
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(1, first.size)
        assertTrue(first[0].contains("gameStart"))
        // Draining the throttled body would take many seconds; returning fast is the evidence that
        // the read was cut short rather than run to completion.
        assertTrue("Took ${elapsed}ms, so the stream was drained rather than cancelled", elapsed < 3_000)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
