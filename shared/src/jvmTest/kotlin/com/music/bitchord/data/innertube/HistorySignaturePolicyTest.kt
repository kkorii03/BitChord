package com.music.bitchord.data.innertube

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A gated session's tracking block comes from youtube.com, which answers
 * without a signature timestamp; only the Music client needs one. The tracker
 * used to demand one either way, so every gated track paid for a player-JS
 * fetch that could only fail and then retried it three times.
 */
class HistorySignaturePolicyTest {

    @AfterTest
    fun tearDown() {
        MusicGateFallback.resetForTest()
    }

    @Test
    fun `the gated youtube fallback does not need a signature timestamp`() {
        assertFalse(MusicGateFallback.trackingNeedsSignatureTimestamp(gateActive = true))
    }

    @Test
    fun `the music client still needs a signature timestamp`() {
        assertTrue(MusicGateFallback.trackingNeedsSignatureTimestamp(gateActive = false))
    }
}
