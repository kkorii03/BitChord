package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyFailure
import com.music.bitchord.data.net.ProxyPlaybackPolicy
import java.io.File
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KO] review T1: a proxy walk whose player ask was answered but whose media
 * probe stalled (or got the proxy's own 502/504) heard nothing usable from the
 * route. It must end as proxy-down (transient, health monitor told), not as the
 * plain "found no stream" that is stored as a ten-minute verdict.
 */
class StreamResolverProxyProbeStallTest {

    private val stall = SocketTimeoutException("proxy media probe stalled")

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

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText().replace("\r\n", "\n")
    }
}
