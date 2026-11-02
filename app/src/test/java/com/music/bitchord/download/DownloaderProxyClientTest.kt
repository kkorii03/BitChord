package com.music.bitchord.download

import com.music.bitchord.data.Http
import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyPlayback
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `a proxied range fetch really leaves through the proxy`() {
        MockWebServer().use { proxy ->
            proxy.start()
            YouTubeProxy.update(
                ProxyConfig(
                    mode = ProxyMode.HTTP,
                    host = proxy.hostName,
                    port = proxy.port,
                    scope = ProxyScope.ALL_YOUTUBE,
                ),
            )
            val id = "download-proxy-range-${System.nanoTime()}"
            // A host that does not resolve: only the proxy can answer it.
            val url = "http://proxied-test.googlevideo.com/videoplayback?x=$id"
            ProxyPlayback.markResolved(id, url)
            ProxyHealthMonitor.recordUnreachable("test")
            proxy.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-0/100")
                    .setBody("through-proxy"),
            )

            Downloader.open(url, 0, 1).use { response ->
                assertEquals(206, response.code)
                assertEquals("through-proxy", response.body.string())
            }
            assertNotNull(proxy.takeRequest(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `an ordinary url keeps the shared client`() {
        assertSame(Http.client, Downloader.clientFor("https://example.com/file.mp3"))
    }
}
