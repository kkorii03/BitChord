package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.PI
import kotlin.math.sin

/**
 * The verifier now decodes only the opening it compares, and may hand that
 * opening to the decoder while it is still arriving. Neither may move the
 * verdict: the frames of a shortened decode are the same frames a longer
 * decode produced, and aligning against either gives the same match and
 * offset.
 */
class HqVerifyWindowParityTest {

    private val rate = 8_000

    @Test
    fun `the compared window has exactly the frames the longer decode had`() {
        val song = song(seconds = 120)
        val whole = features(song)
        val window = features(song.copyOf((91 * rate)))

        // Smoothing at the very end may look a little further in the longer
        // decode, so compare the frames the verifier actually reads.
        val compared = 90.0 / ChromaAlignment.HOP_SECONDS
        val frames = minOf(window.frames, compared.toInt())
        assertTrue(frames > 800, "the window produced too few frames: ${window.frames}")
        for (i in 0 until frames) {
            for (pc in 0 until 12) {
                assertEquals(
                    whole.chroma[i * 12 + pc].toDouble(),
                    window.chroma[i * 12 + pc].toDouble(),
                    0.0001,
                    "frame $i pitch class $pc",
                )
            }
        }
    }

    @Test
    fun `a shortened video gives the same match and offset`() {
        val recordSong = song(seconds = 92)
        // One second of lead-in makes the answer a non-zero offset, so an
        // accidental zero-vs-zero comparison cannot pass this test.
        val videoSong = FloatArray((1 * rate) + recordSong.size)
        recordSong.copyInto(videoSong, 1 * rate)

        val record = features(recordSong)
        val fullVideo = features(videoSong)
        val shortVideo = features(videoSong.copyOf(91 * rate))

        val whole = assertNotNull(ChromaAlignment.align(record, fullVideo, videoComplete = false))
        val shortened = assertNotNull(ChromaAlignment.align(record, shortVideo, videoComplete = false))

        assertEquals(whole.matchPercent, shortened.matchPercent)
        assertEquals(whole.map.map(0L), shortened.map.map(0L))
        assertEquals(whole.rate, shortened.rate, 0.000001)
    }

    private fun features(buffer: FloatArray): ChromaAlignment.Features {
        val extractor = ChromaAlignment.Extractor(rate)
        var at = 0
        var piece = 997
        while (at < buffer.size) {
            val n = minOf(piece, buffer.size - at)
            extractor.push(buffer.copyOfRange(at, at + n), n)
            at += n
            piece = if (piece == 997) 4_013 else 997
        }
        return extractor.finish()
    }

    /** One changing note per second, long enough for the 90 s comparison. */
    private fun song(seconds: Int): FloatArray {
        val out = FloatArray(seconds * rate)
        val notes = intArrayOf(48, 52, 55, 57, 60, 64, 67, 72)
        for (s in out.indices) {
            val t = s.toDouble() / rate
            val note = notes[(s / rate) % notes.size]
            val hz = 440.0 * Math.pow(2.0, (note - 69) / 12.0)
            out[s] = (0.4 * sin(2 * PI * hz * t) + 0.2 * sin(4 * PI * hz * t)).toFloat()
        }
        return out
    }
}
