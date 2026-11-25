package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.ChromaAlignment
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class KoRecordingPrintTest {

    @Test
    fun `the same recording is the same at another rate, level, start and with noise`() {
        val reference = features(render(song = 1, take = 1, rate = 16_000), 16_000)
        val copy = features(render(song = 1, take = 1, rate = 11_025, lead = 1.3, gain = 0.4, noise = 0.002f), 11_025)

        assertEquals(KoRecordingPrint.Sound.SAME, KoRecordingPrint.compare(reference.print, copy.print))
    }

    // Phone 11-25: the chroma and the lyrics both matched 100 % and another singer played.
    @Test
    fun `another performance of the same notes is another recording`() {
        val reference = features(render(song = 1, take = 1, rate = 16_000), 16_000)
        val cover = features(render(song = 1, take = 2, rate = 16_000, beat = 0.83), 16_000)

        assertEquals(KoRecordingPrint.Sound.DIFFERENT, KoRecordingPrint.compare(reference.print, cover.print))
    }

    @Test
    fun `a file that holds part of the original is never called different`() {
        val original = render(song = 1, take = 1, rate = 16_000)
        val edit = render(song = 2, take = 1, rate = 16_000)
        original.copyInto(edit, 40 * 16_000, 0, 20 * 16_000)

        assertNotEquals(
            KoRecordingPrint.Sound.DIFFERENT,
            KoRecordingPrint.compare(features(original, 16_000).print, features(edit, 16_000).print),
        )
    }

    @Test
    fun `too little sound and no print say nothing`() {
        val reference = features(render(song = 1, take = 1, rate = 16_000), 16_000)
        val silence = features(FloatArray(60 * 16_000), 16_000)

        assertEquals(KoRecordingPrint.Sound.UNCLEAR, KoRecordingPrint.compare(silence.print, reference.print))
        assertEquals(KoRecordingPrint.Sound.UNCLEAR, KoRecordingPrint.compare(reference.print, null))
        assertNull(features(render(song = 1, take = 1, rate = 16_000), 16_000, print = false).print)
    }

    @Test
    fun `the print is kept with the profile and a profile without one still reads`() {
        val kept = features(render(song = 1, take = 1, rate = 16_000), 16_000)
        assertContentEquals(kept.print, ChromaAlignment.Features.fromBytes(kept.toBytes())!!.print)

        val plain = features(render(song = 1, take = 1, rate = 16_000), 16_000, print = false)
        val back = ChromaAlignment.Features.fromBytes(plain.toBytes())!!
        assertEquals(plain.frames, back.frames)
        assertNull(back.print)
    }

    /** Ten notes a [beat] from [song]'s own list, their loudness moved by [take]. */
    private fun render(
        song: Int,
        take: Int,
        rate: Int,
        lead: Double = 0.0,
        gain: Double = 1.0,
        noise: Float = 0f,
        beat: Double = 0.8,
    ): FloatArray {
        val notes = Random(song)
        val hz = Array(100) { DoubleArray(VOICES) { 300.0 * (2000.0 / 300.0).pow(notes.nextDouble()) } }
        val hands = Random(1_000 * song + take)
        val knots = Array(VOICES) { DoubleArray(700) { hands.nextDouble() } }
        val hiss = Random(7)
        return FloatArray(60 * rate) { n ->
            val t = n.toDouble() / rate - lead
            var v = 0.0
            if (t >= 0) {
                val chord = hz[(t / beat).toInt()]
                val at = t / 0.1
                val k = at.toInt()
                for (voice in 0 until VOICES) {
                    val level = knots[voice][k] * (1 - (at - k)) + knots[voice][k + 1] * (at - k)
                    v += level * level * sin(2 * PI * chord[voice] * t)
                }
            }
            (v * gain / VOICES).toFloat() + (hiss.nextFloat() * 2f - 1f) * noise
        }
    }

    private fun features(buffer: FloatArray, rate: Int, print: Boolean = true): ChromaAlignment.Features =
        ChromaAlignment.Extractor(rate, print).also { it.push(buffer) }.finish()

    private companion object {
        const val VOICES = 10
    }
}