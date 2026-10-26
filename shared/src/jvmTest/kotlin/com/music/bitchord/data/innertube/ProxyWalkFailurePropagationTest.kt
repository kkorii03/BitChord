package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyFailure
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.YouTubeProxy
import java.io.IOException
import java.net.SocketException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyWalkFailurePropagationTest {

    @AfterTest
    fun tearDown() {
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @Test
    fun `a proxy connect failure is propagated instead of looking like no candidate`() {
        YouTubeProxy.update(
            ProxyConfig(
                mode = ProxyMode.HTTP,
                host = "127.0.0.1",
                port = 1,
            ),
        )

        assertFailsWith<IOException> {
            DirectClientResolver.candidates("proxy-walk-failure", maxKbps = 128, viaProxy = true)
                .toList()
        }
    }

    @Test
    fun `an IPv6 route failure is classified as the proxy being down`() {
        assertTrue(ProxyFailure.isProxyDown(SocketException("Network is unreachable")))
    }

    @Test
    fun `a rate-limited URL is forgotten so the next walk cannot reuse it`() {
        val videoId = "rate-limited-${System.nanoTime()}"
        val url = "https://rr1.googlevideo.com/videoplayback?x=$videoId"

        StreamResolver.rememberServed(videoId, url)
        assertEquals(videoId, StreamResolver.videoIdFor(url))

        StreamResolver.forgetCachedUrl(url)

        assertNull(StreamResolver.videoIdFor(url))
    }
}
