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

    private val flac16 = StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)

    /** A real FLAC opening: the marker, a STREAMINFO block, and the stated samples. */
    private fun flacHead(rate: Int, seconds: Int): ByteArray {
        val samples = rate.toLong() * seconds
        val info = ByteArray(34)
        info[10] = (rate shr 12).toByte()
        info[11] = (rate shr 4).toByte()
        // Low nibble of rate, then stereo (1) and the top bit of depth-1 (15).
        info[12] = (((rate and 0x0F) shl 4) or (1 shl 1)).toByte()
        info[13] = ((15 shl 4) or ((samples shr 32).toInt() and 0x0F)).toByte()
        info[14] = (samples shr 24).toByte()
        info[15] = (samples shr 16).toByte()
        info[16] = (samples shr 8).toByte()
        info[17] = samples.toByte()
        return ascii("fLaC") + bytes(0x00, 0x00, 0x00, 0x22) + info
    }

    @Test
    fun `the size alone is only the shortest a lossless file can run`() {
        // 44,100 Hz * 2 bytes * 2 channels = 176,400 bytes a second.
        assertEquals(201, StreamContainerSniff.secondsOfLossless(35_456_400, flac16))
        // An unstated rate cannot be weighed.
        assertNull(StreamContainerSniff.secondsOfLossless(35_456_400, StreamFormat(codec = "flac")))
    }

    /**
     * Phone 11-06: 95 genuine Qobuz rows refused with `of 166s but the file runs
     * 117s`. FLAC compresses, so its size read as PCM always looks short: 117 s
     * of 166 s is 70%, an ordinary ratio.
     */
    @Test
    fun `a compressed flac is not refused for being smaller than its pcm`() {
        val bytes = 117L * 176_400
        assertNull(StreamContainerSniff.runtimeContradiction(166, flacHead(44_100, 166), bytes, flac16))
        assertNull(StreamContainerSniff.runtimeContradiction(166, ByteArray(0), bytes, flac16))
    }

    @Test
    fun `a flac states its own length and is believed over its size`() {
        assertEquals(166, StreamContainerSniff.flacSeconds(flacHead(44_100, 166)))
        assertEquals(197, StreamContainerSniff.flacSeconds(flacHead(96_000, 197)))
        // The row says 3:21 and the file says 7:40: another recording.
        assertEquals(460, StreamContainerSniff.runtimeContradiction(201, flacHead(44_100, 460), 1L, flac16))
        assertNull(StreamContainerSniff.flacSeconds(ascii("OggS")))
    }

    @Test
    fun `with no stream header the size still catches what it can prove`() {
        // Larger than the row could be even uncompressed: over an hour against 3:21.
        assertTrue(StreamContainerSniff.runtimeContradiction(201, ByteArray(0), 700_000_000, flac16) != null)
        // Too small to be the row at any real compression.
        assertTrue(StreamContainerSniff.runtimeContradiction(300, ByteArray(0), 176_400L * 10, flac16) != null)
        // No stated runtime, no contradiction.
        assertNull(StreamContainerSniff.runtimeContradiction(null, ByteArray(0), 700_000_000, flac16))
    }

    @Test
    fun `an id3 tag longer than the first look still shows the flac behind it`() {
        // The first look is only the ID3 header; the tag runs past it.
        val opening = ascii("ID3") + bytes(4, 0, 0, 0, 0, 0, 100)
        val full = opening + ByteArray(100) + flacHead(44_100, 180)
        val head = StreamContainerSniff.headForRefusal(opening) { full }
        assertFalse(StreamContainerSniff.looksLossy(head))
        assertNull(
            CachedRenditionBar.refusal(
                claimsLossless = true,
                head = head,
                rowSeconds = 180,
                renditionSeconds = 180,
            ),
        )
    }

    @Test
    fun `flac inside another wrapper is still lossless`() {
        // An ID3 tag of 16 bytes in front of the marker.
        val tagged = ascii("ID3") + bytes(4, 0, 0, 0, 0, 0, 16) + ByteArray(16) + flacHead(44_100, 180)
        assertFalse(StreamContainerSniff.looksLossy(tagged))
        assertEquals(180, StreamContainerSniff.flacSeconds(tagged))
        // FLAC carried in MP4 names itself in its sample entry.
        val inMp4 = bytes(0, 0, 0, 0x20) + ascii("ftypiso8") + ByteArray(40) + ascii("fLaC")
        assertFalse(StreamContainerSniff.looksLossy(inMp4))
        assertEquals("mp4", StreamContainerSniff.lossyContainer(bytes(0, 0, 0, 0x20) + ascii("ftypM4A ")))
    }

    /** ALAC in MP4 names itself the same way, and is every bit as lossless. */
    @Test
    fun `alac in an mp4 is lossless`() {
        val inMp4 = bytes(0, 0, 0, 0x20) + ascii("ftypM4A ") + ByteArray(40) + ascii("alac")
        assertFalse(StreamContainerSniff.looksLossy(inMp4))
    }

    /** Synchsafe: seven bits a byte, so a tag of [size] bytes in the ID3v2 header. */
    private fun id3Header(size: Int) = ascii("ID3") + bytes(
        4, 0, 0,
        (size shr 21) and 0x7F, (size shr 14) and 0x7F, (size shr 7) and 0x7F, size and 0x7F,
    )

    @Test
    fun `a tag ending past the head says how much more would show what is behind it`() {
        val head = id3Header(10_000) + ByteArray(4_000)
        // Ten bytes of header, the tag, and the FLAC opening behind it, length included.
        assertEquals(10_036, StreamContainerSniff.bytesPastId3(head))
    }

    @Test
    fun `no more is wanted when the head already shows it or there is no tag`() {
        assertNull(StreamContainerSniff.bytesPastId3(id3Header(16) + ByteArray(16) + flacHead(44_100, 180)))
        assertNull(StreamContainerSniff.bytesPastId3(flacHead(44_100, 180)))
        assertNull(StreamContainerSniff.bytesPastId3(ascii("ID3\u0004\u0000\u0000")))
    }

    /**
     * Not knowing is not good enough here: an MP3 with cover art opens exactly
     * like this, and it is far more common than a FLAC with an ID3 tag.
     */
    @Test
    fun `a tag whose end cannot be seen is still refused`() {
        assertEquals("id3", StreamContainerSniff.lossyContainer(id3Header(10_000) + ByteArray(4_000)))
    }
}
