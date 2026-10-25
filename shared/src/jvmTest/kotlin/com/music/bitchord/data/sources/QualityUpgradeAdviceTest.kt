package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityUpgradeAdviceTest {

    @Test
    fun `lossy track with a source above YouTube is worth searching`() {
        assertTrue(
            QualityUpgradeAdvice.shouldSearch(
                currentIsLossless = false,
                currentIsAtmos = false,
                hasHigherSource = true,
            ),
        )
    }

    @Test
    fun `YouTube only install treats its own stream as the ceiling`() {
        assertFalse(
            QualityUpgradeAdvice.shouldSearch(
                currentIsLossless = false,
                currentIsAtmos = false,
                hasHigherSource = false,
            ),
        )
        assertEquals(
            QualityUpgradeAdvice.Outcome.ALREADY_BEST,
            QualityUpgradeAdvice.outcome(
                found = false,
                currentIsLossless = false,
                currentIsAtmos = false,
                hasHigherSource = false,
            ),
        )
    }

    @Test
    fun `lossless and Atmos are never searched away from`() {
        assertFalse(QualityUpgradeAdvice.shouldSearch(true, false, true))
        assertFalse(QualityUpgradeAdvice.shouldSearch(false, true, true))
        assertEquals(
            QualityUpgradeAdvice.Outcome.ALREADY_BEST,
            QualityUpgradeAdvice.outcome(false, true, false, true),
        )
        assertEquals(
            QualityUpgradeAdvice.Outcome.ALREADY_BEST,
            QualityUpgradeAdvice.outcome(false, false, true, true),
        )
    }

    @Test
    fun `a search that finds nothing is distinct from being at the ceiling`() {
        assertEquals(
            QualityUpgradeAdvice.Outcome.NOTHING_BETTER,
            QualityUpgradeAdvice.outcome(false, false, false, true),
        )
    }

    @Test
    fun `a found stream wins regardless of the current ceiling`() {
        assertEquals(
            QualityUpgradeAdvice.Outcome.SWAPPED,
            QualityUpgradeAdvice.outcome(true, true, false, true),
        )
    }

    @Test
    fun `labels name the codec and only state bitrate for lossy streams`() {
        assertEquals("Opus 138kbps", QualityUpgradeAdvice.label(StreamFormat(codec = "opus", kbps = 138)))
        assertEquals("AAC 256kbps", QualityUpgradeAdvice.label(StreamFormat(codec = "aac", kbps = 256)))
        assertEquals("FLAC", QualityUpgradeAdvice.label(StreamFormat(codec = "flac", kbps = 1411)))
        assertEquals("Dolby Atmos", QualityUpgradeAdvice.label(StreamFormat(codec = "eac3-joc", kbps = 768)))
        assertEquals("", QualityUpgradeAdvice.label(null))
    }
}
