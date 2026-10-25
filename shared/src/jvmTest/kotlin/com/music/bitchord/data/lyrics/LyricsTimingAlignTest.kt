package com.music.bitchord.data.lyrics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The video-timing arithmetic: the AI answer fit, the time map and the chroma
 * alignment, on answers and audio made up to have a known right answer.
 */
class LyricsTimingAlignTest {

    // ---- AI answers ------------------------------------------------------------------

    private val refs = (0 until 40).map { 12_000L + it * 4_500L }

    @Test
    fun aiFitTakesAPlainShift() {
        val rnd = Random(1)
        val points = refs.map { it to it + 7_300L + rnd.nextLong(-400, 400) }
        val line = assertNotNull(TimingFit.fit(points))
        assertEquals(1.0, line.rate)
        assertTrue(abs(line.offsetMs - 7_300L) < 200, "offset ${line.offsetMs}")
        assertEquals(points.size, line.inliers)
    }

    @Test
    fun aiFitFollowsAVideoThatRunsSlower() {
        // The bug this replaced: 70% close to the median was read as one shift,
        // so a 3% slower video ended up seconds late by the last chorus.
        val rnd = Random(2)
        val points = refs.map { it to (it * 1.03).toLong() + 2_000L + rnd.nextLong(-500, 500) }
        val line = assertNotNull(TimingFit.fit(points))
        assertTrue(abs(line.rate - 1.03) < 0.006, "rate ${line.rate}")
        val last = refs.last()
        assertTrue(abs(line.map.map(last) - ((last * 1.03).toLong() + 2_000L)) < 600)
    }

    @Test
    fun aiFitIgnoresAMinorityOfWildAnswers() {
        val rnd = Random(3)
        val points = refs.mapIndexed { i, r ->
            r to if (i % 4 == 0) rnd.nextLong(0, 200_000) else r + 4_000L + rnd.nextLong(-300, 300)
        }
        val line = assertNotNull(TimingFit.fit(points))
        assertEquals(1.0, line.rate)
        assertTrue(abs(line.offsetMs - 4_000L) < 300)
        assertTrue(line.inliers in 28..31, "inliers ${line.inliers}")
    }

    @Test
    fun aiFitRefusesAnswersThatAgreeOnNothing() {
        val rnd = Random(4)
        val points = refs.map { it to rnd.nextLong(0, 240_000) }.sortedBy { it.second }
        assertNull(TimingFit.fit(points))
    }

    @Test
    fun aiFitRefusesAnImplausiblePace() {
        val points = refs.map { it to it * 2 }
        assertNull(TimingFit.fit(points))
    }

    @Test
    fun aShortAnswerSpanCanOnlySayShiftNotPace() {
        // Three seconds of answers are all within one pair-span floor of each
        // other, so no slope is claimed: the result is the plain shift it is.
        val points = listOf(12_000L to 17_000L, 13_000L to 18_000L, 15_000L to 20_100L)
        val line = assertNotNull(TimingFit.fit(points))
        assertEquals(1.0, line.rate)
        assertEquals(5_000L, line.offsetMs)
        assertEquals(points.size, line.inliers)
    }

    @Test
    fun aNinetySecondAnswerSpanStillReadsAsTheSameShift() {
        // The song check's sample length must not change what a plain offset
        // means: a spread this wide still snaps to the record's pace.
        val points = listOf(10_000L, 40_000L, 70_000L, 100_000L).map { it to it + 4_000L }
        val line = assertNotNull(TimingFit.fit(points))
        assertEquals(1.0, line.rate)
        assertEquals(4_000L, line.offsetMs)
        assertEquals(points.size, line.inliers)
    }

    // ---- The time map ------------------------------------------------------------------

    @Test
    fun timeMapMovesWordsAndSyllablesWithTheLine() {
        val line = LyricLine(
            timeMs = 10_000L,
            text = "hello world",
            words = listOf(
                LyricWord(10_000L, 10_500L, "hello", listOf(
                    LyricSyllable(10_000L, 10_200L, 0, 2), LyricSyllable(10_200L, 10_500L, 2, 5),
                )),
                LyricWord(10_600L, 11_000L, "world"),
            ),
            sungUntilMs = 11_000L,
        )
        val moved = listOf(line).timedBy(LyricsTimeMap.linear(1.1, 1_000L), extraMs = -200L).single()
        assertEquals(11_800L, moved.timeMs)
        assertEquals(11_800L, moved.words[0].startMs)
        assertEquals(12_350L, moved.words[0].endMs)
        assertEquals(12_020L, moved.words[0].syllables[1].startMs)
        assertEquals(12_900L, moved.sungUntilMs)
        val shifted = listOf(line).timedBy(null, extraMs = 300L).single()
        assertEquals(10_300L, shifted.timeMs)
        assertTrue(listOf(line).timedBy(null, 0L).single() === line)
    }

