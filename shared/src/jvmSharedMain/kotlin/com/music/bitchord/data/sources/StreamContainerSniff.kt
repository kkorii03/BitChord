package com.music.bitchord.data.sources

/**
 * Whether a stream's first bytes are a lossy file, whatever the source called it.
 *
 * A catalogue describes a row's rendition from its own metadata: "FLAC · 16-bit
 * · 44.1 kHz". Nothing makes it open the file it hands over. LastWave answered a
 * request for ILLIT's "It's Me" with a row whose title, artist, album and
 * runtime were all correct and whose bytes were a 22.1 kHz AAC recording of
 * something else entirely, so a phone call played instead of the song (phone
 * 11-04). The player only found out when it decoded the stream — by then the
 * wrong audio was already audible.
 *
 * The container is in the first handful of bytes, so a lookup can refuse the row
 * before the player commits to it. Only a *lossless* claim is checked here: a
 * source that says MP3 and serves MP3 has nothing to prove, and a header this
 * does not recognise is not evidence of anything.
 */
object StreamContainerSniff {

    /**
     * Enough to tell these containers apart, read a FLAC's own stream header,
     * and step over a modest ID3 tag in front of it.
     */
    const val HEAD_BYTES = 4096

    private const val FTYP_OFFSET = 4

    /** ID3v2's fixed header: "ID3", version, flags, four synchsafe size bytes. */
    private const val ID3_HEADER_BYTES = 10

    /** The FLAC marker, a metadata block header, then STREAMINFO's first 18 bytes. */
    private const val FLAC_STREAMINFO_BYTES = 4 + 4 + 18

    /**
     * True when [head] opens a container that cannot be the lossless file a row
     * claimed: ISO base media ("ftyp", which for these sources is AAC), an MPEG
     * audio frame or ID3 tag, or an ADTS AAC frame.
     */
    fun looksLossy(head: ByteArray): Boolean = lossyContainer(head) != null

    /** Which lossy container [head] opens, for the refusal's log line; null when none. */
    fun lossyContainer(head: ByteArray): String? {
        if (head.size < 2) return null
        // ISO base media. FLAC or ALAC can be carried in MP4, and then its sample
        // entry says so in the opening; without that it is AAC under a FLAC claim.
        if (head.size >= FTYP_OFFSET + 4 && matches(head, FTYP_OFFSET, FTYP)) {
            return if (indexOf(head, FLAC_MARKER) >= 0 || indexOf(head, ALAC_MARKER) >= 0) null else "mp4"
        }
        // MPEG audio: an ID3 tag in front of the frames, or a frame sync. FLAC
        // files sometimes carry an ID3 tag too, and the marker follows it.
        if (matches(head, 0, ID3)) {
            val start = afterId3(head)
            return if (start != null && matches(head, start, FLAC_MARKER)) null else "id3"
        }
        // ADTS AAC and MPEG layer III both open with eleven set bits.
        val first = head[0].toInt() and 0xFF
        val second = head[1].toInt() and 0xFF
        return if (first == 0xFF && (second and 0xE0) == 0xE0) "mpeg-frame" else null
    }

    /**
     * How long a FLAC file runs, from its own STREAMINFO: the sample rate and
     * the total samples, which the encoder wrote and nothing here has to guess.
     * Null for anything else, and for a stream that left the count at zero
     * (the format allows "unknown").
     */
    fun flacSeconds(head: ByteArray): Int? {
        val start = if (matches(head, 0, ID3)) afterId3(head) ?: return null else 0
        if (!matches(head, start, FLAC_MARKER)) return null
        if (head.size < start + FLAC_STREAMINFO_BYTES) return null
        // The low seven bits of the block header name the block; 0 is STREAMINFO.
        if (head[start + 4].toInt() and 0x7F != 0) return null
        val info = start + 8
        fun at(offset: Int) = (head[info + offset].toLong() and 0xFF)
        // Bytes 10..17: 20 bits of rate, 3 of channels, 5 of depth, 36 of samples.
        val rate = (at(10) shl 12) or (at(11) shl 4) or (at(12) shr 4)
        val samples = ((at(13) and 0x0F) shl 32) or (at(14) shl 24) or (at(15) shl 16) or
            (at(16) shl 8) or at(17)
        if (rate <= 0 || samples <= 0) return null
        return (samples / rate).toInt()
    }

