package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyPlayback
import com.music.bitchord.data.net.ProxyPlaybackPolicy
import com.music.bitchord.data.net.ProxyRoute
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * A refused URL can already be gone from [StreamResolver]'s short-lived cache
 * while [ProxyPlayback] still knows it was minted by the proxy. Restoring the
 * stamp from the refusal must not turn that registered proxy URL into a direct
 * one, or the next open after a route switch fetches it directly and gets 403.
 */
class StreamResolverRefusedRegisteredStampTest {

    private val musicOnly = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "primary.example",
        port = 8080,
        backupHost = "backup.example",
        backupPort = 9090,
        scope = ProxyScope.MUSIC_ONLY,
    )

    @AfterTest
    fun tearDown() {
        YouTubeProxy.update(ProxyConfig(mode = ProxyMode.HTTP, host = "cleanup.invalid", port = 1))
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @Test
    fun `a refused registered proxy url keeps its generation after the cache entry is gone`() {
        YouTubeProxy.update(musicOnly)
        val id = "refused-registered-${System.nanoTime()}"
        val url = "https://rr10.googlevideo.com/videoplayback?registered=$id"

        // Register the URL as proxy-minted without putting it in StreamResolver's
        // recent cache: the entry can have been evicted or expired.
        ProxyPlayback.markResolved(id, url)
        val minted = assertNotNull(ProxyPlayback.registeredGenerationFor(url))

        InnerTubeXResolver.rememberForTest(
            InnerTubeXResolver.Extracted(
                videoId = id,
                url = url,
                kbps = 128,
                mimeType = "audio/mp4",
                loudnessDb = null,
                clientName = "TEST",
                profileId = "TEST__po",
                headers = emptyMap(),
            ),
        )

        val refused = assertNotNull(StreamResolver.refuseMidPlay(url, 403))
        assertEquals(id, refused.videoId)
        StreamResolver.rememberServed(id, url)

        YouTubeProxy.switchRoute(ProxyRoute.BACKUP, "test")

        val stamp = assertNotNull(StreamResolver.cachedProxyStampForTest(id))
        assertEquals(
            minted,
            stamp.proxyGeneration,
            "a registered proxy URL must keep the route that minted it even when its cache entry is gone",
        )
        assertFalse(stamp.mintedOnAllScope)
        assertFalse(
            ProxyPlaybackPolicy.cachedUrlUsable(
                proxyGeneration = stamp.proxyGeneration,
                currentGeneration = YouTubeProxy.routeGeneration,
                canResolveViaProxy = ProxyPlayback.canUseCachedProxyUrl(),
                allScopeNow = false,
                mintedOnAllScope = stamp.mintedOnAllScope,
            ),
            "after a route switch the next open must re-resolve instead of fetching the old URL directly (403)",
        )
    }
}
