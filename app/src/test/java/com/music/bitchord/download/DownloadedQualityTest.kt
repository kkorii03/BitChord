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
        assertFalse(DownloadedQuality.isAtBest("OPUS · 160 kbps"))
        assertEquals(
            DownloadQuality.LOSSLESS,
            DownloadedQuality.qualityForUpgrade("OPUS · 160 kbps", verified = null),
        )
    }

    @Test
    fun `a verified better rung enables the row and asks for that rung`() {
        assertFalse(DownloadedQuality.isAtBest("OPUS · 160 kbps"))
        assertEquals(
            DownloadQuality.LOSSLESS,
            DownloadedQuality.qualityForUpgrade("OPUS · 160 kbps", HqTier.LOSSLESS_16),
        )
    }

    /**
     * 11-07 'Titanium' read "이미 최고 음질" on the strength of its own rung having
     * been proved. A 16-bit FLAC is not the top, and an Opus file certainly is not.
     */
    @Test
    fun `only a top rung says it is already best`() {
        assertFalse(DownloadedQuality.isAtBest("FLAC"))
        assertFalse(DownloadedQuality.isAtBest("FLAC", HqTier.LOSSLESS_16))
        assertFalse(DownloadedQuality.isAtBest("FLAC", HqTier.HI_RES_24_44))
        assertFalse(DownloadedQuality.isAtBest("AAC · 320 kbps"))
        assertTrue(DownloadedQuality.isAtBest("FLAC", HqTier.HI_RES_24_88))
    }

    @Test
    fun `a lossy file is never best whatever rung its pair was proved at`() {
        assertFalse(DownloadedQuality.isAtBest("MP3 · 320 kbps", HqTier.HI_RES_24_88))
        assertFalse(DownloadedQuality.isAtBest("OPUS · 160 kbps", HqTier.DOLBY_ATMOS))
        assertEquals(HqTier.NORMAL, DownloadedQuality.tierOf("OPUS · 160 kbps", HqTier.HI_RES_24_88.name))
    }

    @Test
    fun `Dolby Atmos is already the top of the ladder`() {
        assertTrue(DownloadedQuality.isAtBest("DOLBY"))
    }
}
