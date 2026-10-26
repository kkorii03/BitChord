package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scope rule's hostile and boundary inputs. Proxying one host too many
 * moves traffic the listener did not choose; one too few leaves the gate up.
 */
class ProxyScopeMatcherEdgeTest {

    @Test
    fun `a missing blank or whitespace host never matches`() {
        assertFalse(ProxyScopeMatcher.matches(null, "/", ProxyScope.MUSIC_ONLY))
        assertFalse(ProxyScopeMatcher.matches(null, "/", ProxyScope.ALL_YOUTUBE))
        assertFalse(ProxyScopeMatcher.matches("", "/", ProxyScope.MUSIC_ONLY))
        assertFalse(ProxyScopeMatcher.matches("   ", "/", ProxyScope.ALL_YOUTUBE))
        assertFalse(ProxyScopeMatcher.matches("\t\n", "/", ProxyScope.MUSIC_ONLY))
    }

    @Test
    fun `the host is trimmed and matched case-insensitively`() {
        assertTrue(
            ProxyScopeMatcher.matches("  MUSIC.YOUTUBE.COM  ", "/", ProxyScope.MUSIC_ONLY),
        )
        assertTrue(
            ProxyScopeMatcher.matches("WWW.YouTube.Com", "/watch", ProxyScope.ALL_YOUTUBE),
        )
        assertTrue(
            ProxyScopeMatcher.matches("RR1.GOOGLEVIDEO.COM", "/videoplayback", ProxyScope.ALL_YOUTUBE),
        )
    }

    @Test
    fun `music-only proxies the music origin but not its player path`() {
        for (path in listOf(
            "/",
            "/youtubei/v1/browse",
            "/youtubei/v1/search",
            "/youtubei/v1/next",
            "/watch",
            "/playlist",
        )) {
            assertTrue(
                ProxyScopeMatcher.matches("music.youtube.com", path, ProxyScope.MUSIC_ONLY),
                path,
            )
        }
        for (path in listOf(
            "/player",
            "/youtubei/v1/player",
            "/youtubei/v1/player?prettyPrint=false",
            "/get_watch",
            "/foo/get_watch/bar",
        )) {
            assertFalse(
                ProxyScopeMatcher.matches("music.youtube.com", path, ProxyScope.MUSIC_ONLY),
                path,
            )
        }
    }

    @Test
    fun `stream resolution is recognised regardless of case`() {
        assertFalse(
            ProxyScopeMatcher.matches(
                "music.youtube.com",
                "/YOUTUBEI/V1/PLAYER",
                ProxyScope.MUSIC_ONLY,
            ),
        )
        assertFalse(
            ProxyScopeMatcher.matches("music.youtube.com", "/GET_WATCH", ProxyScope.MUSIC_ONLY),
        )
        assertFalse(
            ProxyScopeMatcher.matches("music.youtube.com", "/Player", ProxyScope.MUSIC_ONLY),
        )
    }

    @Test
    fun `youtubei player is never proxied in music-only scope`() {
        assertFalse(
            ProxyScopeMatcher.matches(
                "youtubei.googleapis.com",
                "/youtubei/v1/player",
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
    fun `the web origins stay direct in music-only scope`() {
        for (host in listOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com.evil")) {
            for (path in listOf("/", "/watch", "/youtubei/v1/browse", "/youtubei/v1/player")) {
                assertFalse(
                    ProxyScopeMatcher.matches(host, path, ProxyScope.MUSIC_ONLY),
                    "$host $path",
                )
            }
        }
    }

    @Test
    fun `all-youtube covers every youtube host and googlevideo subdomain`() {
        for (host in listOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "music.youtube.com",
            "youtubei.googleapis.com",
            "googlevideo.com",
            "rr1.googlevideo.com",
            "rr3---sn-a5mekn6s.googlevideo.com",
        )) {
            assertTrue(
                ProxyScopeMatcher.matches(host, "/anything", ProxyScope.ALL_YOUTUBE),
                host,
            )
        }
    }

    @Test
    fun `lookalike hosts never match either scope`() {
        for (host in listOf(
            "evilyoutube.com",
            "notyoutube.com",
            "youtube.co",
            "youtube.com.evil.example",
            "music.youtube.com.evil.example",
            "evilgooglevideo.com",
            "googlevideo.com.evil.example",
            "youtubei.googleapis.com.evil.example",
            "example.com",
        )) {
            assertFalse(
                ProxyScopeMatcher.matches(host, "/", ProxyScope.ALL_YOUTUBE),
                host,
            )
            assertFalse(
                ProxyScopeMatcher.matches(host, "/", ProxyScope.MUSIC_ONLY),
                host,
            )
        }
    }

    @Test
    fun `a host carrying a port is not a host name`() {
        assertFalse(
            ProxyScopeMatcher.matches("music.youtube.com:443", "/", ProxyScope.MUSIC_ONLY),
        )
        assertFalse(
            ProxyScopeMatcher.matches("www.youtube.com:443", "/", ProxyScope.ALL_YOUTUBE),
        )
    }

    @Test
    fun `a null path is treated as the root`() {
        assertTrue(ProxyScopeMatcher.matches("music.youtube.com", null, ProxyScope.MUSIC_ONLY))
        assertTrue(ProxyScopeMatcher.matches("www.youtube.com", null, ProxyScope.ALL_YOUTUBE))
    }
}
