package com.music.bitchord.data.net

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-memory half of proxy playback: first-success reporting, URL
 * canonicalization, and the generation stamp that ties a URL to its route.
 * Every test uses its own ids so the process-wide memory cannot leak between
 * cases.
 */
class ProxyPlaybackStateTest {

    private val config = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "proxy.example",
        port = 8080,
        scope = ProxyScope.MUSIC_ONLY,
    )

    @AfterTest
    fun tearDown() {
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @Test
    fun `the first resolve of an id is reported once`() {
        YouTubeProxy.update(config)
        val id = "state-first-${System.nanoTime()}"
        val url = "https://rr1.googlevideo.com/videoplayback?x=$id"

        assertTrue(ProxyPlayback.markResolved(id, url))
        assertFalse(ProxyPlayback.markResolved(id, url))
        assertTrue(ProxyPlayback.isRemembered(id))
        assertTrue(ProxyPlayback.isProxiedUrl(url))
        assertNotNull(ProxyPlayback.generationFor(url))
        assertTrue(ProxyPlayback.usesProxyFor(url))
    }

    @Test
    fun `a direct url has no generation`() {
        assertNull(ProxyPlayback.generationFor("https://example.com/not-proxied"))
        assertFalse(ProxyPlayback.isProxiedUrl("https://example.com/not-proxied"))
    }

    @Test
    fun `the url is canonicalized the way okhttp will open it`() {
        YouTubeProxy.update(config)
        val id = "state-canonical-${System.nanoTime()}"
        ProxyPlayback.markResolved(id, "HTTPS://RR1.GOOGLEVIDEO.COM/videoplayback?x=1")

        assertTrue(
            ProxyPlayback.isProxiedUrl("https://rr1.googlevideo.com/videoplayback?x=1"),
        )
        assertNotNull(
            ProxyPlayback.generationFor("https://rr1.googlevideo.com/videoplayback?x=1"),
        )
    }

    @Test
    fun `a new url for the same id forgets the old one`() {
        YouTubeProxy.update(config)
        val id = "state-replace-${System.nanoTime()}"
        val old = "https://rr1.googlevideo.com/videoplayback?old=$id"
        val new = "https://rr1.googlevideo.com/videoplayback?new=$id"

        ProxyPlayback.markResolved(id, old)
        assertTrue(ProxyPlayback.isProxiedUrl(old))

        ProxyPlayback.markResolved(id, new)
        assertFalse(ProxyPlayback.isProxiedUrl(old))
        assertTrue(ProxyPlayback.isProxiedUrl(new))
        assertNull(ProxyPlayback.generationFor(old))
        assertNotNull(ProxyPlayback.generationFor(new))
    }

    @Test
    fun `a proxied url stops being usable when the proxy is switched off`() {
        YouTubeProxy.update(config)
        val id = "state-off-${System.nanoTime()}"
        val url = "https://rr1.googlevideo.com/videoplayback?off=$id"
        ProxyPlayback.markResolved(id, url)
        assertTrue(ProxyPlayback.usesProxyFor(url))

        YouTubeProxy.update(ProxyConfig())

        assertTrue(ProxyPlayback.isProxiedUrl(url), "membership survives the configuration change")
        assertFalse(ProxyPlayback.usesProxyFor(url))
    }

    @Test
    fun `the current generation is the proxy's own`() {
        YouTubeProxy.update(config)
        assertEquals(YouTubeProxy.routeGeneration, ProxyPlayback.currentRouteGeneration)
        assertTrue(ProxyPlayback.isCurrentRoute(YouTubeProxy.routeGeneration))
        assertFalse(ProxyPlayback.isCurrentRoute(YouTubeProxy.routeGeneration - 1L))
    }

    @Test
    fun `a proxied url is usable only on the route that minted it`() {
        YouTubeProxy.update(config)
        val id = "state-generation-${System.nanoTime()}"
        val url = "https://rr1.googlevideo.com/videoplayback?gen=$id"
        ProxyPlayback.markResolved(id, url)
        val generation = ProxyPlayback.generationFor(url)!!

        assertTrue(
            ProxyPlaybackPolicy.cachedUrlUsable(
                generation,
                ProxyPlayback.currentRouteGeneration,
                canResolveViaProxy = true,
            ),
        )

        YouTubeProxy.switchRoute(ProxyRoute.BACKUP, "test")

        assertFalse(ProxyPlayback.isCurrentRoute(generation))
        assertFalse(
            ProxyPlaybackPolicy.cachedUrlUsable(
                generation,
                ProxyPlayback.currentRouteGeneration,
                canResolveViaProxy = true,
            ),
        )
    }
}
