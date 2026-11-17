package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.ChromaAlignment
import com.music.bitchord.data.lyrics.LyricsTimeMap
import com.music.bitchord.data.sources.HqMatchPolicy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review 11-17, NR2b-1. A high-quality copy that is another edit (an insert or a
 * cut inside the compared opening) aligns only along a bent path. HqAudioVerify
 * kept just the head offset and an overall rate of about 1.0 from it and stored
 * that as the copy's clock, so everything after the edit point — swap landing,
 * revert, length proof, lyric map — was off by the insert. Such a match is now
 * left unmeasured instead of stored or refused.
 *
 * The audio is made up the same way as in LyricsTimingAlignTest (chords with a
 * melody), so the right answer is known.
 */
class KoHqWarpTest {

    @AfterTest
    fun clear() = KoHqWarp.clearForTest()

    @Test
    fun anInsertedPassageBendsTheMatchAndTheStoredLineMissesItByTheInsert() {
        val rate = 22_050
        val original = song(seed = 71, count = 90)
        val recordStart = starts(original, 1.0, 0.5)
        val record = FloatArray((recordStart.last() + 4.0).times(rate).toInt())
        render(record, rate, original, original.chords.indices, at = 0.5, brightness = 0.6)
        // The other edit: chords 0..44, then 25 s of something else, then the rest.
        val candidateStart = DoubleArray(original.chords.size)
        val candidate = FloatArray((recordStart.last() + 40.0).times(rate).toInt())
        var t = 5.0
        for (i in original.chords.indices) {
            if (i == 45) {
                noise(candidate, (t * rate).toInt(), ((t + 25.0) * rate).toInt(), 0.05f, 12)
                t += 25.0
            }
            candidateStart[i] = t
            t = render(candidate, rate, original, i..i, at = t, brightness = 0.9)
        }
        val result = assertNotNull(ChromaAlignment.align(features(record, rate), features(candidate, rate)))
        println("KO warp: ${ChromaAlignment.lastVerdict}")
        assertFalse(result.linear)

        // What the verdict store kept before: one offset and one rate for the whole song.
        val stored = { youtubeMs: Long -> HqMatchPolicy.applyOffset(youtubeMs, result.map.map(0L), result.rate) }
        val after = 50
        val youtubeMs = (recordStart[after] * 1000).toLong()
        val truth = (candidateStart[after] * 1000).toLong()
        assertTrue(abs(stored(youtubeMs) - truth) > 5_000, "stored ${stored(youtubeMs)} vs $truth")

        // Whatever the policy says about the figures, a bent match is not stored.
        assertTrue(KoHqWarp.bent(accepted = true, result = result))
    }

    @Test
    fun aPlainOffsetCopyStaysALineAndIsStoredAsBefore() {
        val rate = 22_050
        val original = song(seed = 21, count = 80)
        val record = FloatArray((starts(original, 1.0, 0.5).last() + 4.0).times(rate).toInt())
        render(record, rate, original, original.chords.indices, at = 0.5, brightness = 0.6)
        val candidate = FloatArray(record.size + 9 * rate)
        noise(candidate, 0, 8 * rate, 0.04f, 7)
        render(candidate, rate, original, original.chords.indices, at = 8.5, brightness = 0.9)
        val result = assertNotNull(ChromaAlignment.align(features(record, rate), features(candidate, rate)))
        assertTrue(result.linear)
        assertFalse(KoHqWarp.bent(accepted = true, result = result))
    }

    @Test
    fun aRefusedMatchStaysARefusalWhateverItsShape() {
        assertFalse(KoHqWarp.bent(accepted = false, result = result(linear = false)))
        assertFalse(KoHqWarp.bent(accepted = false, result = result(linear = true)))
        assertFalse(KoHqWarp.bent(accepted = true, result = result(linear = true)))
        assertTrue(KoHqWarp.bent(accepted = true, result = result(linear = false)))
    }

    @Test
    fun aBentFileIsRememberedForThisRunOnly() {
        assertFalse(KoHqWarp.fileNoted("yt1", "content://a"))
        KoHqWarp.noteFile("yt1", "content://a")
        assertTrue(KoHqWarp.fileNoted("yt1", "content://a"))
        assertFalse(KoHqWarp.fileNoted("yt1", "content://b"))
        assertFalse(KoHqWarp.fileNoted("yt2", "content://a"))
        KoHqWarp.clearForTest()
        assertFalse(KoHqWarp.fileNoted("yt1", "content://a"))
    }

    private fun result(linear: Boolean) = ChromaAlignment.Result(
        map = LyricsTimeMap.shift(1_000L),
        rate = 1.0,
        linear = linear,
        matchPercent = 95,
        transpose = 0,
        costRatio = 0.3,
        straightShare = 0.95,
        coveredUntilMs = 85_000L,
    )

    // ---- Made-up audio (as in LyricsTimingAlignTest) ------------------------------

    private class Song(val chords: List<Triple<Int, Boolean, Double>>, val melody: List<Int>)

    private fun song(seed: Int, count: Int): Song {
        val rnd = Random(seed)
        val chords = List(count) {
            Triple(rnd.nextInt(12), rnd.nextBoolean(), listOf(1.0, 1.5, 2.0, 2.5)[rnd.nextInt(4)])
        }
        return Song(chords, List(count) { rnd.nextInt(5) })
    }

    private fun starts(song: Song, tempo: Double, from: Double): DoubleArray {
        val out = DoubleArray(song.chords.size)
        var t = from
        for (i in song.chords.indices) {
            out[i] = t
            t += song.chords[i].third * tempo
        }
        return out
    }

    private fun render(buffer: FloatArray, rate: Int, song: Song, range: IntRange, at: Double, brightness: Double): Double {
        var t0 = at
        for (index in range) {
            val (root, minor, seconds) = song.chords[index]
            val melody = root + listOf(0, 2, 4, 7, 9)[song.melody[index]]
            val notes = listOf(48 + root, 48 + root + if (minor) 3 else 4, 48 + root + 7, 72 + melody)
            val from = (t0 * rate).toInt()
            val to = minOf(buffer.size, ((t0 + seconds) * rate).toInt())
            for (s in from until to) {
                val t = (s - from).toDouble() / rate
                val envelope = minOf(1.0, t / 0.02) * (1.0 - 0.4 * t / seconds)
                var v = 0.0
                for ((n, midi) in notes.withIndex()) {
                    val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
                    val gain = if (n == notes.size - 1) 0.35 else 0.2
                    for (h in 1..4) v += gain * brightness.pow(h - 1) * sin(2 * PI * hz * h * t) / h
                }
                buffer[s] += (v * envelope * 0.5).toFloat()
            }
            t0 += seconds
        }
        return t0
    }

    private fun noise(buffer: FloatArray, from: Int, to: Int, level: Float, seed: Int) {
        val rnd = Random(seed)
        for (s in from until minOf(to, buffer.size)) buffer[s] += (rnd.nextFloat() * 2f - 1f) * level
    }

    private fun features(buffer: FloatArray, rate: Int): ChromaAlignment.Features {
        val extractor = ChromaAlignment.Extractor(rate)
        var at = 0
        var piece = 1_000
        while (at < buffer.size) {
            val n = minOf(piece, buffer.size - at)
            extractor.push(buffer.copyOfRange(at, at + n), n)
            at += n
            piece = if (piece == 1_000) 3_841 else 1_000
        }
        return extractor.finish()
    }
}
