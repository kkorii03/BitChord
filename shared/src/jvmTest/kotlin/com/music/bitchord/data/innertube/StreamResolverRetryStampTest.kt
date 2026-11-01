package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyRoute
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A same-URL retry is still bound to the route that minted the URL. Restoring
 * the stamp from the refusal, rather than reading the state after the retry,
 * keeps a direct URL out of ALL_YOUTUBE and an old-route URL off the new route.
 */
class StreamResolverRetryStampTest {

    private val allConfig = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "all.example",
        port = 8080,
        backupHost = "backup.example",
        backupPort = 9090,
        scope = ProxyScope.ALL_YOUTUBE,
    )

    @AfterTest
    fun tearDown() {
        YouTubeProxy.update(ProxyConfig(mode = ProxyMode.HTTP, host = "cleanup.invalid", port = 1))
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @Test
    fun `a same-url retry does not stamp a pre-all direct url as all-scope`() {
        val id = "retry-direct-${System.nanoTime()}"
        val url = "https://rr6.googlevideo.com/videoplayback?directretry=$id"

        YouTubeProxy.update(ProxyConfig())
        StreamResolver.rememberServed(id, url)
        YouTubeProxy.update(allConfig)

        val refused = assertNotNull(StreamResolver.refuseMidPlay(url, 403))
        assertNotNull(refused.url)

        StreamResolver.rememberServed(id, url)

        val stamp = assertNotNull(StreamResolver.cachedProxyStampForTest(id))
        assertFalse(
            stamp.mintedOnAllScope,
            "a URL minted directly must stay direct after ALL is enabled mid-track",
        )
        assertFalse(
            com.music.bitchord.data.net.ProxyPlaybackPolicy.cachedUrlUsable(
                proxyGeneration = stamp.proxyGeneration,
                currentGeneration = YouTubeProxy.routeGeneration,
                canResolveViaProxy = com.music.bitchord.data.net.ProxyPlayback.canUseCachedProxyUrl(),
                allScopeNow = true,
                mintedOnAllScope = stamp.mintedOnAllScope,
            ),
            "the next open must re-resolve instead of fetching the direct URL through the proxy",
        )
    }

    @Test
    fun `a same-url retry keeps the route that minted it after a route switch`() {
        YouTubeProxy.update(allConfig)
        val id = "retry-route-${System.nanoTime()}"
        val url = "https://rr7.googlevideo.com/videoplayback?routeretry=$id"

        StreamResolver.rememberServed(id, url)
        val minted = assertNotNull(StreamResolver.cachedProxyStampForTest(id)).proxyGeneration
        assertNotNull(minted)

        YouTubeProxy.switchRoute(ProxyRoute.BACKUP, "test")
        val refused = assertNotNull(StreamResolver.refuseMidPlay(url, 403))
        assertNotNull(refused.url)

        StreamResolver.rememberServed(id, url)

        val stamp = assertNotNull(StreamResolver.cachedProxyStampForTest(id))
        assertTrue(stamp.mintedOnAllScope)
        assertNotNull(stamp.proxyGeneration)
        assertFalse(
            stamp.proxyGeneration == YouTubeProxy.routeGeneration,
            "a retried URL must keep its minting route, not the route current after the switch",
        )
    }
}
