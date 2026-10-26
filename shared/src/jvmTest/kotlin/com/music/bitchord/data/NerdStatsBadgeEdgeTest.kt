package com.music.bitchord.data

import com.music.bitchord.data.sources.StreamFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lossless badge and the quality tiers around it are read off measurements,
 * never off what a source claimed. These are the boundaries that decide what
 * the listener is told the stream is.
 */
class NerdStatsBadgeEdgeTest {

    private fun snapshot(
        mime: String? = "audio/flac",
        kbps: Int? = 900,
        rate: Int? = 44_100,
        depth: Int? = 16,
        claimed: StreamFormat? = null,
    ) = NerdStats.Snapshot(
        mimeType = mime,
        bitrateKbps = kbps,
        sampleRateHz = rate,
        channels = 2,
        bitDepth = depth,
        claimed = claimed,
    )

    @Test
    fun fakeLosslessBoundaryIsThreeHundredKbps() {
        assertTrue(snapshot(kbps = 0).isSuspectedFakeLossless)
        assertTrue(snapshot(kbps = 299).isSuspectedFakeLossless)
        assertFalse(snapshot(kbps = 300).isSuspectedFakeLossless)
        assertFalse(snapshot(kbps = 301).isSuspectedFakeLossless)
        assertFalse(snapshot(kbps = null).isSuspectedFakeLossless)
    }

    @Test
    fun aClaimAloneNeverLightsTheBadge() {
        val claimedLossless = snapshot(
            mime = "audio/opus",
            kbps = 128,
            rate = 48_000,
            depth = null,
            claimed = StreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24),
        )