    @Test
    fun timeMapThroughKnotsStaysInOrder() {
        val map = assertNotNull(
            LyricsTimeMap.through(listOf(0L, 10_000L, 9_000L, 20_000L), listOf(5_000L, 15_000L, 1L, 40_000L), 2.0, 1.0),
        )
        assertEquals(3, map.knots)
        assertEquals(10_000L, map.map(5_000L))
        assertEquals(27_500L, map.map(15_000L))
        assertEquals(45_000L, map.map(25_000L))
        assertEquals(1_000L, map.map(-2_000L))
        assertEquals(map, LyricsTimeMap.unflatten(map.flatten(), map.headRate, map.tailRate))
    }

    // ---- Chroma alignment ------------------------------------------------------------------

    /** A made-up song: chords (root, quality, seconds) and a melody note over each. */
    private class Song(val chords: List<Triple<Int, Boolean, Double>>, val melody: List<Int>)

    private fun song(seed: Int, count: Int): Song {
        val rnd = Random(seed)
        val chords = List(count) {
            Triple(rnd.nextInt(12), rnd.nextBoolean(), listOf(1.0, 1.5, 2.0, 2.5)[rnd.nextInt(4)])
        }
        return Song(chords, List(count) { rnd.nextInt(5) })
    }

    /** Where each chord starts, seconds, when rendered with [tempo] (1 = as written). */
    private fun starts(song: Song, tempo: Double, from: Double): DoubleArray {
        val out = DoubleArray(song.chords.size)
        var t = from
        for (i in song.chords.indices) {
            out[i] = t
            t += song.chords[i].third * tempo
        }
        return out
    }

