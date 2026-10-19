package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scope rule decides which requests leave through the proxy. It is the
 * part of the feature that is unsafe to get wrong: proxying too much moves
 * ordinary pages and media onto a connection the listener did not choose, and
 * proxying too little leaves the Premium gate in place.
 */
class ProxyScopeMatcherTest {

    @Test
    fun `the music origin is proxied in music-only scope`() {
        assertTrue(ProxyScopeMatcher.matches("music.youtube.com", "/", ProxyScope.MUSIC_ONLY))
        assertTrue(
            ProxyScopeMatcher.matches(
                "music.youtube.com",
                "/youtubei/v1/browse",
                ProxyScope.MUSIC_ONLY,
            ),
        )
    }

    @Test
    fun `music browse is proxied in music-only scope`() {
        assertTrue(
            ProxyScopeMatcher.matches(
                "music.youtube.com",
                "/youtubei/v1/browse",
                ProxyScope.MUSIC_ONLY,
            ),
        )
        assertTrue(
            ProxyScopeMatcher.matches(
                "youtubei.googleapis.com",
                "/youtubei/v1/browse",
                ProxyScope.MUSIC_ONLY,
            ),
        )
    }

    @Test
    fun `music player is not proxied in music-only scope`() {
        assertFalse(
            ProxyScopeMatcher.matches(
                "music.youtube.com",
                "/youtubei/v1/player",
                ProxyScope.MUSIC_ONLY,
            ),
        )
    }

    @Test
    fun `youtubei player is not proxied in music-only scope`() {
        assertFalse(
            ProxyScopeMatcher.matches(
                "youtubei.googleapis.com",
                "/youtubei/v1/player",
                ProxyScope.MUSIC_ONLY,
            ),
        )
    }

    @Test
    fun `www player is not proxied in music-only scope`() {
        assertFalse(
            ProxyScopeMatcher.matches(
                "www.youtube.com",
                "/youtubei/v1/player",
                ProxyScope.MUSIC_ONLY,
            ),
        )
        assertFalse(
            ProxyScopeMatcher.matches("www.youtube.com", "/player", ProxyScope.MUSIC_ONLY),
        )
        assertFalse(
            ProxyScopeMatcher.matches(
                "m.youtube.com",
                "/youtubei/v1/player",
                ProxyScope.MUSIC_ONLY,
            ),
        )
    }

    @Test
    fun `www browse is not proxied in music-only scope`() {
        assertFalse(
            ProxyScopeMatcher.matches(
                "www.youtube.com",
                "/youtubei/v1/browse",
                ProxyScope.MUSIC_ONLY,
            ),
        )
        assertFalse(
            ProxyScopeMatcher.matches(
                "www.youtube.com",
                "/youtubei/v1/search",
                ProxyScope.MUSIC_ONLY,
            ),
        )
        assertFalse(
            ProxyScopeMatcher.matches(
                "m.youtube.com",
                "/youtubei/v1/browse",
                ProxyScope.MUSIC_ONLY,
            ),
        )
    }

    @Test
    fun `a watch page on www is not proxied in music-only scope`() {
        assertFalse(
            ProxyScopeMatcher.matches("www.youtube.com", "/watch", ProxyScope.MUSIC_ONLY),
        )
        assertFalse(
            ProxyScopeMatcher.matches("www.youtube.com", "/playlist", ProxyScope.MUSIC_ONLY),
        )
    }

    @Test
    fun `all-youtube scope adds web pages and media downloads`() {
        assertTrue(
            ProxyScopeMatcher.matches("www.youtube.com", "/watch", ProxyScope.ALL_YOUTUBE),
        )
        assertTrue(
            ProxyScopeMatcher.matches(
                "rr3---sn-a5mekn6s.googlevideo.com",
                "/videoplayback",
                ProxyScope.ALL_YOUTUBE,
            ),
        )
        assertTrue(
            ProxyScopeMatcher.matches("music.youtube.com", "/", ProxyScope.ALL_YOUTUBE),
        )
    }

    @Test
    fun `googlevideo is direct unless all-youtube scope is chosen`() {
        assertFalse(
            ProxyScopeMatcher.matches(
                "rr3---sn-a5mekn6s.googlevideo.com",
                "/videoplayback",
                ProxyScope.MUSIC_ONLY,
            ),
        )
    }

    @Test
    fun `other hosts are never proxied`() {
        assertFalse(ProxyScopeMatcher.matches("example.com", "/", ProxyScope.MUSIC_ONLY))
        assertFalse(ProxyScopeMatcher.matches("example.com", "/", ProxyScope.ALL_YOUTUBE))
        assertFalse(ProxyScopeMatcher.matches("notgooglevideo.com", "/", ProxyScope.ALL_YOUTUBE))
    }

    @Test
    fun `lookalike domains do not match`() {
        assertFalse(
            ProxyScopeMatcher.matches("evilyoutube.com", "/youtubei/v1/browse", ProxyScope.ALL_YOUTUBE),
        )
        assertFalse(
            ProxyScopeMatcher.matches(
                "music.youtube.com.evil.example",
                "/",
                ProxyScope.ALL_YOUTUBE,
            ),
        )
        assertFalse(
            ProxyScopeMatcher.matches("youtube.com.evil.example", "/", ProxyScope.ALL_YOUTUBE),
        )
    }

    @Test
    fun `host matching ignores case and a missing path`() {
        assertTrue(
            ProxyScopeMatcher.matches("MUSIC.YOUTUBE.COM", null, ProxyScope.MUSIC_ONLY),
        )
        assertFalse(
            ProxyScopeMatcher.matches("WWW.YouTube.com", "/YouTubei/v1/browse", ProxyScope.MUSIC_ONLY),
        )
        assertFalse(
            ProxyScopeMatcher.matches("MUSIC.YOUTUBE.COM", "/YouTubei/v1/player", ProxyScope.MUSIC_ONLY),
        )
    }
}