        assertFalse(claimedLossless.isLossless)
        assertFalse(claimedLossless.showsLosslessBadge)
        assertFalse(claimedLossless.isHiRes)
    }

    @Test
    fun losslessMimeFamiliesAreRecognised() {
        assertTrue(NerdStats.isLosslessMime("audio/flac"))
        assertTrue(NerdStats.isLosslessMime("audio/x-flac"))
        assertTrue(NerdStats.isLosslessMime("audio/alac"))
        assertTrue(NerdStats.isLosslessMime("audio/raw"))
        assertTrue(NerdStats.isLosslessMime("audio/dsd64"))
        assertTrue(NerdStats.isLosslessMime("audio/dsd128"))
        assertFalse(NerdStats.isLosslessMime("audio/opus"))
        assertFalse(NerdStats.isLosslessMime("audio/eac3-joc"))
        assertFalse(NerdStats.isLosslessMime(null))
    }

    @Test
    fun dolbyAtmosIsImmersiveButNotLossless() {
        val atmos = snapshot(mime = "audio/eac3-joc", kbps = 768, rate = 48_000, depth = null)

        assertTrue(atmos.isDolbyAtmos)
        assertFalse(atmos.isLossless)
        assertFalse(atmos.showsLosslessBadge)
        assertTrue(NerdStats.isDolbyAtmosMime("audio/eac3"))
        assertFalse(NerdStats.isDolbyAtmosMime("audio/ec-3"))
        assertFalse(NerdStats.isDolbyAtmosMime(null))
    }

    @Test
    fun hiResNeedsMoreThanCd() {
        assertFalse(snapshot(rate = 44_100, depth = 16).isHiRes)
        assertFalse(snapshot(rate = 48_000, depth = 16).isHiRes)
        assertTrue(snapshot(rate = 48_001, depth = 16).isHiRes)
        assertTrue(snapshot(rate = 44_100, depth = 17).isHiRes)
        assertTrue(snapshot(rate = 96_000, depth = 24).isHiRes)
        assertFalse(snapshot(rate = null, depth = null).isHiRes)
    }

    @Test
    fun lossyTiersUseMeasuredBitrateOnly() {
        val lowEdge = snapshot(mime = "audio/opus", kbps = 80, rate = 48_000, depth = null)
        assertTrue(lowEdge.isLowQuality)
        assertFalse(lowEdge.isMediumQuality)

        val mediumEdge = snapshot(mime = "audio/opus", kbps = 81, rate = 48_000, depth = null)
        assertFalse(mediumEdge.isLowQuality)
        assertTrue(mediumEdge.isMediumQuality)

        val mediumTop = snapshot(mime = "audio/opus", kbps = 255, rate = 48_000, depth = null)
        assertTrue(mediumTop.isMediumQuality)
        assertFalse(mediumTop.isHiQuality)

        val highEdge = snapshot(mime = "audio/opus", kbps = 256, rate = 48_000, depth = null)
        assertFalse(highEdge.isMediumQuality)
        assertTrue(highEdge.isHiQuality)

        val unmeasured = snapshot(mime = "audio/opus", kbps = null, rate = 48_000, depth = null)
        assertFalse(unmeasured.isLowQuality)
        assertFalse(unmeasured.isMediumQuality)
        assertFalse(unmeasured.isHiQuality)

        val lossless = snapshot(kbps = 1411, rate = 44_100, depth = 16)
        assertFalse(lossless.isLowQuality)
        assertFalse(lossless.isMediumQuality)
        assertFalse(lossless.isHiQuality)
    }

    @Test
    fun downgradedNeedsBothSidesKnown() {
        val claimed = StreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24)

        assertTrue(snapshot(rate = 48_000, depth = 24, claimed = claimed).downgraded)
        assertTrue(snapshot(rate = 96_000, depth = 16, claimed = claimed).downgraded)
        assertFalse(snapshot(rate = 96_000, depth = 24, claimed = claimed).downgraded)
        assertFalse(snapshot(rate = null, depth = 24, claimed = claimed).downgraded)
        assertTrue(snapshot(rate = 48_000, depth = null, claimed = claimed).downgraded)
        assertFalse(snapshot(rate = 96_000, depth = null, claimed = claimed).downgraded)
        assertFalse(snapshot(rate = 48_000, depth = 24, claimed = null).downgraded)
    }

    @Test
    fun megahertzLabelsOnlyAppearAtMegasampleRates() {
        assertEquals("2.8224 MHz", NerdStats.megahertzLabel(2_822_400))
        assertEquals("5.6448 MHz", NerdStats.megahertzLabel(5_644_800))
        assertEquals("11.2896 MHz", NerdStats.megahertzLabel(11_289_600))
        assertEquals("1 MHz", NerdStats.megahertzLabel(1_000_000))
        assertNull(NerdStats.megahertzLabel(999_999))
        assertNull(NerdStats.megahertzLabel(44_100))
    }

    @Test
    fun codecLabelsCoverEveryFamily() {
        assertEquals("FLAC", NerdStats.codecLabel("audio/flac"))
        assertEquals("Opus", NerdStats.codecLabel("audio/opus"))
        assertEquals("AAC", NerdStats.codecLabel("audio/mp4a-latm"))
        assertEquals("AAC", NerdStats.codecLabel("audio/aac"))
        assertEquals("MP3", NerdStats.codecLabel("audio/mpeg"))
        assertEquals("MP3", NerdStats.codecLabel("audio/mp3"))
        assertEquals("ALAC", NerdStats.codecLabel("audio/alac"))
        assertEquals("PCM", NerdStats.codecLabel("audio/raw"))
        assertEquals("DSD64", NerdStats.codecLabel("audio/dsd64"))
        assertEquals("Vorbis", NerdStats.codecLabel("audio/vorbis"))
        assertEquals("E-AC-3 JOC", NerdStats.codecLabel("audio/eac3-joc"))
        assertEquals("E-AC-3", NerdStats.codecLabel("audio/eac3"))
        assertEquals("XYZ", NerdStats.codecLabel("audio/xyz"))
        assertEquals("ABC", NerdStats.codecLabel("abc"))
        assertNull(NerdStats.codecLabel(null))
    }

    @Test
    fun rawPcmFamiliesAreRecognised() {
        assertTrue(NerdStats.isRawPcm("audio/raw"))
        assertTrue(NerdStats.isRawPcm("audio/wav"))
        assertTrue(NerdStats.isRawPcm("audio/x-wav"))
        assertTrue(NerdStats.isRawPcm("audio/WAV"))
        assertFalse(NerdStats.isRawPcm("audio/wave"))
        assertFalse(NerdStats.isRawPcm("audio/flac"))
        assertFalse(NerdStats.isRawPcm(null))
    }
}
