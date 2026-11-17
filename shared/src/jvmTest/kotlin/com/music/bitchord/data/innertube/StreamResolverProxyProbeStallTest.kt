package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyFailure
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyPlaybackPolicy
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import com.music.bitchord.ko.KoMediaProbe
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KO] review T1: a proxy walk whose player ask was answered but whose media
 * probe stalled (or got the proxy's own 502/504) heard nothing usable from the
 * route. It must end as proxy-down (transient, health monitor told), not as the
 * plain "found no stream" that is stored as a ten-minute verdict.
 *
 * [KO] review T1-A: but googlevideo's own 429/503/5xx answer (or a body that
 * ended short) came back over the whole route. The proxy is up and the answer
 * is about one URL for now: the walk ends transient, the proxy is not marked
 * unreachable (no five-minute bypass without a backup) and nothing is stored.
 */
class StreamResolverProxyProbeStallTest {

    private val stall = SocketTimeoutException("proxy media probe stalled")

    /** A Music-only proxy with no backup: one recordUnreachable starts the five-minute bypass. */
    private val proxy = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "proxy.example",
        port = 8080,
        scope = ProxyScope.MUSIC_ONLY,
    )

    @BeforeTest
    fun setUp() {
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @AfterTest
    fun tearDown() {
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @Test
    fun `a player answer whose media probe stalled is not an answer from the proxy`() {
        val answered = ProxyPlaybackPolicy.routeAnswered(refusalHeard = false, playerAnswered = true, mediaStalled = true)
        assertFalse(answered)
        val failure = assertNotNull(ProxyPlaybackPolicy.unansweredFailure("x", answered, stall))
        assertTrue(ProxyFailure.isProxyDown(failure), "the stall must reach the proxy-down branch")
        assertNull(UnplayableReason.fromMessage(failure.message), "a stalled probe is no recording verdict")
    }

    @Test
    fun `a refusal or a clean player answer still settles the walk`() {
        // A refused probe, a blocked or unplayable answer: the verdict is stored as before.
        assertTrue(ProxyPlaybackPolicy.routeAnswered(refusalHeard = true, playerAnswered = true, mediaStalled = true))
        assertNull(ProxyPlaybackPolicy.unansweredFailure("x", heardAnswer = true, fault = stall))
        // A player answer with no stalled probe (e.g. no audio formats) keeps SX-1's verdict.
        assertTrue(ProxyPlaybackPolicy.routeAnswered(refusalHeard = false, playerAnswered = true, mediaStalled = false))
        assertFalse(ProxyPlaybackPolicy.routeAnswered(refusalHeard = false, playerAnswered = false, mediaStalled = false))
    }

    @Test
    fun `resolveViaProxy judges the player answer with the probe verdicts`() {
        val resolver = source("shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt")
        val body = resolver.substringAfter("private suspend fun resolveViaProxy(")
            .substringBefore("private fun rememberProxyStream(")
        // Before T1 a player answer alone set the verdict flag.
        assertFalse("if (failure == null) heardAnswer = true" in body, "a player answer alone must not settle the verdict")
        assertTrue("Probe.UNREACHABLE" in body)
        assertTrue("SocketTimeoutException(\"proxy media probe stalled\")" in body)
        assertTrue("ProxyPlaybackPolicy.routeAnswered(heardAnswer, playerAnswered, mediaStalled)" in body)
        assertTrue(Regex("""onProbe = onProbe""").findAll(body).count() == 2, "both legs report their probes")

        val itx = resolver.substringAfter("private suspend fun innerTubeXStream(")
            .substringBefore("private fun directStream(")
        assertTrue(Regex("""onProbe\(verdict\)""").findAll(itx).count() == 2, "preferred and walked probes both report")
        val direct = resolver.substringAfter("private fun directProbe(").substringBefore("private suspend fun askAsUpload(")
        assertTrue("onProbe(verdict)" in direct)
        val uploads = resolver.substringAfter("private suspend fun innerTubeXStreamAllowingUploads(")
            .substringBefore("fun mediaHeadersFor(")
        assertTrue(Regex("""onProbe = onProbe""").findAll(uploads).count() == 3, "every inner walk passes the callback on")
    }

    // --- T1-A ------------------------------------------------------------

    @Test
    fun `googlevideo back-pressure codes are transient, a gateway's are not`() {
        for (code in listOf(429, 503, 500, 501, 505, 599)) {
            assertTrue(KoMediaProbe.isTransientCode(code), "HTTP $code")
        }
        for (code in listOf(502, 504, 403, 404, 410, 416, 200, 206, 400)) {
            assertFalse(KoMediaProbe.isTransientCode(code), "HTTP $code")
        }
    }

    @Test
    fun `a 429 media probe through a healthy proxy does not mark the proxy unreachable`() {
        val verdict = probeVerdict(answer(429, "Too Many Requests"))
        // Before T1-A: UNREACHABLE, read as a stalled route.
        assertEquals("TRANSIENT", verdict)
        assertTransientWalk(verdict)
    }

    @Test
    fun `a 503 media probe through a healthy proxy does not mark the proxy unreachable`() {
        val verdict = probeVerdict(answer(503, "Service Unavailable"))
        assertEquals("TRANSIENT", verdict)
        assertTransientWalk(verdict)
        // Any other googlevideo 5xx but a gateway's reads the same.
        assertEquals("TRANSIENT", probeVerdict(answer(500, "Internal Server Error")))
    }

    @Test
    fun `a gateway 502 or 504 still reads as a stalled route and marks the proxy down`() {
        assertEquals("UNREACHABLE", probeVerdict(answer(502, "Bad Gateway")))
        assertEquals("UNREACHABLE", probeVerdict(answer(504, "Gateway Timeout")))
        YouTubeProxy.update(proxy)
        val failure = assertNotNull(proxyWalkEnds(listOf("UNREACHABLE"), playerAnswered = true))
        assertTrue(ProxyFailure.isProxyDown(failure))
        assertFalse(KoMediaProbe.isTransient(failure))
        assertTrue(ProxyHealthMonitor.isMarkedUnreachable(), "T1 is unchanged for a stalled bytes leg")
    }

    @Test
    fun `a body that ended short is an answer, a body that stalls is still a stall`() {
        assertEquals("TRANSIENT", probeVerdict(audioHead(100), ByteArray(100)))
        // Waits out the probe's six-second call timeout: a trickle throws, it is not a short body.
        assertEquals("UNREACHABLE", probeVerdict(audioHead(32 * 1024), ByteArray(100), hold = true))
    }

    @Test
    fun `a transient answer outweighs a stall or a refusal elsewhere on the walk`() {
        YouTubeProxy.update(proxy)
        // googlevideo answered one URL through the tunnel: the route carries bytes.
        val besideStall = assertNotNull(proxyWalkEnds(listOf("UNREACHABLE", "TRANSIENT"), playerAnswered = true))
        assertFalse(ProxyFailure.isProxyDown(besideStall))
        assertFalse(ProxyHealthMonitor.isMarkedUnreachable())
        // A minted URL googlevideo only asked to wait on is no ten-minute verdict.
        assertTrue(KoMediaProbe.isTransient(proxyWalkEnds(listOf("REFUSED", "TRANSIENT"), playerAnswered = true)))
        // Without one, a refusal still settles the walk as before (null = ordinary "no stream").
        assertNull(proxyWalkEnds(listOf("REFUSED"), playerAnswered = true))
    }

    @Test
    fun `the transient failure is no verdict anywhere, even wrapped`() {
        val failure = assertNotNull(KoMediaProbe.transientFailure(mediaTransient = true))
        assertNull(KoMediaProbe.transientFailure(mediaTransient = false))
        assertFalse(ProxyFailure.isProxyDown(failure))
        assertNull(UnplayableReason.fromMessage(failure.message))
        // A coroutine stack-trace copy wraps the original as its cause.
        assertTrue(KoMediaProbe.isTransient(java.io.IOException("copy", failure)))
        assertFalse(KoMediaProbe.isTransient(java.io.IOException("stream resolution found no stream for x")))
    }

    @Test
    fun `resolveViaProxy and its callers let a transient walk through untouched`() {
        val resolver = source("shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt")
        val body = resolver.substringAfter("private suspend fun resolveViaProxy(")
            .substringBefore("private fun rememberProxyStream(")
        // proxyWalkEnds below mirrors these lines.
        assertTrue("} else if (verdict == Probe.TRANSIENT) {\n                mediaTransient = true" in body)
        val transientAt = body.indexOf("KoMediaProbe.transientFailure(mediaTransient)?.let { throw it }")
        val routeAt = body.indexOf("ProxyPlaybackPolicy.routeAnswered(heardAnswer, playerAnswered, mediaStalled)")
        assertTrue(transientAt in 0 until routeAt, "the transient exit comes before the stall reading")
        val downAt = body.indexOf("if (ProxyFailure.isProxyDown(e)) {")
        val recordAt = body.indexOf("ProxyHealthMonitor.recordUnreachable(")
        assertTrue(downAt in 0 until recordAt, "only a proxy-down failure tells the health monitor")

        val uncached = resolver.substringAfter("private suspend fun resolveUncached(")
            .substringBefore("private suspend fun resolveViaProxy(")
        val leg = uncached.substringAfter("fun startProxyLeg(").substringBefore("proxyLegRefusal = failure")
        assertTrue("!KoMediaProbe.isTransient(failure)" in leg, "a throttled leg is no proxy refusal")
        val exit = uncached.indexOf("if (KoMediaProbe.isTransient(proxyFailure)) throw proxyFailure")
        val store = uncached.indexOf("rememberUnplayable(videoId, reason)", (exit).coerceAtLeast(0))
        assertTrue(exit >= 0 && store > exit, "the proxy retry rethrows it before its verdict store")

        assertTrue("KoMediaProbe.isTransientCode(response.code) -> Probe.TRANSIENT" in resolver)
    }

    /** The walk end for one transient probe with the player ask answered, on a proxy with no backup. */
    private fun assertTransientWalk(verdict: String) {
        YouTubeProxy.update(proxy)
        val failure = assertNotNull(proxyWalkEnds(listOf(verdict), playerAnswered = true))
        assertTrue(KoMediaProbe.isTransient(failure))
        assertFalse(ProxyFailure.isProxyDown(failure), "googlevideo's back-pressure is not a dead proxy")
        assertFalse(ProxyHealthMonitor.isMarkedUnreachable(), "no five-minute bypass for one URL's back-pressure")
        assertNull(UnplayableReason.fromMessage(failure.message), "no ten-minute verdict either")
    }

    /**
     * resolveViaProxy's onProbe, its end of walk and its catch's health
     * record, in the order the source test above checks; the policies are the
     * real ones. Returns what the walk throws, or null for the plain
     * "found no stream".
     */
    private fun proxyWalkEnds(verdicts: List<String>, playerAnswered: Boolean): Exception? {
        var heardAnswer = false
        var mediaStalled = false
        var mediaTransient = false
        var routeFault: Throwable? = null
        for (verdict in verdicts) {
            when (verdict) {
                "REFUSED" -> heardAnswer = true
                "UNREACHABLE" -> {
                    mediaStalled = true
                    if (routeFault == null) routeFault = SocketTimeoutException("proxy media probe stalled")
                }
                "TRANSIENT" -> mediaTransient = true
            }
        }
        val failure: Exception? = KoMediaProbe.transientFailure(mediaTransient)
            ?: ProxyPlaybackPolicy.unansweredFailure(
                "x",
                ProxyPlaybackPolicy.routeAnswered(heardAnswer, playerAnswered, mediaStalled),
                routeFault,
            )
        if (failure != null && ProxyFailure.isProxyDown(failure)) ProxyHealthMonitor.recordUnreachable("stream x")
        return failure
    }

    private fun answer(code: Int, reason: String): String =
        "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    private fun audioHead(length: Int): String =
        "HTTP/1.1 206 Partial Content\r\nContent-Type: audio/webm\r\nContent-Length: $length\r\nConnection: close\r\n\r\n"

    /** The real probe's verdict for one local answer; [hold] keeps the body unfinished. */
    private fun probeVerdict(head: String, body: ByteArray = ByteArray(0), hold: Boolean = false): String {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val release = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    readHeaders(socket)
                    val out = socket.getOutputStream()
                    out.write(head.toByteArray())
                    out.write(body)
                    out.flush()
                    holdOpen(hold, release)
                }
            } catch (_: Exception) {
                // The server socket closed under a pending accept: the test is over.
            }
        }
        return try {
            StreamResolver.probeVerdictForTest("http://127.0.0.1:${server.localPort}/videoplayback?clen=20000")
        } finally {
            release.countDown()
            server.close()
            worker.join(2_000)
        }
    }

    /** Keeps the answer's socket open, its body unfinished, until the probe has given up. */
    private fun holdOpen(hold: Boolean, release: CountDownLatch) {
        if (hold) release.await(15, TimeUnit.SECONDS)
    }

    private fun readHeaders(socket: Socket) {
        val input = socket.getInputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return
        }
    }

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText().replace("\r\n", "\n")
    }
}
