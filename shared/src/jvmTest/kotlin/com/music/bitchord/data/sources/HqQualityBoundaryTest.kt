package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundaries of the quality ladder: the thresholds where a copy changes rung,
 * the fake-lossless demotions, and the tie-breaks inside one rung.
 */
class HqQualityBoundaryTest {

    private fun format(
        codec: String? = null,
        kbps: Int? = null,
        rate: Int? = null,
        depth: Int? = null,
    ) = StreamFormat(codec = codec, kbps = kbps, sampleRateHz = rate, bitDepth = depth)

    @Test
    fun `the ranks are ordered lower is better and never collide`() {
        val ranks = HqTier.entries.map { it.rank }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.distinct(), ranks)
        assertEquals(
            listOf(
                HqTier.DOLBY_ATMOS,
                HqTier.HI_RES_24_88,
                HqTier.HI_RES_24_44,
                HqTier.LOSSLESS_16,
                HqTier.HIGH_LOSSY,
                HqTier.NORMAL,
            ),
            HqTier.entries,
        )
    }

    @Test
    fun `every lossless codec reaches a lossless rung`() {
        listOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff").forEach { codec ->
            assertEquals("codec $codec", HqTier.LOSSLESS_16, HqQuality.tierOf(format(codec)))
        }
    }

    @Test
    fun `a lossy codec never reaches lossless however high its rate`() {
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOf(format("opus", 192_000, 192_000, 24)))
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOf(format("aac", 320, 96_000, 24)))
        assertFalse(HqQuality.isLosslessTier(format("opus", 192_000, 192_000, 24)))
    }

    @Test
    fun `the lossy rung starts exactly at 256 kbps`() {
        assertEquals(HqTier.NORMAL, HqQuality.tierOf(format("aac", 255)))
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOf(format("aac", 256)))
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOf(format("aac", 320)))
    }

    @Test
    fun `a non-positive bitrate counts as unstated`() {
        assertEquals(HqTier.NORMAL, HqQuality.tierOf(format("aac", 0)))
        assertEquals(HqTier.NORMAL, HqQuality.tierOf(format("aac", -1)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", 0)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", -1)))
    }

    @Test
    fun `the fake-lossless bitrate boundary is exclusive at 300`() {
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOf(format("flac", 299)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", 300)))
        assertTrue(HqQuality.isFakeHighQuality(format("flac", 299)))
        assertFalse(HqQuality.isFakeHighQuality(format("flac", 300)))
    }

    @Test
    fun `a lossless claim under 160 kbps falls all the way to normal`() {
        assertEquals(HqTier.NORMAL, HqQuality.tierOf(format("flac", 159)))
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOf(format("flac", 160)))
        assertEquals(HqTier.NORMAL, HqQuality.tierOf(format("flac", 64)))
    }

    @Test
    fun `the 24-bit demotion boundary is exactly the 16-bit cost`() {
        // 96 kHz label at a 16-bit bitrate: one rung down.
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOf(format("flac", 1_411, 96_000, 24)))
        assertEquals(HqTier.HI_RES_24_88, HqQuality.tierOf(format("flac", 1_412, 96_000, 24)))
        // 48 kHz label at the same rate: all the way to ordinary lossless.
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", 1_411, 48_000, 24)))
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOf(format("flac", 1_412, 48_000, 24)))
        assertTrue(HqQuality.isFakeHighQuality(format("flac", 1_411, 96_000, 24)))
        assertFalse(HqQuality.isFakeHighQuality(format("flac", 1_412, 96_000, 24)))
    }

    @Test
    fun `the hi-res rate boundary is 88 point 2 kHz`() {
        assertEquals(HqTier.HI_RES_24_88, HqQuality.tierOf(format("flac", null, 88_200, 24)))
        assertEquals(HqTier.HI_RES_24_88, HqQuality.tierOf(format("flac", null, 96_000, 24)))
        assertEquals(HqTier.HI_RES_24_88, HqQuality.tierOf(format("flac", null, 192_000, 24)))
        // Below the hi-res threshold the rate is not one of the base rates, so
        // the file can only claim ordinary lossless.
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, 88_199, 24)))
    }

    @Test
    fun `the base-rate window is 44 point 1 to 48 kHz`() {
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, 44_100, 16)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, 48_000, 16)))
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOf(format("flac", null, 44_100, 24)))
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOf(format("flac", null, 48_000, 24)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, 32_000, 16)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, 48_001, 24)))
    }

    @Test
    fun `a partly stated lossless file cannot claim hi-res`() {
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac")))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, null, 24)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, 96_000, null)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", 4_608, null, 24)))
    }

    @Test
    fun `a low bit depth still sits on the ordinary lossless rung`() {
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, 44_100, 8)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, 44_100, 15)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", null, 44_100, 16)))
    }

    @Test
    fun `an unstated format is normal and loses to anything stated`() {
        assertEquals(HqTier.NORMAL, HqQuality.tierOf(null))
        assertFalse(HqQuality.isLosslessTier(null))
        assertFalse(HqQuality.isFakeHighQuality(null))
        assertFalse(HqQuality.isBetter(null, null))
        assertFalse(HqQuality.isBetter(null, format("aac", 128)))
        assertTrue(HqQuality.isBetter(format("aac", 128), null))
    }

    @Test
    fun `fake high quality is only claimed by lossless rows`() {
        assertFalse(HqQuality.isFakeHighQuality(format("aac", 64)))
        assertFalse(HqQuality.isFakeHighQuality(format("opus", 320)))
        assertFalse(HqQuality.isFakeHighQuality(format("flac", null, 96_000, 24)))
        assertTrue(HqQuality.isFakeHighQuality(format("flac", 299, 96_000, 24)))
        // Without a stated depth, 300 kbps is no longer proof of a fake; a
        // 24-bit label at that rate still is.
        assertFalse(HqQuality.isFakeHighQuality(format("flac", 300)))
        assertTrue(HqQuality.isFakeHighQuality(format("flac", 300, 96_000, 24)))
        assertTrue(HqQuality.isFakeHighQuality(format("flac", 1_411, 96_000, 24)))
        assertFalse(HqQuality.isFakeHighQuality(format("flac", 1_412, 96_000, 24)))
        assertFalse(HqQuality.isFakeHighQuality(format("flac", 1_411, 44_100, 16)))
    }

    @Test
    fun `a demoted fake hi-res still counts as lossless`() {
        assertTrue(HqQuality.isLosslessTier(format("flac", 1_411, 96_000, 24)))
        assertTrue(HqQuality.isLosslessTier(format("flac", 900, 48_000, 24)))
        assertFalse(HqQuality.isLosslessTier(format("flac", 299, 96_000, 24)))
    }

    @Test
    fun `the ladder beats a higher bitrate on the rung below`() {
        val demotedHiRes = format("flac", 900, 96_000, 24)
        val cd = format("flac", 1_411, 44_100, 16)
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOf(demotedHiRes))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(cd))
        assertTrue(HqQuality.isBetter(demotedHiRes, cd))
        assertFalse(HqQuality.isBetter(cd, demotedHiRes))
    }

    @Test
    fun `within a rung the stated bitrate decides`() {
        assertTrue(HqQuality.isBetter(format("aac", 320), format("aac", 256)))
        assertFalse(HqQuality.isBetter(format("aac", 256), format("aac", 320)))
        // Missing means zero, not a win.
        assertFalse(HqQuality.isBetter(format("aac", null), format("aac", 256)))
        assertTrue(HqQuality.isBetter(format("aac", 256), format("aac", null)))
        assertFalse(HqQuality.isBetter(format("aac", 0), format("aac", null)))
    }

    @Test
    fun `equal bitrate falls through depth then sample rate`() {
        val base = format("flac", 2_304, 44_100, 24)
        assertTrue(HqQuality.isBetter(format("flac", 2_304, 44_100, 24), format("flac", 2_304, 44_100, 16)))
        assertFalse(HqQuality.isBetter(format("flac", 2_304, 44_100, 16), format("flac", 2_304, 44_100, 24)))
        assertTrue(HqQuality.isBetter(format("flac", 2_304, 48_000, 24), base))
        assertFalse(HqQuality.isBetter(format("flac", 2_304, 48_000, null), base))
        assertFalse(HqQuality.isBetter(base, base))
    }

    @Test
    fun `an equal copy never beats the one already chosen`() {
        val aac = format("aac", 320, 44_100, 16)
        val same = format("aac", 320, 44_100, 16)
        assertFalse(HqQuality.isBetter(aac, same))
        assertFalse(HqQuality.isBetter(same, aac))
    }

    @Test
    fun `couldWin is the race rule around isBetter`() {
        val aac = format("aac", 320)
        val flac = format("flac", 1_411, 44_100, 16)
        val opus = format("opus", 160)
        assertTrue(HqQuality.couldWin(opus, null, candidateEarlier = false))
        assertFalse(HqQuality.couldWin(null, aac, candidateEarlier = true))
        assertTrue(HqQuality.couldWin(flac, aac, candidateEarlier = false))
        assertTrue(HqQuality.couldWin(flac, aac, candidateEarlier = true))
        assertFalse(HqQuality.couldWin(opus, aac, candidateEarlier = true))
        assertTrue(HqQuality.couldWin(aac, format("aac", 320), candidateEarlier = true))
        assertFalse(HqQuality.couldWin(aac, format("aac", 320), candidateEarlier = false))
    }

    @Test
    fun `declared search labels map onto the same rungs`() {
        assertEquals(HqTier.DOLBY_ATMOS, HqQuality.tierOfDeclared("DOLBY", hiRes = false))
        assertEquals(HqTier.DOLBY_ATMOS, HqQuality.tierOfDeclared("dolby", hiRes = true))
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOfDeclared("HI-RES", hiRes = false))
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOfDeclared("HIRES", hiRes = false))
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOfDeclared("LOSSLESS", hiRes = true))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOfDeclared("LOSSLESS", hiRes = false))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOfDeclared("FLAC", hiRes = false))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOfDeclared("ALAC", hiRes = false))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOfDeclared("WAV", hiRes = false))
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOfDeclared("HIGH", hiRes = false))
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOfDeclared("320", hiRes = true))
        assertEquals(HqTier.NORMAL, HqQuality.tierOfDeclared("LOW", hiRes = true))
        assertEquals(HqTier.NORMAL, HqQuality.tierOfDeclared("", hiRes = true))
        assertEquals(HqTier.NORMAL, HqQuality.tierOfDeclared(null, hiRes = true))
    }

    @Test
    fun `labels state the rung and only a positive bitrate`() {
        assertEquals(HqTier.NORMAL.label, HqQuality.label(null))
        assertEquals(HqTier.NORMAL.label, HqQuality.label(format("aac", 0)))
        assertEquals(HqTier.NORMAL.label, HqQuality.label(format("aac", -1)))
        assertEquals(
            "${HqTier.LOSSLESS_16.label} 1411kbps",
            HqQuality.label(format("flac", 1_411, 44_100, 16)),
        )
        assertEquals(
            "${HqTier.DOLBY_ATMOS.label} 768kbps",
            HqQuality.label(format("eac3-joc", 768)),
        )
    }
}
