package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HqTierTest {

    private fun format(
        codec: String?,
        kbps: Int? = null,
        rate: Int? = null,
        depth: Int? = null,
    ) = StreamFormat(codec = codec, kbps = kbps, sampleRateHz = rate, bitDepth = depth)

    @Test
    fun `the five rungs are ordered from hi-res down to normal`() {
        assertEquals(HqTier.HI_RES_24_88, HqQuality.tierOf(format("flac", 4608, 96_000, 24)))
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOf(format("flac", 2304, 48_000, 24)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", 1411, 44_100, 16)))
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOf(format("aac", 320)))
        assertEquals(HqTier.NORMAL, HqQuality.tierOf(format("opus", 160)))
    }

    @Test
    fun `dolby atmos keeps its existing place ahead of lossless`() {
        assertEquals(HqTier.DOLBY_ATMOS, HqQuality.tierOf(format("eac3-joc", 768)))
        assertTrue(HqQuality.isBetter(format("eac3-joc", 768), format("flac", 4608, 96_000, 24)))
    }

    @Test
    fun `a lossless file that does not state depth or rate is ordinary lossless`() {
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac")))
    }

    @Test
    fun `a 24-bit label at 16-bit bitrate is demoted one rung`() {
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOf(format("flac", 900, 96_000, 24)))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format("flac", 900, 48_000, 24)))
        assertTrue(HqQuality.isFakeHighQuality(format("flac", 900, 96_000, 24)))
    }

    @Test
    fun `a flac below the lossy floor is a fake and is demoted`() {
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOf(format("flac", 250)))
        assertEquals(HqTier.NORMAL, HqQuality.tierOf(format("flac", 120)))
        assertTrue(HqQuality.isFakeHighQuality(format("flac", 250)))
        assertFalse(HqQuality.isFakeHighQuality(format("flac", 1411, 44_100, 16)))
    }

    @Test
    fun `the ladder decides before the bitrate`() {
        val hiRes = format("flac", 2304, 48_000, 24)
        val cd = format("flac", 1411, 44_100, 16)
        assertTrue(HqQuality.isBetter(hiRes, cd))
        assertFalse(HqQuality.isBetter(cd, hiRes))
    }

    @Test
    fun `same rung compares the stated bitrate`() {
        assertTrue(HqQuality.isBetter(format("aac", 320), format("aac", 256)))
        assertFalse(HqQuality.isBetter(format("aac", 256), format("aac", 320)))
    }

    @Test
    fun `same rung and bitrate compares depth then rate`() {
        assertTrue(
            HqQuality.isBetter(
                format("flac", 2304, 48_000, 24),
                format("flac", 2304, 44_100, 24),
            ),
        )
    }

    @Test
    fun `same or better keeps the rung and refuses a downgrade`() {
        val cd = format("flac", 1411, 44_100, 16)
        val hiRes = format("flac", 2304, 48_000, 24)
        val aac = format("aac", 320)
        val atmos = format("eac3-joc", 768)
        assertTrue(HqQuality.isSameOrBetter(cd, cd))
        assertTrue(HqQuality.isSameOrBetter(hiRes, cd))
        assertFalse(HqQuality.isSameOrBetter(aac, cd))
        assertFalse(HqQuality.isSameOrBetter(cd, hiRes))
        // An immersive mix stays ahead of every stereo rung.
        assertTrue(HqQuality.isSameOrBetter(atmos, cd))
        assertFalse(HqQuality.isSameOrBetter(cd, atmos))
        // An unknown floor is cleared only by Atmos or lossless.
        assertTrue(HqQuality.isSameOrBetter(cd, null))
        assertTrue(HqQuality.isSameOrBetter(atmos, null))
        assertFalse(HqQuality.isSameOrBetter(aac, null))
        assertFalse(HqQuality.isSameOrBetter(null, cd))
    }

    @Test
    fun `an unstated format is the normal rung and beats nothing`() {
        assertEquals(HqTier.NORMAL, HqQuality.tierOf(null))
        assertFalse(HqQuality.isBetter(null, format("opus", 160)))
        assertTrue(HqQuality.isBetter(format("opus", 160), null))
    }

    @Test
    fun `lossless tiers exclude Atmos and a fake flac`() {
        assertTrue(HqQuality.isLosslessTier(format("flac", 1411, 44_100, 16)))
        assertTrue(HqQuality.isLosslessTier(format("flac", 4608, 96_000, 24)))
        assertFalse(HqQuality.isLosslessTier(format("flac", 250)))
        assertFalse(HqQuality.isLosslessTier(format("eac3-joc", 768)))
        assertFalse(HqQuality.isLosslessTier(format("aac", 320)))
    }

    @Test
    fun `declared search rows use the same ladder`() {
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOfDeclared("HI-RES", hiRes = false))
        assertEquals(HqTier.HI_RES_24_44, HqQuality.tierOfDeclared("LOSSLESS", hiRes = true))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOfDeclared("FLAC", hiRes = false))
        assertEquals(HqTier.HIGH_LOSSY, HqQuality.tierOfDeclared("HIGH", hiRes = false))
        assertEquals(HqTier.NORMAL, HqQuality.tierOfDeclared(null, hiRes = false))
    }

    @Test
    fun `a candidate that cannot win is not worth proving`() {
        val opus = format("opus", 160)
        val aac = format("aac", 320)
        val flac = format("flac", 1411, 44_100, 16)
        // Nothing found yet: anything may win.
        assertTrue(HqQuality.couldWin(opus, null, candidateEarlier = false))
        // No candidate is never a winner.
        assertFalse(HqQuality.couldWin(null, null, candidateEarlier = true))
        assertFalse(HqQuality.couldWin(null, aac, candidateEarlier = true))
        // A better rendition wins whatever the source order.
        assertTrue(HqQuality.couldWin(flac, aac, candidateEarlier = false))
        assertTrue(HqQuality.couldWin(flac, aac, candidateEarlier = true))
        // A worse one cannot, even when its source is ranked first.
        assertFalse(HqQuality.couldWin(opus, aac, candidateEarlier = true))
        // An equal rendition wins only by being ranked earlier.
        assertTrue(HqQuality.couldWin(aac, format("aac", 320), candidateEarlier = true))
        assertFalse(HqQuality.couldWin(aac, format("aac", 320), candidateEarlier = false))
    }
}
