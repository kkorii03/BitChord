package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule behind #21: a usable, reachable proxy is not bypassed with the
 * youtube.com fallback, while a no-proxy install keeps the old gate fallback.
 */
class MusicGateFallbackProxyPolicyTest {

    @Test
    fun `a usable proxy that is unreachable still uses the fallback`() {
        assertTrue(
            MusicGateFallback.shouldUseYouTubeFallback(
                proxyUsable = true,
                proxyUnreachable = true,
                gateActive = false,
                gateSignal = false,
            ),
        )
    }

    @Test
    fun `a healthy usable proxy uses the fallback only for its own gate refusal`() {
        assertFalse(
            MusicGateFallback.shouldUseYouTubeFallback(
                proxyUsable = true,
                proxyUnreachable = false,
                gateActive = false,
                gateSignal = false,
            ),
        )
        assertTrue(
            MusicGateFallback.shouldUseYouTubeFallback(
                proxyUsable = true,
                proxyUnreachable = false,
                gateActive = true,
                gateSignal = true,
            ),
        )
        assertFalse(
            MusicGateFallback.shouldUseYouTubeFallback(
                proxyUsable = true,
                proxyUnreachable = false,
                gateActive = true,
                gateSignal = false,
            ),
        )
    }

    @Test
    fun `without a proxy the existing gate rule is unchanged`() {
        assertTrue(
            MusicGateFallback.shouldUseYouTubeFallback(
                proxyUsable = false,
                proxyUnreachable = false,
                gateActive = true,
                gateSignal = false,
            ),
        )
        assertFalse(
            MusicGateFallback.shouldUseYouTubeFallback(
                proxyUsable = false,
                proxyUnreachable = true,
                gateActive = false,
                gateSignal = true,
            ),
        )
    }
}
