package com.music.bitchord.data.net

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A proxied URL is bound to the server that minted it. These cases pin the
 * invalidation: a route switch or a configuration edit must stop the old URL
 * from being handed back, while a direct URL survives either change.
 */
class ProxyRouteGenerationTest {

    private val withBackup = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "primary.example",
        port = 8080,
        backupHost = "backup.example",
        backupPort = 9090,
    )

    private val url = "https://rr1.googlevideo.com/videoplayback?x=1"

    @AfterTest
    fun tearDown() {
        YouTubeProxy.update(ProxyConfig(mode = ProxyMode.HTTP, host = "cleanup.invalid", port = 1))
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @Test
    fun `switching route invalidates the url minted on the old route`() {
        YouTubeProxy.update(withBackup)
        ProxyPlayback.markResolved("v1", url)
        val generation = ProxyPlayback.generationFor(url)
        assertNotNull(generation)
        assertTrue(ProxyPlayback.usesProxyFor(url))
        assertTrue(
            ProxyPlaybackPolicy.cachedUrlUsable(
                generation,
                ProxyPlayback.currentRouteGeneration,
                canResolveViaProxy = true,
            ),
        )

        YouTubeProxy.switchRoute(ProxyRoute.BACKUP, "test")

        assertTrue(ProxyPlayback.isProxiedUrl(url), "membership is kept so the URL is still known to be proxied")
        assertFalse(ProxyPlayback.isCurrentRoute(generation))
        assertFalse(ProxyPlayback.usesProxyFor(url))
        assertFalse(
            ProxyPlaybackPolicy.cachedUrlUsable(
                generation,
                ProxyPlayback.currentRouteGeneration,
                canResolveViaProxy = true,
            ),
        )
    }

    @Test
    fun `a new configuration invalidates the url minted by the old one`() {
        YouTubeProxy.update(withBackup)
        ProxyPlayback.markResolved("v2", url)
        val generation = ProxyPlayback.generationFor(url)
        assertNotNull(generation)

        YouTubeProxy.update(withBackup.copy(host = "other.example"))

        assertFalse(ProxyPlayback.isCurrentRoute(generation))
        assertFalse(ProxyPlayback.usesProxyFor(url))
    }

    @Test
    fun `an ALL_YOUTUBE url is stamped and invalidated by a route change`() {
        val all = withBackup.copy(scope = ProxyScope.ALL_YOUTUBE)
        YouTubeProxy.update(all)
        val allUrl = "https://rr2.googlevideo.com/videoplayback?all=1"
        val generation = ProxyPlayback.generationFor(allUrl)
        assertNotNull(generation)
        assertTrue(
            ProxyPlaybackPolicy.cachedUrlUsable(
                generation,
                ProxyPlayback.currentRouteGeneration,
                canResolveViaProxy = ProxyPlayback.canUseCachedProxyUrl(),
            ),
        )

        YouTubeProxy.switchRoute(ProxyRoute.BACKUP, "test")

        assertFalse(ProxyPlayback.isCurrentRoute(generation))
        assertFalse(
            ProxyPlaybackPolicy.cachedUrlUsable(
                generation,
                ProxyPlayback.currentRouteGeneration,
                canResolveViaProxy = ProxyPlayback.canUseCachedProxyUrl(),
            ),
        )
    }

    @Test
    fun `an explicit generation stamp keeps a url on the route that minted it`() {
        YouTubeProxy.update(withBackup)
        val minted = YouTubeProxy.routeGeneration
        YouTubeProxy.switchRoute(ProxyRoute.BACKUP, "test")
        val id = "v-explicit-${System.nanoTime()}"
        val explicitUrl = "https://rr3.googlevideo.com/videoplayback?explicit=$id"

        ProxyPlayback.markResolved(id, explicitUrl, minted)

        assertFalse(ProxyPlayback.isCurrentRoute(minted))
        assertFalse(ProxyPlayback.usesProxyFor(explicitUrl))
    }

    @Test
    fun `a direct url survives a route change`() {
        YouTubeProxy.update(withBackup)
        YouTubeProxy.switchRoute(ProxyRoute.BACKUP, "test")

        assertTrue(
            ProxyPlaybackPolicy.cachedUrlUsable(
                proxyGeneration = null,
                currentGeneration = ProxyPlayback.currentRouteGeneration,
                canResolveViaProxy = false,
            ),
        )
    }
}
