package com.music.bitchord.download

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which replacement row a downloaded file earns. */
class DownloadedQualityTest {

    @Test
    fun `an unverified download still offers the higher-quality replacement`() {
        assertFalse(DownloadedQuality.isAtBest("OPUS · 160 kbps", verified = null))
        assertEquals(
            DownloadQuality.LOSSLESS,
            DownloadedQuality.qualityForUpgrade("OPUS · 160 kbps", verified = null),
        )
    }

    @Test
    fun `a verified better rung enables the row and asks for that rung`() {
        assertFalse(DownloadedQuality.isAtBest("OPUS · 160 kbps", HqTier.LOSSLESS_16))
        assertEquals(
            DownloadQuality.LOSSLESS,
            DownloadedQuality.qualityForUpgrade("OPUS · 160 kbps", HqTier.LOSSLESS_16),
        )
    }

    @Test
    fun `a verified equal or lower rung says it is already best`() {
        assertTrue(DownloadedQuality.isAtBest("FLAC", HqTier.LOSSLESS_16))
        assertTrue(DownloadedQuality.isAtBest("OPUS · 160 kbps", HqTier.NORMAL))
    }

    @Test
    fun `Dolby Atmos is already the top of the ladder`() {
        assertTrue(DownloadedQuality.isAtBest("DOLBY", verified = null))
    }
}
