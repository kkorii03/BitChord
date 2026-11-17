package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyPlayback
import com.music.bitchord.data.net.ProxyRoute
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KO] review R1: a 403 on a URL minted under an older proxy route is the route
 * changing under the track (its bytes now go over the wrong server), not the
 * client failing. It must not bench the InnerTubeX client for ten minutes; a
 * refusal on the route that minted the URL still does.
 */
class StreamResolverRouteRefusalBenchTest {

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

    private fun mintThroughProxy(tag: String): Pair<String, String> {
        val id = "$tag-${System.nanoTime()}"
        val url = "https://rr1---sn-x.googlevideo.com/videoplayback?id=$id&mime=audio%2Fmp4&clen=1000"
        InnerTubeXResolver.rememberForTest(
            InnerTubeXResolver.Extracted(
                videoId = id,
                url = url,
                kbps = 128,
                mimeType = "audio/mp4",
                loudnessDb = null,
                clientName = "WEB",
                profileId = PROFILE,
                headers = emptyMap(),
            ),
        )
        ProxyPlayback.markResolved(id, url, ProxyPlayback.currentRouteGeneration)
        return id to url
    }

    @Test
    fun `a 403 after a switch to the backup does not bench the client that minted the url`() {
        YouTubeProxy.update(musicOnly)
        val (id, url) = mintThroughProxy("route-switch")
        YouTubeProxy.switchRoute(ProxyRoute.BACKUP, "test")

        val refused = assertNotNull(StreamResolver.refuseMidPlay(url, 403))
        assertEquals(id, refused.videoId)
        assertFalse(PROFILE in InnerTubeXResolver.excludedForTest(id), "the route changed, the client did nothing wrong")
        assertNull(InnerTubeXResolver.extractedFor(url), "the dead URL is still forgotten")
    }

    @Test
    fun `a 403 after the proxy is turned off does not bench the client either`() {
        YouTubeProxy.update(musicOnly)
        val (id, url) = mintThroughProxy("proxy-off")
        YouTubeProxy.update(ProxyConfig())

        assertNotNull(StreamResolver.refuseMidPlay(url, 403))
        assertFalse(PROFILE in InnerTubeXResolver.excludedForTest(id))
    }

    @Test
    fun `a 403 on the route that minted the url still benches the client`() {
        YouTubeProxy.update(musicOnly)
        val (id, url) = mintThroughProxy("same-route")

        assertNotNull(StreamResolver.refuseMidPlay(url, 403))
        assertTrue(PROFILE in InnerTubeXResolver.excludedForTest(id))
        assertNull(InnerTubeXResolver.extractedFor(url))
    }

    private companion object {
        const val PROFILE = "WEB_CREATOR__po"
    }
}
