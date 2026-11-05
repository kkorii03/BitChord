package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phone 11-04, ILLIT "IYKYK": every candidate the addon offered was refused for
 * its own runtime (`claims FLAC 24-bit 44.1 kHz of 234s but the file runs
 * 162s`), and the track played wrong audio anyway — from a rendition cached by
 * an earlier play, which is never resolved and so never probed. These are the
 * same two questions asked of the bytes on disk.
 */
class CachedRenditionBarTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private val flacHead = ascii("fLaC\u0000\u0000\u0000\u0022")
    private val mp4Head = ascii("\u0000\u0000\u0000\u0020ftypM4A ")
    private val webmHead = ByteArray(12).also {
        it[0] = 0x1A; it[1] = 0x45; it[2] = 0xDF.toByte(); it[3] = 0xA3.toByte()
    }

    @Test
    fun `a cached copy whose runtime is not the row's is refused`() {
        // The catalogue says 234s; the bytes run 162s.
        assertEquals(
            CachedRenditionBar.Refusal.DURATION,
            CachedRenditionBar.refusal(
                claimsLossless = true,
                head = flacHead,
                rowSeconds = 234,
                renditionSeconds = 162,
            ),
        )
    }

    @Test
    fun `a cached copy of the recording is kept`() {
        // Whole seconds from a decoder, so a second either way is the same file.
        assertNull(
            CachedRenditionBar.refusal(
                claimsLossless = true,
                head = flacHead,
                rowSeconds = 234,
                renditionSeconds = 233,
            ),
        )
        assertNull(
            CachedRenditionBar.refusal(
                claimsLossless = false,
                head = webmHead,
                rowSeconds = 234,
                renditionSeconds = 234,
            ),
        )
    }

    @Test
    fun `a lossless claim over lossy bytes is refused whatever it runs`() {
        assertEquals(
            CachedRenditionBar.Refusal.LOSSY_BYTES,
            CachedRenditionBar.refusal(
                claimsLossless = true,
                head = mp4Head,
                rowSeconds = 234,
                renditionSeconds = 234,
            ),
        )
        // A copy nobody tagged as lossless has made no claim to keep.
        assertNull(
            CachedRenditionBar.refusal(
                claimsLossless = false,
                head = mp4Head,
                rowSeconds = 234,
                renditionSeconds = 234,
            ),
        )
    }

    @Test
    fun `what cannot be read or measured is not evidence`() {
        // An unreadable head says nothing about the container, so that half is
        // left unanswered — the runtime is still the row's or it is not.
        assertEquals(
            CachedRenditionBar.Refusal.DURATION,
            CachedRenditionBar.refusal(true, head = null, rowSeconds = 234, renditionSeconds = 162),
        )
        // A row with no runtime, or bytes no decoder has measured, leaves the
        // runtime half unanswered. Unanswered is not "fine".
        assertNull(
            CachedRenditionBar.refusal(true, head = flacHead, rowSeconds = null, renditionSeconds = 162),
        )
        assertNull(
            CachedRenditionBar.refusal(true, head = flacHead, rowSeconds = 234, renditionSeconds = null),
        )
    }

    @Test
    fun `a refused candidate takes the copy an addon filled, and nothing else`() {
        val keys = CachedRenditionBar.invalidatedKeys("qh7F1sFkOaY")
        // The substitute bucket is where a file played from an addon was cached.
        assertEquals(listOf("qh7F1sFkOaY#alt", "qh7F1sFkOaY#alt-noatmos"), keys)
        // The YouTube entry and a proved upgrade are different files nobody refused.
        assertFalse(keys.any { it == "qh7F1sFkOaY" || it.contains("#hifi") })
    }
}
