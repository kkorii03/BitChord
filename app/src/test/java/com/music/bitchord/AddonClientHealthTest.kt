package com.music.bitchord

import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonUnavailable
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The addon transport must leave evidence about the server it just spoke to:
 * a sleeping gateway is woken once, a slow answer is timed, and a refused
 * answer counts against the host. Before this was wired the gate only saw
 * successes, so a sleeping or slow addon was never sorted behind a healthy one
 * and every track sent it another wake-up request.
 */
class AddonClientHealthTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        HqHostTracker.resetForTest()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        HqHostTracker.resetForTest()
    }

    @Test
    fun `a sleeping gateway is asked exactly once`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503)
        }
        val client = AddonClient(server.url("/").toString())

        val result = client.probeSearch()

        assertTrue(result.isFailure)
        assertEquals("one wake request, not a retry storm", 1, server.requestCount)
        val host = HqHostTracker.hostOf(server.url("/").toString())
        assertTrue("the sleeping host must be rested", HqHostTracker.isResting(host))
    }

    @Test
    fun `a successful addon answer records its response time`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
        val client = AddonClient(server.url("/").toString())

        assertTrue(client.probeSearch().isSuccess)

        val host = HqHostTracker.hostOf(server.url("/").toString())
        val record = HqHostTracker.snapshotForTest()[host]
        assertNotNull(record)
        assertEquals(1, record!!.responseSamples)
        assertEquals(1, record.successes)
        assertTrue(record.lastResponseMs >= 0L)
    }

    @Test
    fun `a body cut off mid-response is a server fault and rests the host`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"tracks":[]}""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val client = AddonClient(server.url("/").toString())

        val result = client.probeSearch()

        assertTrue(result.isFailure)
        assertTrue(
            "a truncated body must reach callers as AddonUnavailable, not a bare IOException",
            result.exceptionOrNull() is AddonUnavailable,
        )
        val host = HqHostTracker.hostOf(server.url("/").toString())
        assertTrue("a body-read transport failure must rest the host", HqHostTracker.isResting(host))
        val record = HqHostTracker.snapshotForTest()[host]
        assertNotNull(record)
        assertEquals(1, record!!.responseSamples)
        assertEquals(1, record.failures)
    }

    @Test
    fun `a server fault counts against the host`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = AddonClient(server.url("/").toString())

        assertTrue(client.manifest().isFailure)

        val host = HqHostTracker.hostOf(server.url("/").toString())
        val record = HqHostTracker.snapshotForTest()[host]
        assertNotNull(record)
        assertEquals(1, record!!.failures)
        assertEquals(1, record.responseSamples)
    }

    @Test
    fun `a long host rest is refused instead of parked`() = runBlocking {
        // The addon's own ten-minute rest, asked for by the server itself. A
        // sleep of that length runs on the client's scope, which no caller can
        // cancel, so every track during the rest parked a coroutine and they
        // all woke on the same instant against a host that asked to be left
        // alone.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(429).setHeader("Retry-After", "600")
        }
        val client = AddonClient(server.url("/").toString())

        assertTrue(client.probeSearch().isFailure)

        val second = withTimeout(5_000) { client.probeSearch() }
        assertTrue(
            "a rest past the wait budget must fail as a source fault, not hang",
            second.exceptionOrNull() is AddonUnavailable,
        )
        assertEquals("the refused call must not reach the server", 1, server.requestCount)
    }

    @Test
    fun `a missing manifest is remembered instead of asked again`() = runBlocking {
        // An addon that never published one is still a working addon, and both
        // search and stream read the manifest for their settings — so a 404
        // that was forgotten was paid again on every call.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(404)
        }
        val client = AddonClient(server.url("/").toString())

        assertTrue(client.manifest().isFailure)
        assertTrue(client.manifest().isFailure)

        assertEquals("a 404 manifest must be remembered for its TTL", 1, server.requestCount)
    }
}
