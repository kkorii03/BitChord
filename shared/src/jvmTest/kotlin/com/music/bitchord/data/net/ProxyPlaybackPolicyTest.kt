package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two decisions the proxy playback fallback rests on, kept free of
 * sockets and mutable global state:
 *
 *  - only the Korean-IP refusal pattern earns one extra walk;
 *  - only MUSIC_ONLY needs that walk, and only when the proxy is usable and
 *    not in its unreachable window.
 */
class ProxyPlaybackPolicyTest {

    private val musicOnly = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "proxy.example",
        port = 8080,
        scope = ProxyScope.MUSIC_ONLY,
    )

    private val allYouTube = musicOnly.copy(scope = ProxyScope.ALL_YOUTUBE)

    @Test
    fun `a sign-in-to-prove-not-a-bot refusal is a region refusal`() {
        assertTrue(
            ProxyPlaybackPolicy.isRegionRefusal(
                signInConfirmNotBot = true,
                directUnplayable = false,
            ),
        )
    }

    @Test
    fun `an UNPLAYABLE answer from the anonymous clients is a region refusal`() {
        assertTrue(
            ProxyPlaybackPolicy.isRegionRefusal(
                signInConfirmNotBot = false,
                directUnplayable = true,
            ),
        )
    }

    @Test
    fun `an ordinary failure is not a region refusal`() {
        assertFalse(
            ProxyPlaybackPolicy.isRegionRefusal(
                signInConfirmNotBot = false,
                directUnplayable = false,
            ),
        )
    }

    @Test
    fun `MUSIC_ONLY with a usable proxy may use proxy playback`() {
        assertTrue(ProxyPlaybackPolicy.canProxyPlayback(musicOnly, proxyUnreachable = false))
    }

    @Test
    fun `ALL_YOUTUBE keeps the existing path`() {
        assertFalse(ProxyPlaybackPolicy.canProxyPlayback(allYouTube, proxyUnreachable = false))
    }

    @Test
    fun `a disabled proxy keeps the existing path`() {
        assertFalse(ProxyPlaybackPolicy.canProxyPlayback(ProxyConfig(), proxyUnreachable = false))
    }

    @Test
    fun `an unreachable proxy is not retried`() {
        assertFalse(ProxyPlaybackPolicy.canProxyPlayback(musicOnly, proxyUnreachable = true))
    }

    @Test
    fun `without a usable proxy every route stays direct`() {
        assertEquals(
            ProxyPlaybackRoute.DIRECT,
            ProxyPlaybackPolicy.route(remembered = true, refused = true, canProxy = false),
        )
    }

    @Test
    fun `a remembered track starts at the proxy`() {
        assertEquals(
            ProxyPlaybackRoute.PROXY_FIRST,
            ProxyPlaybackPolicy.route(remembered = true, refused = false, canProxy = true),
        )
    }

    @Test
    fun `a refused direct walk earns exactly one proxy retry`() {
        assertEquals(
            ProxyPlaybackRoute.PROXY_RETRY,
            ProxyPlaybackPolicy.route(remembered = false, refused = true, canProxy = true),
        )
    }

    @Test
    fun `an ordinary successful direct walk stays direct`() {
        assertEquals(
            ProxyPlaybackRoute.DIRECT,
            ProxyPlaybackPolicy.route(remembered = false, refused = false, canProxy = true),
        )
    }
}