    /**
     * Renders chords [range] of [song] into [buffer] from [at] seconds, as the
     * given instrument: [brightness] shapes the harmonics (a different band),
     * [transpose] moves the key.
     */
    private fun render(
        buffer: FloatArray,
        rate: Int,
        song: Song,
        range: IntRange,
        at: Double,
        tempo: Double,
        transpose: Int,
        brightness: Double,
        sparse: Boolean = false,
    ): Double {
        var t0 = at
        for (index in range) {
            val (root, minor, length) = song.chords[index]
            val seconds = length * tempo
            val melody = root + listOf(0, 2, 4, 7, 9)[song.melody[index]]
            val notes = if (sparse) {
                // Another arrangement: power chord down an octave, melody an octave lower.
                listOf(36 + root, 36 + root + 7, 60 + melody)
            } else {
                listOf(48 + root, 48 + root + if (minor) 3 else 4, 48 + root + 7, 72 + melody)
            }.map { it + transpose }
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
        // In uneven pieces, the way a decoder hands them over.
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

    @Test
    fun chromaFindsACoverWithAnIntroAKeyChangeASlowerPaceAndACut() {
        val rate = 48_000
        val original = song(seed = 11, count = 90)
        val record = FloatArray((starts(original, 1.0, 1.0).last() + 4.0).times(rate).toInt())
        render(record, rate, original, original.chords.indices, at = 1.0, tempo = 1.0, transpose = 0, brightness = 0.6)

        // The cover: 14 s of talking-ish noise and unrelated chords, two
        // semitones up, 3% slower, a different instrument, and chords 40..47
        // left out.
        val tempo = 1.03
        val cut = 40..47
        val other = song(seed = 99, count = 10)
        val video = FloatArray((starts(original, tempo, 14.0).last() + 30.0).times(rate).toInt())
        noise(video, 0, 6 * rate, 0.05f, 5)
        render(video, rate, other, 0 until 5, at = 6.0, tempo = 1.0, transpose = 0, brightness = 0.3)
        var t = 14.0
        val videoStart = DoubleArray(original.chords.size) { -1.0 }
        for (i in original.chords.indices) {
            if (i in cut) continue
            videoStart[i] = t
            t = render(video, rate, original, i..i, at = t, tempo = tempo, transpose = 2, brightness = 0.85)
        }
        noise(video, 0, video.size, 0.01f, 6)

        val result = assertNotNull(ChromaAlignment.align(features(record, rate), features(video, rate)))
        assertEquals(2, result.transpose)
        val recordStart = starts(original, 1.0, 1.0)
        val errors = original.chords.indices.filter { it !in cut && it !in 38..49 }.map { i ->
            abs(result.map.map((recordStart[i] * 1000).toLong()) - (videoStart[i] * 1000).toLong())
        }.sorted()
        val median = errors[errors.size / 2]
        val p90 = errors[errors.size * 9 / 10]
        println("CHROMA cover: match=${result.matchPercent}% ratio=${"%.2f".format(result.costRatio)} straight=${"%.2f".format(result.straightShare)} linear=${result.linear} median=${median}ms p90=${p90}ms")
        assertTrue(median <= 250, "median error $median ms")
        assertTrue(p90 <= 600, "90th percentile error $p90 ms")
    }

    @Test
    fun chromaFindsARearrangedNoisyCover() {
        val rate = 22_050
        val original = song(seed = 51, count = 90)
        val recordStart = starts(original, 1.0, 0.5)
        val record = FloatArray((recordStart.last() + 4.0).times(rate).toInt())
        render(record, rate, original, original.chords.indices, at = 0.5, tempo = 1.0, transpose = 0, brightness = 0.6)
        val tempo = 0.97
        val videoStart = starts(original, tempo, 20.0)
        val video = FloatArray((videoStart.last() + 10.0).times(rate).toInt())
        render(video, rate, original, original.chords.indices, at = 20.0, tempo = tempo, transpose = 0, brightness = 0.95, sparse = true)
        // A loud room and a drum kit: broadband noise plus a burst every half second.
        noise(video, 0, video.size, 0.08f, 9)
        var beat = 0
        while (beat * rate / 2 < video.size) {
            noise(video, beat * rate / 2, beat * rate / 2 + rate / 20, 0.5f, 100 + beat)
            beat++
        }
        val aligned = ChromaAlignment.align(features(record, rate), features(video, rate))
        println("CHROMA rearranged: ${ChromaAlignment.lastVerdict}")
        val result = assertNotNull(aligned)
        val errors = original.chords.indices.map { i ->
            abs(result.map.map((recordStart[i] * 1000).toLong()) - (videoStart[i] * 1000).toLong())
        }.sorted()
        println("CHROMA rearranged: median=${errors[errors.size / 2]} p90=${errors[errors.size * 9 / 10]}")
        assertTrue(errors[errors.size / 2] <= 300)
        assertTrue(errors[errors.size * 9 / 10] <= 700)
    }

    @Test
    fun chromaGivesAStraightLineForASimpleOffset() {
        val rate = 22_050
        val original = song(seed = 21, count = 80)
        val record = FloatArray((starts(original, 1.0, 0.5).last() + 4.0).times(rate).toInt())
        render(record, rate, original, original.chords.indices, at = 0.5, tempo = 1.0, transpose = 0, brightness = 0.6)
        val video = FloatArray(record.size + 9 * rate)
        noise(video, 0, 8 * rate, 0.04f, 7)
        render(video, rate, original, original.chords.indices, at = 8.5, tempo = 1.0, transpose = 0, brightness = 0.9)

        val result = assertNotNull(ChromaAlignment.align(features(record, rate), features(video, rate)))
        println("CHROMA shift: match=${result.matchPercent}% rate=${result.rate} linear=${result.linear} at60=${result.map.map(60_000)}")
        assertTrue(result.linear)
        assertEquals(1.0, result.rate)
        assertTrue(abs(result.map.map(60_000L) - 68_000L) <= 150, "mapped ${result.map.map(60_000L)}")
    }

    @Test
    fun chromaUsesTheOpeningOfAVideoStillDownloading() {
        val rate = 22_050
        val original = song(seed = 41, count = 90)
        val recordStart = starts(original, 1.0, 0.5)
        val record = FloatArray((recordStart.last() + 4.0).times(rate).toInt())
        render(record, rate, original, original.chords.indices, at = 0.5, tempo = 1.0, transpose = 0, brightness = 0.6)
        // Only the first 75 s of the video are on disk: 12 s of chatter, then
        // the song a little slower.
        val tempo = 1.02
        val videoStart = starts(original, tempo, 12.0)
        val full = FloatArray((videoStart.last() + 4.0).times(rate).toInt())
        noise(full, 0, 12 * rate, 0.04f, 8)
        render(full, rate, original, original.chords.indices, at = 12.0, tempo = tempo, transpose = 0, brightness = 0.9)
        val opening = full.copyOf(75 * rate)

        val aligned = ChromaAlignment.align(features(record, rate), features(opening, rate), videoComplete = false)
        println("CHROMA partial: ${ChromaAlignment.lastVerdict} covered=${aligned?.coveredUntilMs}")
        val partial = assertNotNull(aligned)
        assertTrue(partial.coveredUntilMs in 55_000L..64_000L, "covered ${partial.coveredUntilMs}")
        val inside = original.chords.indices.filter { recordStart[it] in 2.0..55.0 }.map { i ->
            abs(partial.map.map((recordStart[i] * 1000).toLong()) - (videoStart[i] * 1000).toLong())
        }.sorted()
        assertTrue(inside[inside.size / 2] <= 250, "median inside ${inside[inside.size / 2]}")
        // Read as if it were the whole video (a short version), the opening
        // still lines up the same way.
        val asWhole = ChromaAlignment.align(features(record, rate), features(opening, rate), videoComplete = true)
        if (asWhole != null) assertTrue(abs(asWhole.map.map(30_000L) - partial.map.map(30_000L)) <= 250)
    }

    @Test
    fun chromaAcceptsAShortVersionThatHoldsOnlyPartOfTheRecord() {
        val rate = 22_050
        val original = song(seed = 81, count = 120)
        val recordStart = starts(original, 1.0, 0.5)
        val record = FloatArray((recordStart.last() + 4.0).times(rate).toInt())
        render(record, rate, original, original.chords.indices, at = 0.5, tempo = 1.0, transpose = 0, brightness = 0.6)
        // A TV-size cover: the first 30 chords only (about a quarter), after an 8 s intro.
        val part = 0 until 30
        val videoStart = starts(original, 1.0, 8.0)
        val video = FloatArray((videoStart[30] + 6.0).times(rate).toInt())
        noise(video, 0, 8 * rate, 0.04f, 13)
        render(video, rate, original, part, at = 8.0, tempo = 1.0, transpose = 0, brightness = 0.9)
        val result = ChromaAlignment.align(features(record, rate), features(video, rate))
        println("CHROMA short version: ${ChromaAlignment.lastVerdict}")
        val found = assertNotNull(result)
        assertTrue(abs(found.map.map((recordStart[10] * 1000).toLong()) - (videoStart[10] * 1000).toLong()) <= 200)
    }

    @Test
    fun chromaRefusesADifferentSong() {
        val rate = 22_050
        val a = song(seed = 31, count = 80)
        val b = song(seed = 32, count = 80)
        val record = FloatArray((starts(a, 1.0, 0.5).last() + 4.0).times(rate).toInt())
        render(record, rate, a, a.chords.indices, at = 0.5, tempo = 1.0, transpose = 0, brightness = 0.6)
        val video = FloatArray((starts(b, 1.0, 0.5).last() + 4.0).times(rate).toInt())
        render(video, rate, b, b.chords.indices, at = 0.5, tempo = 1.0, transpose = 0, brightness = 0.6)
        val result = ChromaAlignment.align(features(record, rate), features(video, rate))
        println("CHROMA different: ${ChromaAlignment.lastVerdict}")
        assertNull(result)
    }

    @Test
    fun chromaRefusesANoisyRearrangementOfADifferentSong() {
        // The hard cover above, but of another song: a path through noise
        // stays diagonal for want of anything better, so the cost has to say no.
        val rate = 22_050
        val a = song(seed = 61, count = 90)
        val b = song(seed = 62, count = 90)
        val record = FloatArray((starts(a, 1.0, 0.5).last() + 4.0).times(rate).toInt())
        render(record, rate, a, a.chords.indices, at = 0.5, tempo = 1.0, transpose = 0, brightness = 0.6)
        val video = FloatArray((starts(b, 0.97, 20.0).last() + 10.0).times(rate).toInt())
        render(video, rate, b, b.chords.indices, at = 20.0, tempo = 0.97, transpose = 0, brightness = 0.95, sparse = true)
        noise(video, 0, video.size, 0.08f, 9)
        var beat = 0
        while (beat * rate / 2 < video.size) {
            noise(video, beat * rate / 2, beat * rate / 2 + rate / 20, 0.5f, 100 + beat)
            beat++
        }
        val result = ChromaAlignment.align(features(record, rate), features(video, rate))
        println("CHROMA noisy different: ${ChromaAlignment.lastVerdict}")
        assertNull(result)
    }

    @Test
    fun chromaFollowsAVideoWithTalkingInTheMiddle() {
        val rate = 22_050
        val original = song(seed = 71, count = 90)
        val recordStart = starts(original, 1.0, 0.5)
        val record = FloatArray((recordStart.last() + 4.0).times(rate).toInt())
        render(record, rate, original, original.chords.indices, at = 0.5, tempo = 1.0, transpose = 0, brightness = 0.6)
        // Chords 0..44, then 25 s of chatter, then the rest.
        val videoStart = DoubleArray(original.chords.size)
        val video = FloatArray((recordStart.last() + 40.0).times(rate).toInt())
        var t = 5.0
        for (i in original.chords.indices) {
            if (i == 45) {
                noise(video, (t * rate).toInt(), ((t + 25.0) * rate).toInt(), 0.05f, 12)
                t += 25.0
            }
            videoStart[i] = t
            t = render(video, rate, original, i..i, at = t, tempo = 1.0, transpose = 0, brightness = 0.9)
        }
        val result = assertNotNull(ChromaAlignment.align(features(record, rate), features(video, rate)))
        println("CHROMA insert: ${ChromaAlignment.lastVerdict}")
        assertTrue(!result.linear)
        val errors = original.chords.indices.filter { it !in 43..47 }.map { i ->
            abs(result.map.map((recordStart[i] * 1000).toLong()) - (videoStart[i] * 1000).toLong())
        }.sorted()
        println("CHROMA insert: median=${errors[errors.size / 2]} p90=${errors[errors.size * 9 / 10]} max=${errors.last()}")
        assertTrue(errors[errors.size * 9 / 10] <= 400)
    }

    @Test
    fun profilesSurviveTheDiskAndArriveInPieces() {
        val rate = 22_050
        val original = song(seed = 91, count = 50)
        val audio = FloatArray((starts(original, 1.0, 0.5).last() + 4.0).times(rate).toInt())
        render(audio, rate, original, original.chords.indices, at = 0.5, tempo = 1.0, transpose = 0, brightness = 0.6)
        val whole = features(audio, rate)
        // Read in two goes, asked for in between: the same as read in one.
        val extractor = ChromaAlignment.Extractor(rate)
        val half = audio.size / 2
        extractor.push(audio.copyOfRange(0, half))
        val early = extractor.finish()
        extractor.push(audio.copyOfRange(half, audio.size))
        val late = extractor.finish()
        assertTrue(early.frames < late.frames)
        assertEquals(whole.frames, late.frames)
        val back = assertNotNull(ChromaAlignment.Features.fromBytes(whole.toBytes()))
        assertEquals(whole.frames, back.frames)
        assertEquals(whole.hopSeconds, back.hopSeconds)
        // Kept on disk, it still finds itself, to the frame.
        val shifted = FloatArray(audio.size + 5 * rate)
        audio.copyInto(shifted, 5 * rate)
        val found = assertNotNull(ChromaAlignment.align(back, features(shifted, rate)))
        assertTrue(abs(found.map.map(30_000L) - 35_000L) <= 60, "mapped ${found.map.map(30_000L)}")
        assertNull(ChromaAlignment.Features.fromBytes(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun chromaRefusesSilenceAndShortAudio() {
        val rate = 16_000
        val quiet = FloatArray(60 * rate)
        assertNull(ChromaAlignment.align(features(quiet, rate), features(quiet, rate)))
        val short = FloatArray(5 * rate)
        render(short, rate, song(1, 4), 0 until 3, 0.0, 1.0, 0, 0.6)
        assertNull(ChromaAlignment.align(features(short, rate), features(short, rate)))
    }
}
