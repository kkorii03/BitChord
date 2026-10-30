package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Home starts its chart lookup beside the Music browse. The old path checked
 * the gate verdict again after the browse and sent a still-gated answer to the
 * youtube.com fallback; the overlap refactor dropped that check, so a listener
 * whose re-check left the verdict GATED saw the empty Premium feed instead.
 * These assertions pin the old routing rule.
 */
class HomeGateRouteTest {

    @Test
    fun `a music browse that returns while the gate is active is not rendered`() {
        assertFalse(YtMusicRepository.shouldRenderMusicHome(gateActive = true))
    }

    @Test
    fun `an open gate renders the music browse`() {
        assertTrue(YtMusicRepository.shouldRenderMusicHome(gateActive = false))
    }
}
