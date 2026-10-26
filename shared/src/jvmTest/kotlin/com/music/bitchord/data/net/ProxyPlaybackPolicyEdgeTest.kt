package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The route and cache decisions around proxy playback, including every
 * combination of the three inputs. A proxied URL is bound to the route that
 * minted it; a direct URL is not.
 */
class ProxyPlaybackPolicyEdgeTest {

    private val musicOnly = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "proxy.example",
        port = 8080,
        scope = ProxyScope.MUSIC_ONLY,
    )

    @Test
    fun `a direct url has no generation and always survives`() {
        for (current in listOf(0L, 1L, Long.MAX_VALUE)) {
            for (canResolve in listOf(true, false)) {
                assertTrue(
                    ProxyPlaybackPolicy.cachedUrlUsable(
                        proxyGeneration = null,
                        currentGeneration = current,
                        canResolveViaProxy = canResolve,
                    ),
                )
            }
        }
    }

    @Test
    fun `a proxied url is usable only on its own generation and while a proxy can resolve`() {
        assertTrue(
            ProxyPlaybackPolicy.cachedUrlUsable(
                proxyGeneration = 7L,
                currentGeneration = 7L,
                canResolveViaProxy = true,
            ),
        )
        assertFalse(
            ProxyPlaybackPolicy.cachedUrlUsable(
                proxyGeneration = 7L,
                currentGeneration = 7L,
                canResolveViaProxy = false,
            ),
        )
        assertFalse(
            ProxyPlaybackPolicy.cachedUrlUsable(
                proxyGeneration = 7L,
                currentGeneration = 8L,
                canResolveViaProxy = true,
            ),
        )
        assertFalse(
            ProxyPlaybackPolicy.cachedUrlUsable(
                proxyGeneration = 7L,
                currentGeneration = 8L,
                canResolveViaProxy = false,
            ),
        )
    }

    @Test
    fun `without a proxy every route is direct`() {
        for (remembered in listOf(true, false)) {
            for (refused in listOf(true, false)) {
                assertEquals(
                    ProxyPlaybackRoute.DIRECT,
                    ProxyPlaybackPolicy.route(remembered, refused, canProxy = false),
                    "remembered=$remembered refused=$refused",
                )
            }
        }
    }

    @Test
    fun `remembered wins over refused when the proxy is available`() {
        assertEquals(
            ProxyPlaybackRoute.PROXY_FIRST,
            ProxyPlaybackPolicy.route(remembered = true, refused = true, canProxy = true),
        )
        assertEquals(
            ProxyPlaybackRoute.PROXY_FIRST,
            ProxyPlaybackPolicy.route(remembered = true, refused = false, canProxy = true),
        )
    }

    @Test
    fun `a fresh refusal earns one proxy retry`() {
        assertEquals(
            ProxyPlaybackRoute.PROXY_RETRY,
            ProxyPlaybackPolicy.route(remembered = false, refused = true, canProxy = true),
        )
    }

    @Test
    fun `an ordinary direct success stays direct`() {
        assertEquals(
            ProxyPlaybackRoute.DIRECT,
            ProxyPlaybackPolicy.route(remembered = false, refused = false, canProxy = true),
        )
    }

    @Test
    fun `only a usable music-only proxy may play through the proxy`() {
        assertTrue(ProxyPlaybackPolicy.canProxyPlayback(musicOnly, proxyUnreachable = false))
        assertFalse(ProxyPlaybackPolicy.canProxyPlayback(musicOnly, proxyUnreachable = true))
        assertFalse(
            ProxyPlaybackPolicy.canProxyPlayback(
                musicOnly.copy(scope = ProxyScope.ALL_YOUTUBE),
                proxyUnreachable = false,
            ),
        )
        assertFalse(
            ProxyPlaybackPolicy.canProxyPlayback(
                musicOnly.copy(mode = ProxyMode.OFF),
                proxyUnreachable = false,
            ),
        )
        assertFalse(
            ProxyPlaybackPolicy.canProxyPlayback(
                musicOnly.copy(host = ""),
                proxyUnreachable = false,
            ),
        )
    }

    @Test
    fun `either region-refusal signal is enough`() {
        assertTrue(
            ProxyPlaybackPolicy.isRegionRefusal(
                signInConfirmNotBot = true,
                directUnplayable = true,
            ),
        )
        assertTrue(
            ProxyPlaybackPolicy.isRegionRefusal(
                signInConfirmNotBot = true,
                directUnplayable = false,
            ),
        )
        assertTrue(
            ProxyPlaybackPolicy.isRegionRefusal(
                signInConfirmNotBot = false,
                directUnplayable = true,
            ),
        )
        assertFalse(
            ProxyPlaybackPolicy.isRegionRefusal(
                signInConfirmNotBot = false,
                directUnplayable = false,
            ),
        )
    }
}
