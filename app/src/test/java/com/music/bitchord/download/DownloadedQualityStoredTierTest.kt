package com.music.bitchord.download

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A downloaded file's rung and the replacement row it earns.
 *
 * The badge says only what container was saved. The rung the download recorded
 * is the only witness to the depth and rate actually fetched, and it is what
 * stops a hi-res file from being fetched again as if it were plain FLAC — or a
 * hi-res verdict from being hidden behind yesterday's 16-bit file.
 */
class DownloadedQualityStoredTierTest {

    @Test
    fun `badges map onto the same ladder the stream decisions use`() {
        assertEquals(HqTier.DOLBY_ATMOS, DownloadedQuality.tierOf("DOLBY"))
        assertEquals(HqTier.LOSSLESS_16, DownloadedQuality.tierOf("FLAC"))
        assertEquals(HqTier.LOSSLESS_16, DownloadedQuality.tierOf("ALAC"))
        assertEquals(HqTier.HIGH_LOSSY, DownloadedQuality.tierOf("AAC · 320 kbps"))
        assertEquals(HqTier.NORMAL, DownloadedQuality.tierOf("AAC · 128 kbps"))
        assertEquals(HqTier.HIGH_LOSSY, DownloadedQuality.tierOf("OPUS · 256 kbps"))
        assertEquals(HqTier.NORMAL, DownloadedQuality.tierOf("OPUS · 160 kbps"))
        assertEquals(HqTier.HIGH_LOSSY, DownloadedQuality.tierOf("MP3 · 320 kbps"))
        assertEquals(HqTier.NORMAL, DownloadedQuality.tierOf(null))
        assertEquals(HqTier.NORMAL, DownloadedQuality.tierOf("  "))
    }

    @Test
    fun `the recorded rung wins over a badge that cannot state depth`() {
        assertEquals(
            HqTier.HI_RES_24_88,
            DownloadedQuality.tierOf("FLAC", HqTier.HI_RES_24_88.name),
        )
    }

    @Test
    fun `an unreadable recorded rung falls back to the badge`() {
        assertEquals(
            HqTier.LOSSLESS_16,
            DownloadedQuality.tierOf("FLAC", "not-a-tier"),
        )
    }

    @Test
    fun `a stored hi-res file is already best against a 16-bit verdict`() {
        assertTrue(
            DownloadedQuality.isAtBest(
                badge = "FLAC",
                verified = HqTier.LOSSLESS_16,
                stored = HqTier.HI_RES_24_88,
            ),
        )
    }

    @Test
    fun `a stored 16-bit file still offers a verified hi-res replacement`() {
        assertFalse(
            DownloadedQuality.isAtBest(
                badge = "FLAC",
                verified = HqTier.HI_RES_24_88,
                stored = HqTier.LOSSLESS_16,
            ),
        )
    }

    @Test
    fun `a stored rung equal to the verdict is best`() {
        assertTrue(
            DownloadedQuality.isAtBest(
                badge = "FLAC",
                verified = HqTier.HI_RES_24_88,
                stored = HqTier.HI_RES_24_88,
            ),
        )
    }

    @Test
    fun `a plain FLAC with no stored rung still offers hi-res`() {
        assertFalse(DownloadedQuality.isAtBest("FLAC", HqTier.HI_RES_24_88))
    }

    @Test
    fun `betterVerified only names a rung that beats the file`() {
        assertEquals(HqTier.HI_RES_24_88, DownloadedQuality.betterVerified("FLAC", HqTier.HI_RES_24_88))
        assertNull(DownloadedQuality.betterVerified("FLAC", HqTier.LOSSLESS_16))
        assertNull(DownloadedQuality.betterVerified("FLAC", null))
    }

    @Test
    fun `every tier asks for the download quality that can carry it`() {
        assertEquals(DownloadQuality.HIGH, DownloadedQuality.qualityFor(HqTier.DOLBY_ATMOS))
        assertEquals(DownloadQuality.LOSSLESS, DownloadedQuality.qualityFor(HqTier.HI_RES_24_88))
        assertEquals(DownloadQuality.LOSSLESS, DownloadedQuality.qualityFor(HqTier.HI_RES_24_44))
        assertEquals(DownloadQuality.LOSSLESS, DownloadedQuality.qualityFor(HqTier.LOSSLESS_16))
        assertEquals(DownloadQuality.HIGH, DownloadedQuality.qualityFor(HqTier.HIGH_LOSSY))
        assertEquals(DownloadQuality.STANDARD, DownloadedQuality.qualityFor(HqTier.NORMAL))
    }

    @Test
    fun `a badge is turned into the format a source can compare against`() {
        assertEquals("eac3-joc", DownloadedQuality.formatOf("DOLBY").codec)
        assertEquals("flac", DownloadedQuality.formatOf("FLAC").codec)
        assertEquals("alac", DownloadedQuality.formatOf("ALAC").codec)
        assertEquals("aac", DownloadedQuality.formatOf("AAC · 320 kbps").codec)
        assertEquals(320, DownloadedQuality.formatOf("AAC · 320 kbps").kbps)
        assertEquals("opus", DownloadedQuality.formatOf("OPUS · 160 kbps").codec)
        assertEquals("mp3", DownloadedQuality.formatOf("MP3 · 192 kbps").codec)
        assertNull(DownloadedQuality.formatOf(null).codec)
        assertNull(DownloadedQuality.formatOf("unknown").codec)
    }
}
