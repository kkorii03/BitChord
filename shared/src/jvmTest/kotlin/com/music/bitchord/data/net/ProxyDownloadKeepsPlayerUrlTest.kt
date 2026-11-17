package com.music.bitchord.data.net

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Review D8: a proxied download of the song that is playing through the proxy
 * replaced the player's URL in the proxied set, so the player's next range
 * went direct and got 403 mid-song (and its re-resolve then broke the download).
 */
class ProxyDownloadKeepsPlayerUrlTest {

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
    fun `a download url sits beside the player url`() {
        YouTubeProxy.update(config)
        val id = "dl-beside-${System.nanoTime()}"
        val play = "https://rr1.googlevideo.com/videoplayback?play=$id"
        val download = "https://rr2.googlevideo.com/videoplayback?download=$id"

        ProxyPlayback.markResolved(id, play)
        ProxyPlayback.markDownloadUrl(id, download)

        assertTrue(ProxyPlayback.isProxiedUrl(play), "the player's URL left the proxied set")
        assertTrue(ProxyPlayback.isProxiedUrl(download))
        assertTrue(ProxyPlayback.isRemembered(id))
    }

    @Test
    fun `the player's re-resolve keeps the download url`() {
        YouTubeProxy.update(config)
        val id = "dl-reresolve-${System.nanoTime()}"
        val play = "https://rr1.googlevideo.com/videoplayback?play=$id"
        val download = "https://rr2.googlevideo.com/videoplayback?download=$id"
        val replay = "https://rr3.googlevideo.com/videoplayback?replay=$id"

        ProxyPlayback.markResolved(id, play)
        ProxyPlayback.markDownloadUrl(id, download)
        ProxyPlayback.markResolved(id, replay)

        assertTrue(ProxyPlayback.isProxiedUrl(download), "the download's URL left the proxied set")
        assertTrue(ProxyPlayback.isProxiedUrl(replay))
    }

    @Test
    fun `a download of a song never played still counts as remembered`() {
        YouTubeProxy.update(config)
        val id = "dl-only-${System.nanoTime()}"
        assertTrue(ProxyPlayback.markDownloadUrl(id, "https://rr1.googlevideo.com/videoplayback?d=$id"))
        assertTrue(ProxyPlayback.isRemembered(id))
    }
}
