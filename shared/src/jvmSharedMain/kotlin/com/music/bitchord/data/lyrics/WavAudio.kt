package com.music.bitchord.data.lyrics

/**
 * The audio in a RIFF/WAVE file as mono floats, for the chroma measurement.
 *
 * The song check already decodes a track's opening to a small WAV
 * (`VideoAudio.songWav`); reading those samples directly avoids a second trip
 * through MediaExtractor and a container that may only be a truncated head.
 *
 * Only 16-bit PCM is read, which is what `VideoAudio` writes. Chunks other
 * than `fmt ` and `data` are skipped, so a file with metadata before the
 * samples still reads. Pure, so it can be tested without a decoder.
 */
object WavAudio {

    /** The samples of one WAV, already downmixed to mono at the file's own rate. */
    data class Pcm(val samples: FloatArray, val sampleRate: Int, val channels: Int)

    /** The WAV's samples as mono floats, or null when it is not 16-bit PCM. */
    fun monoFloat(wav: ByteArray): Pcm? {
        if (wav.size < 12) return null
        if (!tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")) return null
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataAt = -1
        var dataSize = 0
        var at = 12
        while (at + 8 <= wav.size) {
            val id = tag(wav, at)
            val size = readInt(wav, at + 4)
            if (size < 0) return null
            val body = at + 8
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > wav.size) return null
                    format = readShort(wav, body)
                    channels = readShort(wav, body + 2)
                    sampleRate = readInt(wav, body + 4)
                    bits = readShort(wav, body + 14)
                    if (format != PCM || channels <= 0 || sampleRate <= 0) return null
                }
                "data" -> if (dataAt < 0) {
                    dataAt = body
                    // A truncated file still yields the samples it actually
                    // holds; the header's length is only an upper bound.
                    dataSize = size.coerceAtMost(wav.size - body)
                }
            }
            if (id == "data") break
            val step = size.toLong() + (size and 1)
            if (body.toLong() + step > wav.size) break
            at = (body.toLong() + step).toInt()
        }
        if (format != PCM || bits != 16 || dataAt < 0 || dataSize < 2) return null
        val frameBytes = channels * 2
        val frames = dataSize / frameBytes
        if (frames <= 0) return null
        val samples = FloatArray(frames)
        var position = dataAt
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                sum += readShort(wav, position).toShort().toInt()
                position += 2
            }
            samples[frame] = sum / (channels * 32768f)
        }
        return Pcm(samples, sampleRate, channels)
    }

    private const val PCM = 1

    private fun tag(wav: ByteArray, at: Int): String =
        String(wav, at, 4, Charsets.US_ASCII)

    private fun tag(wav: ByteArray, at: Int, expected: String): Boolean =
        at + 4 <= wav.size && tag(wav, at) == expected

    private fun readShort(wav: ByteArray, at: Int): Int =
        (wav[at].toInt() and 0xff) or ((wav[at + 1].toInt() and 0xff) shl 8)

    private fun readInt(wav: ByteArray, at: Int): Int =
        (wav[at].toInt() and 0xff) or
            ((wav[at + 1].toInt() and 0xff) shl 8) or
            ((wav[at + 2].toInt() and 0xff) shl 16) or
            ((wav[at + 3].toInt() and 0xff) shl 24)
}
