package com.music.bitchord.data.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which candidates the automatic second look may spend a sound comparison on.
 *
 * Both things that path does with a yes — the mid-song swap and the next-play
 * prefetch — refuse anything that is not lossless, so a 320kbps candidate was
 * decoded for 34.8 s (`verify=34756ms`, 11-03) with the upgrade badge up for
 * all of it, and was then dropped with nothing better ever arriving.
 */
class HqProofWorthTest {

    @Test
    fun `a lossy candidate is not proved by the automatic path`() {
        assertFalse(HqQuality.worthProving(StreamFormat(codec = "aac", kbps = 320)))
        assertFalse(HqQuality.worthProving(StreamFormat(codec = "aac", kbps = 320, kbpsInferred = true)))
        assertFalse(HqQuality.worthProving(StreamFormat(codec = "dolby-atmos")))
        assertFalse(HqQuality.worthProving(null))
        assertTrue(HqQuality.worthProving(StreamFormat(codec = "flac")))
        assertTrue(
            HqQuality.worthProving(
                StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 96_000),
            ),
        )
    }
}
