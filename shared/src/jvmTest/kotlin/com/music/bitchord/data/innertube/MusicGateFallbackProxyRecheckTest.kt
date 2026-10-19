package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
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
}
