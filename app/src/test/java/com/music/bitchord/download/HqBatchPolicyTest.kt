package com.music.bitchord.download

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which batch count a download falls into, and whether a new file may replace it. */
class HqBatchPolicyTest {

    @Test
    fun `a lossy file with a source above YouTube is available`() {
        assertEquals(
            HqBatchPolicy.Category.AVAILABLE,
            HqBatchPolicy.category("OPUS · 160 kbps", verified = null, hasHigherSource = true),
        )
        assertEquals(
            DownloadQuality.LOSSLESS,
            HqBatchPolicy.qualityFor("OPUS · 160 kbps", verified = null, hasHigherSource = true),
        )
    }

    @Test
    fun `a lossy file with nothing above YouTube has no higher copy`() {
        assertEquals(
            HqBatchPolicy.Category.NONE,
            HqBatchPolicy.category("OPUS · 160 kbps", verified = null, hasHigherSource = false),
        )
        assertNull(HqBatchPolicy.qualityFor("OPUS · 160 kbps", verified = null, hasHigherSource = false))
    }

    @Test
    fun `a lossless file is already high quality when nothing above YouTube exists`() {
        assertEquals(
            HqBatchPolicy.Category.ALREADY_BEST,
            HqBatchPolicy.category("FLAC", verified = null, hasHigherSource = false),
        )
    }

    @Test
    fun `a lossless file can still be offered a source's higher rung`() {
        assertEquals(
            HqBatchPolicy.Category.AVAILABLE,
            HqBatchPolicy.category("FLAC", verified = null, hasHigherSource = true),
        )
    }

    @Test
    fun `a verified better rung is available at the lossless rung`() {
        assertEquals(
            HqBatchPolicy.Category.AVAILABLE,
            HqBatchPolicy.category("OPUS · 160 kbps", HqTier.LOSSLESS_16, hasHigherSource = true),
        )
        assertEquals(
            DownloadQuality.LOSSLESS,
            HqBatchPolicy.qualityFor("OPUS · 160 kbps", HqTier.LOSSLESS_16, hasHigherSource = true),
        )
    }

    @Test
    fun `a verified better rung survives the source list being disabled`() {
        assertEquals(
            HqBatchPolicy.Category.AVAILABLE,
            HqBatchPolicy.category("OPUS · 160 kbps", HqTier.HIGH_LOSSY, hasHigherSource = false),
        )
    }

    @Test
    fun `a verified equal rung is already best`() {
        assertEquals(
            HqBatchPolicy.Category.ALREADY_BEST,
            HqBatchPolicy.category("AAC · 256 kbps", HqTier.HIGH_LOSSY, hasHigherSource = true),
        )
    }

    @Test
    fun `Dolby Atmos is always already best`() {
        assertEquals(
            HqBatchPolicy.Category.ALREADY_BEST,
            HqBatchPolicy.category("DOLBY", verified = null, hasHigherSource = true),
        )
    }

    @Test
    fun `a decodable file of the catalogue length may replace`() {
        assertEquals(
            HqBatchPolicy.Replacement.ACCEPT,
            HqBatchPolicy.replacement(newDurationMs = 200_000, catalogDurationMs = 200_000),
        )
    }

    @Test
    fun `a file that cannot be read never replaces`() {
        assertEquals(
            HqBatchPolicy.Replacement.UNREADABLE,
            HqBatchPolicy.replacement(newDurationMs = null, catalogDurationMs = 200_000),
        )
        assertEquals(
            HqBatchPolicy.Replacement.UNREADABLE,
            HqBatchPolicy.replacement(newDurationMs = 0, catalogDurationMs = 200_000),
        )
    }

    @Test
    fun `the music video's longer sound never replaces the song`() {
        assertEquals(
            HqBatchPolicy.Replacement.TOO_LONG,
            HqBatchPolicy.replacement(newDurationMs = 202_501, catalogDurationMs = 200_000),
        )
        assertEquals(
            HqBatchPolicy.Replacement.ACCEPT,
            HqBatchPolicy.replacement(newDurationMs = 202_500, catalogDurationMs = 200_000),
        )
    }

    @Test
    fun `a truncated transfer never replaces`() {
        assertEquals(
            HqBatchPolicy.Replacement.TOO_SHORT,
            HqBatchPolicy.replacement(newDurationMs = 149_999, catalogDurationMs = 200_000),
        )
        assertEquals(
            HqBatchPolicy.Replacement.ACCEPT,
            HqBatchPolicy.replacement(newDurationMs = 150_000, catalogDurationMs = 200_000),
        )
    }

    @Test
    fun `without a catalogue length only decodability is required`() {
        assertEquals(
            HqBatchPolicy.Replacement.ACCEPT,
            HqBatchPolicy.replacement(newDurationMs = 200_000, catalogDurationMs = null),
        )
    }
}
