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

    /** Enough to tell these containers apart. */
    const val HEAD_BYTES = 12

    private const val FTYP_OFFSET = 4

    /**
     * True when [head] opens a container that cannot be the lossless file a row
     * claimed: ISO base media ("ftyp", which for these sources is AAC), an MPEG
     * audio frame or ID3 tag, or an ADTS AAC frame.
     */
    fun looksLossy(head: ByteArray): Boolean {
        if (head.size < 2) return false
        // ISO base media. A FLAC-in-MP4 exists, but no catalogue serves one and
        // a row that says "FLAC · 16-bit" about an MP4 is wrong either way.
        if (head.size >= FTYP_OFFSET + 4 &&
            head[FTYP_OFFSET] == 'f'.code.toByte() &&
            head[FTYP_OFFSET + 1] == 't'.code.toByte() &&
            head[FTYP_OFFSET + 2] == 'y'.code.toByte() &&
            head[FTYP_OFFSET + 3] == 'p'.code.toByte()
        ) {
            return true
        }
        // MPEG audio: an ID3 tag in front of the frames, or a frame sync.
        if (head.size >= 3 &&
            head[0] == 'I'.code.toByte() &&
            head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte()
        ) {
            return true
        }
        // ADTS AAC and MPEG layer III both open with eleven set bits.
        val first = head[0].toInt() and 0xFF
        val second = head[1].toInt() and 0xFF
        return first == 0xFF && (second and 0xE0) == 0xE0
    }

    /**
     * How many seconds a lossless file of [totalBytes] would run, from the
     * rendition its row claims. A lossless stream has a fixed bytes-per-second,
     * so its own size states its length — which is the second half of the same
     * check: a row that says 3:21 whose file would run 7:40 is not this song,
     * whatever container it is in.
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

    /** Lossless catalogues serve stereo. */
    private const val CHANNELS = 2L
}
