package com.music.bitchord

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonUnavailable
import com.music.bitchord.ko.KoStreamProbe
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        SourceResolver.SourceCooldown.resetForTest()
        DeviceCodecs.forced = true
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        HqHostTracker.resetForTest()
        SourceResolver.SourceCooldown.resetForTest()
        DeviceCodecs.forced = null
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

    // ── A search that failed is not "the catalogue does not have it" ─────

    @Test
    fun `a search 404 is not a confirmed miss`() = runBlocking {
        assertTrue("a 404 on /search must raise the failure mark", raisesFailureMark(MockResponse().setResponseCode(404)))
    }

    @Test
    fun `a search answer that cannot be read is not a confirmed miss`() = runBlocking {
        assertTrue("unparseable", raisesFailureMark(jsonBody("<html>not an answer</html>")))
        assertTrue("empty body", raisesFailureMark(MockResponse().setBody("")))
        assertTrue("4xx", raisesFailureMark(MockResponse().setResponseCode(400)))
    }

    @Test
    fun `a 200 carrying an error instead of tracks is not a confirmed miss`() = runBlocking {
        assertTrue(raisesFailureMark(jsonBody("""{"error":"all backends exhausted"}""")))
    }

    @Test
    fun `an empty catalogue answer is still a confirmed miss`() = runBlocking {
        assertFalse("tracks empty", raisesFailureMark(jsonBody("""{"tracks":[]}""")))
        // The spec lets an addon send only the arrays it has.
        assertFalse("no tracks array", raisesFailureMark(jsonBody("""{"albums":[]}""")))
        assertFalse("no arrays at all", raisesFailureMark(jsonBody("""{}""")))
    }

    @Test
    fun `a stream 404 stays an ordinary miss`() = runBlocking {
        route("/manifest.json" to manifest())
        val config = config()
        val before = SourceResolver.SourceCooldown.failureMark(config.id)

        assertNull(AddonSource(config).stream("nope", StreamRequest.Lossless))

        assertEquals(before, SourceResolver.SourceCooldown.failureMark(config.id))
    }

    // ── A short wait must not cancel a long rest ─────────────────────────

    @Test
    fun `a short retry does not shorten a rest the server asked for`() = runBlocking {
        val heldArrived = CountDownLatch(1)
        val releaseHeld = CountDownLatch(1)
        val arrivals = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (arrivals.incrementAndGet()) {
                    1 -> {
                        heldArrived.countDown()
                        releaseHeld.await(20, TimeUnit.SECONDS)
                        MockResponse().setResponseCode(429)
                    }
                    2 -> MockResponse().setResponseCode(429).setHeader("Retry-After", "600")
                    else -> MockResponse().setBody("""{"tracks":[]}""")
                }
        }
        val client = AddonClient(server.url("/").toString())

        val held = async(Dispatchers.IO) { client.probeSearch() }
        withContext(Dispatchers.IO) { assertTrue(heldArrived.await(20, TimeUnit.SECONDS)) }
        assertTrue("the ten-minute refusal", client.probeSearch().isFailure)
        releaseHeld.countDown()

        val answer = withTimeout(20_000) { held.await() }
        assertEquals("the held call must wait out the ten minutes too", 2, server.requestCount)
        assertTrue(answer.exceptionOrNull() is AddonUnavailable)
    }

    // ── What the addon said about the codec ──────────────────────────────

    @Test
    fun `a stated mp3 is not read as flac because of its quality label`() = runBlocking {
        val format = formatOf("""{"url":"https://cdn/t.128.mp3","format":"mp3","quality":"LOSSLESS"}""")
        assertEquals("mp3", format.codec)
        assertEquals(false, format.isLossless)
    }

    @Test
    fun `the URL extension beats a lossless label`() = runBlocking {
        val format = formatOf("""{"url":"https://cdn/t.128.mp3","quality":"LOSSLESS"}""")
        assertEquals("mp3", format.codec)
    }

    @Test
    fun `the format field names the codec of an extensionless URL`() = runBlocking {
        val format = formatOf("""{"url":"https://cdn/track","format":"mp3","quality":"320kbps"}""")
        assertEquals("mp3", format.codec)
    }

    @Test
    fun `an mp4 labelled lossless is not called lossy`() = runBlocking {
        val format = formatOf(
            """{"url":"https://cdn/track","mimeType":"audio/mp4","quality":"HI_RES_LOSSLESS"}""",
        )
        assertEquals(true, format.isLossless)
    }

    @Test
    fun `an mp4 with a lossy label stays an mp4`() = runBlocking {
        val format = formatOf("""{"url":"https://cdn/track","mimeType":"audio/mp4","quality":"320kbps"}""")
        assertEquals("mp4", format.codec)
        assertEquals(false, format.isLossless)
    }

    // ── An address with a query ──────────────────────────────────────────

    @Test
    fun `a query does not stop the manifest filename coming off`() {
        assertEquals("https://h/tok?v=2", AddonClient.normalizeBase("https://h/tok/manifest.json?v=2"))
        assertEquals("https://h/tok/manifest.json?v=2", AddonClient.manifestUrl("https://h/tok?v=2"))
    }

    @Test
    fun `a query on the root keeps the manifest in the path`() {
        val base = AddonClient.normalizeBase("https://h/?token=a")
        assertEquals("https://h/manifest.json?token=a", AddonClient.manifestUrl(base))
    }

    @Test
    fun `a slash or json inside a query is not a path`() {
        assertEquals("https://h/tok?next=a/b.json", AddonClient.normalizeBase("https://h/tok/?next=a/b.json"))
    }

    @Test
    fun `every request to an address with a query carries its path and query`() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.path.orEmpty()
                return MockResponse().setBody("""{"tracks":[]}""")
            }
        }
        val client = AddonClient(server.url("/tok/manifest.json?v=2").toString())

        client.manifest()
        assertTrue(client.search("x", AddonClient.TIER_HIGH).isSuccess)

        assertTrue("$seen", seen.any { it == "/tok/manifest.json?v=2" })
        assertTrue("$seen", seen.any { it.startsWith("/tok/search?v=2&") })
    }

    // ── A tag in front of the audio ──────────────────────────────────────

    /** An ID3v2 tag of 10,000 bytes, which is past the probe's first look, then [audio]. */
    private fun taggedFile(audio: ByteArray): MockResponse {
        val size = 10_000
        val header = "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            4, 0, 0,
            ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
        )
        val file = header + ByteArray(size) + audio
        return MockResponse().setBody(okio.Buffer().write(file))
    }

    private fun flacClaim() = SourceStream(
        url = server.url("/file").toString(),
        format = StreamFormat("flac"),
    )

    @Test
    fun `a flac behind a large id3 tag is not called lossy`() = runBlocking {
        server.enqueue(taggedFile("fLaC".toByteArray(Charsets.US_ASCII) + ByteArray(38)))
        assertTrue(KoStreamProbe.servesBytes(flacClaim()))
    }

    @Test
    fun `an mp3 behind a large id3 tag still is`() = runBlocking {
        server.enqueue(taggedFile(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0) + ByteArray(38)))
        assertFalse(KoStreamProbe.servesBytes(flacClaim()))
    }

    // ── What reaches the log ─────────────────────────────────────────────

    @Test
    fun `a refused stream URL is not written to the log`() = runBlocking {
        route(
            "/manifest.json" to manifest(),
            "/stream/t1" to jsonBody("""{"url":"/secret-token/path.flac"}"""),
        )

        val lines = captureLog {
            assertNull(AddonSource(config()).stream("t1", StreamRequest.Lossless))
        }

        assertTrue("the refusal is still logged", lines.any { "malformed URL" in it })
        assertFalse("$lines", lines.any { "secret-token" in it })
    }

    @Test
    fun `an unreadable answer is not quoted in the log`() = runBlocking {
        route(
            "/manifest.json" to manifest(),
            "/search" to jsonBody("""{"tracks":"https://cdn/secret-token/a.flac"}"""),
        )

        val lines = captureLog {
            assertTrue(AddonSource(config()).search("x", limit = 5).isEmpty())
        }

        assertTrue("the failure is still logged", lines.any { "search failed" in it })
        assertFalse("$lines", lines.any { "secret-token" in it })
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun jsonBody(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun manifest() = jsonBody(
        """{"id":"com.test.addon","name":"Test Addon","version":"2.1.0","resources":["search","stream"]}""",
    )

    private fun config() = SourceConfig(
        kind = SourceKind.ADDON,
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun route(vararg routes: Pair<String, MockResponse>) {
        val byPath = routes.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                byPath[request.requestUrl?.encodedPath.orEmpty()] ?: MockResponse().setResponseCode(404)
        }
    }

    /** Whether a search answered [answer] counts against the source, on a source of its own. */
    private suspend fun raisesFailureMark(answer: MockResponse): Boolean {
        route("/manifest.json" to manifest(), "/search" to answer)
        val config = config()
        val before = SourceResolver.SourceCooldown.failureMark(config.id)
        assertTrue(AddonSource(config).search("x", limit = 5).isEmpty())
        return SourceResolver.SourceCooldown.failureMark(config.id) != before
    }

    private suspend fun formatOf(answer: String): StreamFormat {
        route("/manifest.json" to manifest(), "/stream/t1" to jsonBody(answer))
        return AddonSource(config()).stream("t1", StreamRequest.Lossless)!!.format
    }

    private suspend fun captureLog(block: suspend () -> Unit): List<String> {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = TrackLog.echo
        TrackLog.echo = { _, _, message, _ -> lines += message }
        try {
            block()
        } finally {
            TrackLog.echo = previous
        }
        return lines.toList()
    }
}
