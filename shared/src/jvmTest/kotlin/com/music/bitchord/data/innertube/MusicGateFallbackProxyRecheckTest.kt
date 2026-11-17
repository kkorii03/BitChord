package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A GATED verdict is a property of the route it was made on. These cases pin
 * down when a verdict made with a proxy configured may still be trusted: ten
 * minutes in the same process, never across a process boundary, and the old
 * daily rule when no proxy is in use.
 */
class MusicGateFallbackProxyRecheckTest {

    private class MemoryStore : MusicGateFallback.Store {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private val proxy = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "proxy.example",
        port = 8080,
        scope = ProxyScope.MUSIC_ONLY,
    )

    @BeforeTest
    fun setUp() {
        Innertube.cookie = null
        MusicGateFallback.resetForTest()
        YouTubeProxy.update(ProxyConfig())
    }

    @AfterTest
    fun tearDown() {
        MusicGateFallback.resetForTest()
        YouTubeProxy.update(ProxyConfig())
    }

    @Test
    fun `without a proxy a gated verdict is trusted for a day`() {
        val key = "u:daily"
        val at = 1_000_000L
        MusicGateFallback.init(MemoryStore())
        MusicGateFallback.markGated("test", key = key, now = at)

        assertFalse(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
        assertFalse(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
        assertFalse(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.RECHECK_MS - 1))
        assertTrue(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.RECHECK_MS))
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.RECHECK_MS))
    }

    @Test
    fun `with a usable proxy a gated verdict expires after ten minutes`() {
        val key = "u:proxy"
        val at = 2_000_000L
        MusicGateFallback.init(MemoryStore())
        YouTubeProxy.update(proxy)
        MusicGateFallback.markGated("test", key = key, now = at)

        assertFalse(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.PROXY_RECHECK_MS - 1))
        assertFalse(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS - 1))
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
        assertTrue(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
    }

    @Test
    fun `a gated verdict from an earlier process is rechecked when a proxy is usable`() {
        val store = MemoryStore()
        val key = "u:restart"
        val at = 3_000_000L
        MusicGateFallback.init(store)
        MusicGateFallback.markGated("test", key = key, now = at)

        // The verdict is fresh by the daily rule, but it was written before
        // this process existed, so the proxy route has not been proved.
        MusicGateFallback.resetForTest()
        YouTubeProxy.update(proxy)
        MusicGateFallback.init(store)

        // The verdict is still readable, but it does not shape requests until
        // the proxy route has been asked again.
        assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state(key))
        assertFalse(MusicGateFallback.isActive(key))
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = at + 1))
        assertTrue(MusicGateFallback.needsRecheck(key, now = at + 1))
    }

    @Test
    fun `an earlier process verdict keeps the daily rule without a proxy`() {
        val store = MemoryStore()
        val key = "u:restart-direct"
        val at = 4_000_000L
        MusicGateFallback.init(store)
        MusicGateFallback.markGated("test", key = key, now = at)

        MusicGateFallback.resetForTest()
        YouTubeProxy.update(ProxyConfig())
        MusicGateFallback.init(store)

        assertFalse(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
        assertFalse(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.RECHECK_MS))
    }

    // ---- N1: player / stream refusals only count on the route they took ----

    private val premiumPlayer = Json.parseToJsonElement(
        """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"This content requires YouTube Music Premium"}}""",
    ).jsonObject

    @Test
    fun `N1 a direct player refusal does not gate a proxy user`() {
        val key = MusicGateFallback.currentKey()
        YouTubeProxy.update(proxy)

        assertEquals(MusicGateFallback.Signal.NONE, MusicGateFallback.observePlayer(premiumPlayer))
        MusicGateFallback.observePremiumFailure("YoutubeMusicPremiumContentException: nope")

        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state(key))
        assertFalse(MusicGateFallback.isActive(key))
        assertTrue(MusicGateFallback.shouldTryMusic(key))
    }

    @Test
    fun `N1 a refusal that travelled the proxy still gates a proxy user`() {
        YouTubeProxy.update(proxy)
        MusicGateFallback.observePremiumFailure("YoutubeMusicPremiumContentException: nope", viaProxy = true)
        assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state())

        MusicGateFallback.resetForTest()
        assertEquals(
            MusicGateFallback.Signal.GATED,
            MusicGateFallback.observePlayer(premiumPlayer, viaProxy = true),
        )
        assertTrue(MusicGateFallback.isActive())
    }

    @Test
    fun `N1 without a proxy a direct refusal still gates`() {
        assertEquals(MusicGateFallback.Signal.GATED, MusicGateFallback.observePlayer(premiumPlayer))
        assertTrue(MusicGateFallback.isActive())

        MusicGateFallback.resetForTest()
        MusicGateFallback.observePremiumFailure("YoutubeMusicPremiumContentException: nope")
        assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state())
    }

    // ---- N2: a failed re-check is not a verdict ----------------------------

    @Test
    fun `N2 a failed re-check does not make a stored gated verdict trusted with a proxy`() {
        val store = MemoryStore()
        store.values["music_gate_fallback_v1"] =
            """{"version":1,"entries":{"u:k":{"state":"GATED","at":"1"}}}"""
        MusicGateFallback.init(store)
        YouTubeProxy.update(proxy)
        assertFalse(MusicGateFallback.isActive("u:k"))

        MusicGateFallback.touch("u:k", now = 7_000_000L)

        assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state("u:k"))
        assertFalse(MusicGateFallback.isActive("u:k"), "a failed check is not a verdict")
        // Home still re-checks; only the entry points back off (RG3-2).
        assertTrue(MusicGateFallback.shouldTryMusic("u:k", now = 7_000_001L))
        assertFalse(MusicGateFallback.needsRecheck("u:k", now = 7_000_001L))
    }

    @Test
    fun `N2 a failed re-check does not extend a stale gated verdict without a proxy`() {
        val key = "u:stale"
        val at = 8_000_000L
        MusicGateFallback.markGated("test", key = key, now = at)
        val late = at + MusicGateFallback.RECHECK_MS
        assertTrue(MusicGateFallback.needsRecheck(key, now = late))

        MusicGateFallback.touch(key, now = late)

        // The verdict's own window is not restarted: Home still re-checks it.
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = late + 1L))
        assertTrue(MusicGateFallback.isActive(key), "the verdict itself is unchanged")
    }

    // ---- RG3-2: a failed re-check of a GATED verdict backs the entry points off

    @Test
    fun `RG3-2 a failed gated re-check is not repeated by every entry point`() {
        val key = "u:rg32"
        val at = 8_500_000L
        MusicGateFallback.markGated("test", key = key, now = at)
        val late = at + MusicGateFallback.RECHECK_MS
        assertTrue(MusicGateFallback.needsRecheck(key, now = late))

        MusicGateFallback.touch(key, now = late)

        assertFalse(MusicGateFallback.needsRecheck(key, now = late + 1L))
        assertFalse(MusicGateFallback.needsRecheck(key, now = late + MusicGateFallback.RECHECK_MS - 1))
        assertTrue(MusicGateFallback.needsRecheck(key, now = late + MusicGateFallback.RECHECK_MS))
    }

    @Test
    fun `RG3-2 with a proxy a stored verdict backs off for ten minutes and stays untrusted`() {
        val store = MemoryStore()
        store.values["music_gate_fallback_v1"] =
            """{"version":1,"entries":{"u:rg32p":{"state":"GATED","at":"1"}}}"""
        MusicGateFallback.init(store)
        YouTubeProxy.update(proxy)
        val at = 9_500_000L
        assertTrue(MusicGateFallback.needsRecheck("u:rg32p", now = at))

        MusicGateFallback.touch("u:rg32p", now = at)

        assertFalse(MusicGateFallback.needsRecheck("u:rg32p", now = at + MusicGateFallback.PROXY_RECHECK_MS - 1))
        assertTrue(MusicGateFallback.needsRecheck("u:rg32p", now = at + MusicGateFallback.PROXY_RECHECK_MS))
        assertFalse(MusicGateFallback.isActive("u:rg32p"), "a failed check is not a verdict")
        assertTrue(MusicGateFallback.shouldTryMusic("u:rg32p", now = at + 1L))
    }

    @Test
    fun `RG3-2 a real verdict during the back-off is still recorded`() {
        val store = MemoryStore()
        store.values["music_gate_fallback_v1"] =
            """{"version":1,"entries":{"u:rg32v":{"state":"GATED","at":"1"}}}"""
        MusicGateFallback.init(store)
        YouTubeProxy.update(proxy)
        val at = 9_700_000L
        MusicGateFallback.touch("u:rg32v", now = at)

        MusicGateFallback.markGated("home upsell", key = "u:rg32v", now = at + 1L)

        assertTrue(MusicGateFallback.isActive("u:rg32v"), "a proved verdict is trusted")
        assertFalse(MusicGateFallback.needsRecheck("u:rg32v", now = at + 2L))
    }

    // ---- RG3-3: the stream extractor's own route ---------------------------

    @Test
    fun `RG3-3 an extractor refusal on an all-YouTube proxy route gates`() {
        YouTubeProxy.update(proxy.copy(scope = ProxyScope.ALL_YOUTUBE))
        assertTrue(MusicGateFallback.extractorRouteProxied())

        MusicGateFallback.observePremiumFailure(
            "YoutubeMusicPremiumContentException",
            MusicGateFallback.extractorRouteProxied(),
        )

        assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state())
    }

    @Test
    fun `RG3-3 a music-only proxy keeps the extractor's player direct`() {
        YouTubeProxy.update(proxy)
        assertFalse(MusicGateFallback.extractorRouteProxied())
        YouTubeProxy.update(ProxyConfig())
        assertFalse(MusicGateFallback.extractorRouteProxied())
    }

    @Test
    fun `N2 touch still backs an unknown identity off with a proxy`() {
        val key = "u:unknown-proxy"
        val at = 9_000_000L
        YouTubeProxy.update(proxy)
        MusicGateFallback.touch(key, now = at)
        assertFalse(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS - 1))
        assertTrue(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
    }
}
