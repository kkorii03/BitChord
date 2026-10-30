package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyPlaybackPolicy
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An ALL_YOUTUBE URL is bound to the proxy route even though it never passes
 * through the Music-only `markResolved` fallback. These cases pin the stamp a
 * retried URL keeps (rememberServed passes no capture) and the walk boundary
 * rule that keeps a bypass-minted direct URL from being followed through the
 * proxy later.
 */
class StreamResolverAllScopeStampTest {

    private val allConfig = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "all.example",
        port = 8080,
        scope = ProxyScope.ALL_YOUTUBE,
    )

    @AfterTest
    fun tearDown() {
        YouTubeProxy.update(ProxyConfig(mode = ProxyMode.HTTP, host = "cleanup.invalid", port = 1))
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @Test
    fun `a retried all-scope url keeps its route stamp`() {
        YouTubeProxy.update(allConfig)
        val id = "all-retry-${System.nanoTime()}"
        val url = "https://rr6.googlevideo.com/videoplayback?allretry=$id"

        StreamResolver.rememberServed(id, url)

        val stamp = assertNotNull(StreamResolver.cachedProxyStampForTest(id))
        assertNotNull(stamp.proxyGeneration, "an ALL url must keep the route that minted it")
        assertTrue(stamp.mintedOnAllScope, "the next open must follow the proxy for this URL")
    }

    @Test
    fun `a walk that enters the bypass mid-flight is not stamped as all-scope`() {
        assertFalse(
            ProxyPlaybackPolicy.mintedOnAllScopeAfterWalk(
                allScopeAtStart = true,
                allScopeAtEnd = false,
            ),
            "a direct URL minted during a bypass must not be fetched through the proxy later",
        )
    }

    @Test
    fun `a walk that switches into all-scope mid-flight is not stamped as all-scope`() {
        assertFalse(
            ProxyPlaybackPolicy.mintedOnAllScopeAfterWalk(
                allScopeAtStart = false,
                allScopeAtEnd = true,
            ),
            "a URL minted directly before the switch must not be treated as proxy-minted",
        )
    }

    @Test
    fun `a walk that stays on all-scope is stamped as all-scope`() {
        assertTrue(
            ProxyPlaybackPolicy.mintedOnAllScopeAfterWalk(
                allScopeAtStart = true,
                allScopeAtEnd = true,
            ),
        )
    }
}
