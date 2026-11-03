package com.music.bitchord.download

import com.music.bitchord.data.Http
import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyPlayback
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * A download of a stream URL minted through the proxy must leave through the
 * proxy client, even while health memory says to bypass the proxy for new
 * requests. Otherwise the bytes arrive from the phone's own address and
 * googlevideo refuses a URL bound to the proxy's.
 */
class DownloaderProxyClientTest {

    private val config = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "proxy.example",
        port = 8080,
        scope = ProxyScope.MUSIC_ONLY,
    )

    @Before
    fun setUp() {
        YouTubeProxy.update(config)
        ProxyHealthMonitor.clear()
    }

    @After
    fun tearDown() {
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @Test
    fun `a url minted through the proxy downloads through the proxy client`() {
        val id = "download-proxy-${System.nanoTime()}"
        val url = "https://rr1.googlevideo.com/videoplayback?x=$id"
        ProxyPlayback.markResolved(id, url)
        ProxyHealthMonitor.recordUnreachable("test")

        assertSame(Http.proxyMediaClient, Downloader.clientFor(url))
    }

    @Test
    fun `an ordinary url keeps the shared client`() {
        assertSame(Http.client, Downloader.clientFor("https://example.com/file.mp3"))
    }
}
