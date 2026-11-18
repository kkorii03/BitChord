package com.music.bitchord.data.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KO] HQ-DOWN, 11-18 16:31 Bones: a measured 24-bit/48 kHz copy must not give
 * way to a 16/44.1 one, nor to a "LOSSLESS" row that has not stated its depth
 * and rate (which the rung ladder places on, or promotes from, LOSSLESS_16).
 */
class HqDowngradeTest {

    private val hiRes = StreamFormat(codec = "flac", sampleRateHz = 48_000, bitDepth = 24)
    private val cd = StreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16)
    private val unmeasured = StreamFormat(codec = "flac")

    @Test
    fun `a measured lower depth or rate is a downgrade`() {
        assertTrue(HqQuality.isDowngradeFrom(hiRes, cd))
        assertTrue(HqQuality.isDowngradeFrom(hiRes, StreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 24)))
    }

    @Test
    fun `an unmeasured lossless row does not beat a measured hi-res file`() {
        assertTrue(HqQuality.isDowngradeFrom(hiRes, unmeasured))
    }

    @Test
    fun `lossy against lossless is a downgrade`() {
        assertTrue(HqQuality.isDowngradeFrom(cd, StreamFormat(codec = "aac", kbps = 320)))
    }

    @Test
    fun `an upgrade or an unknown current copy is never refused`() {
        assertFalse(HqQuality.isDowngradeFrom(cd, StreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24)))
        assertFalse(HqQuality.isDowngradeFrom(null, cd))
        assertFalse(HqQuality.isDowngradeFrom(cd, null))
        assertFalse(HqQuality.isDowngradeFrom(StreamFormat(codec = "opus", kbps = 160), cd))
        // A 16-bit file has nothing to protect from an unmeasured lossless row.
        assertFalse(HqQuality.isDowngradeFrom(cd, unmeasured))
        assertFalse(HqQuality.isDowngradeFrom(hiRes, hiRes))
    }

    @Test
    fun `atmos keeps its own rule`() {
        val atmos = StreamFormat(codec = "eac3-joc")
        assertFalse(HqQuality.isDowngradeFrom(hiRes, atmos))
        assertFalse(HqQuality.isDowngradeFrom(atmos, cd))
    }
}