    /**
     * The shortest a lossless file of [totalBytes] can run, from the rendition
     * its row claims: what it would run uncompressed.
     *
     * Only a floor. FLAC compresses to about 40-80% of that, and treating this
     * figure as the length refused every genuine file — phone 11-06: 95 Qobuz
     * rows refused with `of 166s but the file runs 117s`, all of them within
     * that ratio. [runtimeContradiction] is what decides.
     *
     * Null when the row does not state the format well enough to answer.
     */
    fun secondsOfLossless(totalBytes: Long, format: StreamFormat): Int? {
        if (totalBytes <= 0) return null
        val rate = format.sampleRateHz?.takeIf { it > 0 } ?: return null
        val depth = format.bitDepth?.takeIf { it > 0 } ?: 16
        val bytesPerSecond = rate.toLong() * (depth / 8).toLong() * CHANNELS
        if (bytesPerSecond <= 0) return null
        return (totalBytes / bytesPerSecond).toInt()
    }

    /**
     * How long the file really runs, when that disagrees with the [statedSec]
     * its row claims: a row that says 3:21 and a file that runs 7:40 is not this
     * song, whatever container it is in. Null when nothing contradicts the row.
     *
     * A FLAC states its own length. Without that only the size is left, which
     * bounds the length rather than measuring it, so the answer is then the
     * bound the row fell outside.
     */
    fun runtimeContradiction(
        statedSec: Int?,
        head: ByteArray,
        totalBytes: Long?,
        format: StreamFormat,
    ): Int? {
        if (statedSec == null) return null
        flacSeconds(head)?.let { seconds ->
            return seconds.takeIf { TrackMatcher.isSevereMismatch(statedSec, it) }
        }
        val least = totalBytes?.let { secondsOfLossless(it, format) } ?: return null
        val most = least * MAX_COMPRESSION
        return when {
            least - statedSec > TrackMatcher.DURATION_LIMIT_SEC -> least
            statedSec - most > TrackMatcher.DURATION_LIMIT_SEC -> most
            else -> null
        }
    }

    /**
     * How many bytes would show what follows [head]'s ID3v2 tag, when the tag
     * runs past [head]. Null when there is no tag, or its end is already in view.
     */
    fun bytesPastId3(head: ByteArray): Int? {
        if (!matches(head, 0, ID3) || head.size < ID3_HEADER_BYTES || afterId3(head) != null) return null
        return id3End(head) + FLAC_STREAMINFO_BYTES
    }

    /** Where the audio starts behind [head]'s ID3v2 tag, when the tag ends inside [head]. */
    private fun afterId3(head: ByteArray): Int? {
        if (head.size < ID3_HEADER_BYTES) return null
        return id3End(head).takeIf { it < head.size }
    }

    private fun id3End(head: ByteArray): Int {
        var size = 0
        for (index in 6..9) size = (size shl 7) or (head[index].toInt() and 0x7F)
        return ID3_HEADER_BYTES + size
    }

    private fun matches(head: ByteArray, at: Int, wanted: ByteArray): Boolean {
        if (at < 0 || head.size < at + wanted.size) return false
        return wanted.indices.all { head[at + it] == wanted[it] }
    }

    private fun indexOf(head: ByteArray, wanted: ByteArray): Int =
        (0..head.size - wanted.size).firstOrNull { matches(head, it, wanted) } ?: -1

    private val FLAC_MARKER = "fLaC".toByteArray(Charsets.US_ASCII)
    private val ALAC_MARKER = "alac".toByteArray(Charsets.US_ASCII)
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
    private val ID3 = "ID3".toByteArray(Charsets.US_ASCII)

    /** Lossless catalogues serve stereo. */
    private const val CHANNELS = 2L

    /** No real recording compresses past a fifth of its PCM; silence aside. */
    private const val MAX_COMPRESSION = 5
}
