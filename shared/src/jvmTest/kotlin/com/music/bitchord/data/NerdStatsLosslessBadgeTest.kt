package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NerdStatsLosslessBadgeTest {
    private fun flac(
        bitrateKbps: Int?,
        bitDepth: Int = 16,
        sampleRateHz: Int = 44_100,
    ) = NerdStats.Snapshot(
        mimeType = "audio/flac",
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
        channels = 2,
        bitDepth = bitDepth,
    )

    @Test
    fun `real flac earns the badge`() {
        assertTrue(flac(bitrateKbps = 900).showsLosslessBadge)
    }

    @Test
    fun `a flac below 300kbps is treated as a fake lossless`() {
        val suspect = flac(bitrateKbps = 299)
        assertTrue(suspect.isLossless)
        assertTrue(suspect.isSuspectedFakeLossless)
        assertFalse(suspect.showsLosslessBadge)
    }

    @Test
    fun `an unmeasured bitrate is not evidence of a fake lossless`() {
        assertTrue(flac(bitrateKbps = null).showsLosslessBadge)
    }

    @Test
    fun `lossy codecs never earn the lossless badge`() {
        val opus = NerdStats.Snapshot(
            mimeType = "audio/opus",
            bitrateKbps = 160,
            sampleRateHz = 48_000,
            channels = 2,
        )
        assertFalse(opus.showsLosslessBadge)
    }
}
