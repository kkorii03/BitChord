package com.music.bitchord.ui.player

import com.music.bitchord.data.NerdStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review FX2 C2a-1: tapping the artwork (or "고급 정보 표시" on) drew nothing,
 * because the measured-stream line had been deleted from the sleeve.
 */
class SleeveStreamLineTest {

    @Test
    fun `a 24-bit 96 kHz FLAC is described piece by piece`() {
        val parts = streamLineParts(
            NerdStats.Snapshot(
                mimeType = "audio/flac",
                bitrateKbps = 4608,
                sampleRateHz = 96_000,
                channels = 2,
                bitDepth = 24,
            ),
        )
        assertEquals(
            listOf(
                StreamLinePart.Plain("FLAC"),
                StreamLinePart.BitDepth(24),
                StreamLinePart.Plain("96.0 kHz"),
                StreamLinePart.Plain("4608 kbps"),
                StreamLinePart.Channels(2),
            ),
            parts,
        )
    }

    @Test
    fun `nothing known means no line`() {
        assertTrue(streamLineParts(null).isEmpty())
        assertTrue(
            streamLineParts(
                NerdStats.Snapshot(mimeType = null, bitrateKbps = null, sampleRateHz = null, channels = null),
            ).isEmpty(),
        )
    }

    @Test
    fun `a DSD rate is named in MHz`() {
        val parts = streamLineParts(
            NerdStats.Snapshot(mimeType = null, bitrateKbps = null, sampleRateHz = 2_822_400, channels = null),
        )
        assertEquals(listOf(StreamLinePart.Plain("2.8224 MHz")), parts)
    }

    @Test
    fun `a tap reveals the line with the switch off`() {
        assertTrue(nerdStatsVisible(revealed = true, alwaysShow = false))
    }
}
