package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone 11-04: two tracks played audio that was not the song — a phone-call
 * recording under ILLIT's "It's Me", then another under "Almond Chocolate".
 * Both rows were credited, titled and timed exactly right; the bytes handed
 * over were 22.1 kHz / 44.1 kHz AAC while the row said "FLAC · 16-bit · 44.1
 * kHz". These are the checks that look at the bytes instead of the row.
 */
class StreamContainerSniffTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun `an MP4 under a FLAC claim is a lie`() {
        val head = bytes(0, 0, 0, 0x20) + ascii("ftypM4A ")
        assertTrue(StreamContainerSniff.looksLossy(head))
    }

    @Test
    fun `a real FLAC header is not`() {
        assertFalse(StreamContainerSniff.looksLossy(ascii("fLaC\u0000\u0000\u0000\u0022")))
    }

    @Test
    fun `a WAV header is not`() {
        assertFalse(StreamContainerSniff.looksLossy(ascii("RIFF\u0000\u0000\u0000\u0000WAVE")))
    }

    @Test
    fun `an ID3 tag and an MPEG frame are lies`() {
        assertTrue(StreamContainerSniff.looksLossy(ascii("ID3\u0004\u0000\u0000")))
        assertTrue(StreamContainerSniff.looksLossy(bytes(0xFF, 0xFB, 0x90, 0x00)))
    }

    @Test
    fun `an ADTS AAC frame is a lie`() {
        assertTrue(StreamContainerSniff.looksLossy(bytes(0xFF, 0xF1, 0x50, 0x80)))
    }

    @Test
    fun `a header this does not know is not evidence`() {
        assertFalse(StreamContainerSniff.looksLossy(ByteArray(0)))
        assertFalse(StreamContainerSniff.looksLossy(bytes(0x00)))
        assertFalse(StreamContainerSniff.looksLossy(ascii("OggS")))
    }

    @Test
    fun `a lossless file states its own length`() {
        val flac = StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)
        // 44,100 Hz * 2 bytes * 2 channels = 176,400 bytes a second.
        assertEquals(201, StreamContainerSniff.secondsOfLossless(35_456_400, flac))
        // A row that says 3:21 against a file that would run over an hour.
        assertTrue(TrackMatcher.isSevereMismatch(201, StreamContainerSniff.secondsOfLossless(700_000_000, flac)))
        // An unstated rate cannot be weighed.
        assertNull(StreamContainerSniff.secondsOfLossless(35_456_400, StreamFormat(codec = "flac")))
    }
}
