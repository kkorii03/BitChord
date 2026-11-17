package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.BlockedStartPolicy
import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyFailure
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyPlayback
import com.music.bitchord.data.net.ProxyPlaybackPolicy
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SX-1..SX-3: a gated start whose proxy was never heard (dead, stalled, in its
 * unreachable window, or cut by its ceiling) must not be stored as
 * "unplayable" for ten minutes; a proxy that really refused still is.
 */
class StreamResolverProxySilenceTest {

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

    // --- SX-1 -------------------------------------------------------------

    @Test
    fun `the old no-stream failure never reads as a dead proxy`() {
        // What a dead proxy ended as before SX-1: stored as a verdict.
        assertFalse(ProxyFailure.isProxyDown(IOException("stream resolution found no stream for x")))
    }

    @Test
    fun `a walk that heard nothing but network faults is proxy down`() {
        val refused = assertNotNull(
            ProxyPlaybackPolicy.unansweredFailure(
                "x",
                heardAnswer = false,
                fault = ConnectException("Failed to connect to /192.0.2.1:8888"),
            ),
        )
        assertTrue(ProxyFailure.isProxyDown(refused))
        assertNull(UnplayableReason.fromMessage(refused.message), "a network fault is no recording verdict")

        // OkHttp's callTimeout: not matched by isProxyDown on its own.
        val stalled = InterruptedIOException("timeout")
        assertFalse(ProxyFailure.isProxyDown(stalled))
        val stall = assertNotNull(ProxyPlaybackPolicy.unansweredFailure("x", heardAnswer = false, fault = stalled))
        assertTrue(ProxyFailure.isProxyDown(stall))
    }

    @Test
    fun `a walk the route answered keeps the ordinary no-stream verdict`() {
        val fault = ConnectException("Failed to connect")
        assertNull(ProxyPlaybackPolicy.unansweredFailure("x", heardAnswer = true, fault = fault))
        assertNull(ProxyPlaybackPolicy.unansweredFailure("x", heardAnswer = false, fault = null))
        assertNull(
            ProxyPlaybackPolicy.unansweredFailure("x", heardAnswer = false, fault = IllegalStateException("bad json")),
        )
    }

    @Test
    fun `resolveViaProxy listens for answers and throws the silence inside its proxy-down catch`() {
        val resolver = source("shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt")
        val body = resolver.substringAfter("private suspend fun resolveViaProxy(")
            .substringBefore("private fun rememberProxyStream(")
        assertTrue("onBlocked = { heardAnswer = true }" in body)
        assertTrue("onUnplayable = { heardAnswer = true }" in body)
        assertTrue("onAsked = { failure ->" in body)
        val silence = body.indexOf("ProxyPlaybackPolicy.unansweredFailure(")
        val downBranch = body.indexOf("ProxyFailure.isProxyDown(e)")
        assertTrue(silence in 0 until downBranch, "the silence must reach the health monitor branch")

        val direct = source("shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/DirectClientResolver.kt")
        assertTrue("onAsked(it.exceptionOrNull())" in direct)
        val probe = resolver.substringAfter("private fun directProbe(").substringBefore("private suspend fun askAsUpload(")
        assertTrue("onAsked = onAsked," in probe)
    }

    // --- SX-2 -------------------------------------------------------------

    @Test
    fun `a proxy in its unreachable window is still configured, so its start is not a verdict`() {
        YouTubeProxy.update(proxy)
        ProxyHealthMonitor.recordBackupUnreachable()
        assertFalse(ProxyPlayback.canResolveViaProxy())
        assertTrue(ProxyPlayback.isConfiguredForPlayback())
        // Before SX-2 the resolver passed canResolveViaProxy() here: final.
        assertTrue(BlockedStartPolicy.verdictIsFinal(proxyConfigured = ProxyPlayback.canResolveViaProxy(), proxyRefused = false))
        assertFalse(BlockedStartPolicy.verdictIsFinal(proxyConfigured = ProxyPlayback.isConfiguredForPlayback(), proxyRefused = false))
    }

    @Test
    fun `no proxy or an all-YouTube proxy is not a playback proxy`() {
        assertFalse(ProxyPlayback.isConfiguredForPlayback())
        YouTubeProxy.update(proxy.copy(scope = ProxyScope.ALL_YOUTUBE))
        assertFalse(ProxyPlayback.isConfiguredForPlayback())
    }

    @Test
    fun `proxyVerdictFinal reads the configuration, not the health`() {
        val resolver = source("shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt")
        val verdict = resolver.substringAfter("fun proxyVerdictFinal(): Boolean")
            .substringBefore("fun proxyLegAloneMs()")
        assertFalse("canResolveViaProxy()" in verdict)
        assertTrue("ProxyPlayback.isConfiguredForPlayback()" in verdict)
    }

    // --- SX-3 -------------------------------------------------------------

    @Test
    fun `a gate refusal is stored only when the proxy verdict is final`() {
        assertFalse(BlockedStartPolicy.storeVerdict(chainFinal = true, gateRefusal = true, proxyVerdictFinal = false))
        assertTrue(BlockedStartPolicy.storeVerdict(chainFinal = true, gateRefusal = true, proxyVerdictFinal = true))
        // A takedown is about the recording wherever it is asked from.
        assertTrue(BlockedStartPolicy.storeVerdict(chainFinal = true, gateRefusal = false, proxyVerdictFinal = false))
        // C4c-1's slow-proxy exit stays unstored.
        assertFalse(BlockedStartPolicy.storeVerdict(chainFinal = false, gateRefusal = false, proxyVerdictFinal = true))
        assertFalse(BlockedStartPolicy.storeVerdict(chainFinal = false, gateRefusal = true, proxyVerdictFinal = true))
    }

    @Test
    fun `the extraction exit store asks the proxy verdict`() {
        val resolver = source("shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt")
            .substringAfter("private suspend fun resolveUncached(")
            .substringBefore("private suspend fun resolveViaProxy(")
        val stores = Regex("""rememberUnplayable\(videoId, reason\)""").findAll(resolver).toList()
        assertEquals(2, stores.size)
        val last = stores.last().range.first
        val before = resolver.substring((last - 700).coerceAtLeast(0), last)
        assertTrue("isFinalVerdict(e)" in before)
        assertTrue("e.isProxyWorthyRefusal()" in before)
        assertTrue("proxyVerdictFinal()" in before)
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
