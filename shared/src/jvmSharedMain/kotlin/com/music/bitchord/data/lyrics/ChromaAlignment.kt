package com.music.bitchord.data.lyrics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lines a video up against the record by what is being *played*, not how loud
 * it is.
 *
 * A cover is rarely the record: another singer, another band or a piano, a
 * longer intro, a bridge cut short, sometimes a different key. The loudness
 * curve the old measurement slid around changes with all of that. What a
 * cover keeps is the song — its chords and melody, the sequence of notes — so
 * both are reduced to a chroma: for every tenth of a second, how much of each
 * of the twelve pitch classes (C, C♯, … B) is sounding, whatever octave or
 * instrument it is on. Two takes of the same song then trace the same path
 * through those twelve dimensions, each at its own pace.
 *
 * Dynamic time warping finds that path: the cheapest walk through the grid of
 * record moments × video moments, moving forward in either or both, where a
 * step costs how unlike the two moments sound. Its ends are left open — the
 * record can start part-way into the video (an intro, a chat) and the video
 * can stop before the record does (a short version) — with a fixed charge for
 * every second of the record left out, so leaving the song out is never
 * cheaper than matching it.
 *
 * Key changes are tried up front: the record is rotated through the twelve
 * transpositions, the ones whose overall pitch profile resembles the video's
 * most are aligned coarsely (0.4 s steps), and the best of those is refined at
 * full resolution in a band around the coarse path. The answer is accepted
 * only when most of the record found a close match and the matched moments
 * resemble each other far more than random moments of the two do — a
 * different song, or a cover too far from the record, gives nothing rather
 * than a guess.
 *
 * Pure arithmetic over decoded samples; decoding is the caller's.
 */
object ChromaAlignment {

    /** One chroma frame every this many seconds (approximately; see [Features.hopSeconds]). */
    const val HOP_SECONDS = 0.1

    /** Samples per analysis window at the reduced rate (~0.38 s). */
    private const val FFT_SIZE = 2048

    /** Audio is reduced to about this rate before analysis: 2.5 kHz of bandwidth covers the notes chroma reads. */
    private const val TARGET_RATE = 5_000

    /** Pitches outside this range say little about the key: rumble below, harmonics and hiss above. */
    private const val MIN_HZ = 80.0
    private const val MAX_HZ = 2_100.0

    /** A frame quieter than this (RMS, full scale = 1) is silence. */
    private const val SILENCE_RMS = 0.003f

    /** Frames averaged together (each side) to steady the profile across a held note's vibrato. */
    private const val SMOOTH_RADIUS = 2

    /** Coarse pass: this many frames per step. */
    private const val COARSE = 4

    /** How far (frames) the fine pass may stray from the coarse path. */
    private const val BAND_RADIUS = 24

    // Every cost below is in units of what a random pairing of the two
    // costs ([randomPairCost]), so they mean the same thing whether the two
    // takes' profiles are sparse (a solo instrument) or dense (a full mix).

    /** Cost of leaving out one frame of the record entirely. */
    private const val SKIP_COST = 0.8f

    /**
     * Cost of each video frame before the path starts. Cheaper than matching
     * an intro of talk to the record, so the path still starts where the song
     * does — but not free, or with only the opening of a video on hand a path
     * could skip all of it and claim the record simply hadn't started yet.
     */
    private const val VIDEO_SKIP_COST = 0.5f

    /** The least a step costs that moves on in the record alone — a little over [SKIP_COST]. */
    private const val RECORD_STEP_FLOOR = SKIP_COST + 0.05f

    /** Extra cost of a step that moves on in only one of the two. */
    private const val STEP_PENALTY = 0.12f

    /** At least this share of the record's sounding frames must be matched in step. */
    private const val MIN_MATCHED = 0.5

    /** On average, the path's frames must cost at most this much (random pairs cost 1). */
    private const val MAX_COST_RATIO = 0.8

    /** At least this share of the matched path's steps must move on in both at once. */
    private const val MIN_STRAIGHT = 0.60

    /**
     * The warp must cost at most this share of what it costs against the
     * video played backwards — see the significance test in [align].
     * Different songs come out at 1.00; covers measured at 0.03–0.85.
     */
    private const val MAX_CHANCE_RATIO = 0.92

    /** A video holding only part of the record: what it holds must match this well. */
    private const val MIN_PART_MATCHED = 0.2
    private const val MIN_PART_FRAMES = 300
    private const val MIN_PART_STRAIGHT = 0.8
    private const val MAX_PART_COST_RATIO = 0.7

    /** With only the opening of a video, a path ending this close (frames) to its end has reached it. */
    private const val END_SLACK = 20

    /** How much of each end (record ms) sets the pace a warp carries on at past it. */
    private const val EDGE_SPAN_MS = 30_000L

    /** Fewer frames than this (record or video) is not enough song to align. */
    private const val MIN_FRAMES = 150

    /**
     * Twelve-dimensional pitch profiles of one piece of audio, one per
     * [hopSeconds], unit length, row-major ([frames] × 12).
     */
    class Features internal constructor(
        internal val chroma: FloatArray,
        val frames: Int,
        val hopSeconds: Double,
        /** Where frame 0 is centred, seconds into the audio. */
        val firstCenterSeconds: Double,
        internal val silent: BooleanArray,
    ) {
        fun secondsAt(frame: Double): Double = firstCenterSeconds + frame * hopSeconds
        val durationSeconds: Double get() = secondsAt((frames - 1).coerceAtLeast(0).toDouble())
    }

    /**
     * Turns decoded mono samples into [Features] as they arrive, so a whole
     * song never has to be held in memory: a four-minute track at 48 kHz is
     * 46 MB of floats, its chroma 115 kB.
     *
     * Feed it with [push] in order, then call [finish] once.
     */
    class Extractor(sourceRate: Int) {
        private val factor = max(1, sourceRate / TARGET_RATE)
        private val rate = sourceRate.toDouble() / factor
        private val hop = max(1, (rate * HOP_SECONDS).roundToInt())

        // Anti-alias low-pass applied before keeping every [factor]th sample.
        private val taps: FloatArray = if (factor == 1) floatArrayOf(1f) else lowPass(8 * factor + 1, 0.45 / factor)
        private val history = FloatArray(taps.size)
        private var historyAt = 0
        private var phase = 0

        // The last FFT_SIZE reduced samples, circular.
        private val window = FloatArray(FFT_SIZE)
        private var reduced = 0L
        private var nextFrameAt = FFT_SIZE.toLong()

        private val hann = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2.0 * PI * it / FFT_SIZE)).toFloat() }
        private val re = DoubleArray(FFT_SIZE)
        private val im = DoubleArray(FFT_SIZE)
        private val pitchClass = IntArray(FFT_SIZE / 2) { bin ->
            val hz = bin * rate / FFT_SIZE
            if (hz < MIN_HZ || hz > MAX_HZ) -1 else Math.floorMod((69.0 + 12.0 * log2(hz / 440.0)).roundToInt(), 12)
        }

        private var out = FloatArray(12 * 2048)
        private var silentOut = BooleanArray(2048)
        private var frames = 0
        private var finished = false

        /** Adds [count] samples from [samples]. */
        fun push(samples: FloatArray, count: Int = samples.size) {
            check(!finished) { "already finished" }
            for (n in 0 until count) {
                history[historyAt] = samples[n]
                historyAt = if (historyAt + 1 == history.size) 0 else historyAt + 1
                phase++
                if (phase < factor) continue
                phase = 0
                // Filtered sample: taps against history, newest first.
                var acc = 0f
                var h = historyAt - 1
                for (k in taps.indices) {
                    if (h < 0) h += history.size
                    acc += taps[k] * history[h]
                    h--
                }
                window[(reduced % FFT_SIZE).toInt()] = acc
                reduced++
                if (reduced == nextFrameAt) {
                    frame()
                    nextFrameAt += hop
                }
            }
        }

        private fun frame() {
            val start = reduced - FFT_SIZE
            var energy = 0.0
            for (k in 0 until FFT_SIZE) {
                val v = window[((start + k) % FFT_SIZE).toInt()]
                energy += v * v
                re[k] = (v * hann[k]).toDouble()
                im[k] = 0.0
            }
            if (frames == silentOut.size) {
                out = out.copyOf(out.size * 2)
                silentOut = silentOut.copyOf(silentOut.size * 2)
            }
            val base = frames * 12
            val rms = sqrt(energy / FFT_SIZE).toFloat()
            if (rms < SILENCE_RMS) {
                silentOut[frames] = true
            } else {
                fft(re, im)
                var peak = 0.0
                val acc = DoubleArray(12)
                for (bin in 1 until FFT_SIZE / 2) {
                    val pc = pitchClass[bin]
                    if (pc < 0) continue
                    val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                    acc[pc] += mag
                }
                for (pc in 0 until 12) peak = max(peak, acc[pc])
                if (peak <= 0.0) {
                    silentOut[frames] = true
                } else {
                    // Log compression: the loudest note doesn't drown the rest,
                    // and a cover's different mix still reads as the same chord.
                    for (pc in 0 until 12) out[base + pc] = ln(1.0 + 40.0 * acc[pc] / peak).toFloat()
                }
            }
            frames++
        }

        /**
         * The profiles of everything pushed, the first sample of which was
         * [startSeconds] into the audio.
         */
        fun finish(startSeconds: Double = 0.0): Features {
            finished = true
            val n = frames
            val smoothed = FloatArray(n * 12)
            val uniform = (1.0 / sqrt(12.0)).toFloat()
            for (i in 0 until n) {
                val from = max(0, i - SMOOTH_RADIUS)
                val to = min(n - 1, i + SMOOTH_RADIUS)
                var any = false
                for (f in from..to) {
                    if (silentOut[f]) continue
                    any = true
                    for (pc in 0 until 12) smoothed[i * 12 + pc] += out[f * 12 + pc]
                }
                if (!any) {
                    for (pc in 0 until 12) smoothed[i * 12 + pc] = uniform
                } else {
                    normalize(smoothed, i * 12)
                }
            }
            return Features(
                chroma = smoothed,
                frames = n,
                hopSeconds = hop / rate,
                firstCenterSeconds = startSeconds + (FFT_SIZE / 2.0) / rate,
                silent = silentOut.copyOf(n),
            )
        }
    }

    /** What [align] found. */
    data class Result(
        /** Record ms → video ms. */
        val map: LyricsTimeMap,
        /** Video ms per record ms over the matched part. */
        val rate: Double,
        /** True when one straight line describes the whole match (no cut, no drift). */
        val linear: Boolean,
        /** Share of the record's sounding frames that found a close match, 0–100. */
        val matchPercent: Int,
        /** Semitones the record was moved to match the video's key (0–11, up). */
        val transpose: Int,
        /** Mean cost of a matched frame, against a random pair's; lower is surer. */
        val costRatio: Double,
        /** Share of the matched path's steps that move on in both at once. */
        val straightShare: Double,
        /**
         * The last record moment that found its match, ms. With only part of
         * the video to go on, what comes after it is the [map] carried on at
         * its pace, not measured.
         */
        val coveredUntilMs: Long,
    )

    /**
     * Aligns [record] to [video]. Null when either is too short or the two
     * don't convincingly hold the same song.
     *
     * [videoComplete] false says [video] is only the opening of the video —
     * what has reached the disk while it plays. The record may then run on past
     * the end of it at no charge, and only the part of the record the video
     * reached is held to the match thresholds.
     */
    fun align(record: Features, video: Features, videoComplete: Boolean = true): Result? {
        if (record.frames < MIN_FRAMES || video.frames < MIN_FRAMES) return refuse("too short: ${record.frames}/${video.frames} frames")
        val sounding = (0 until record.frames).count { !record.silent[it] }
        if (sounding < MIN_FRAMES / 2) return refuse("record nearly silent")

        // What a match costs by chance; every cost is measured against it.
        val baseline = randomPairCost(record.chroma, record, video)
        if (baseline < 0.02) return refuse("no contrast (baseline ${"%.3f".format(java.util.Locale.ROOT, baseline)})")
        val norm = (1.0 / baseline).toFloat()

        // Every key, coarsely (0.4 s steps): a cover in another key, or one
        // whose arrangement drops the chords' thirds, fools an overall pitch
        // profile into picking the wrong one, and twelve coarse passes cost
        // less than one fine one.
        //
        // Each is run twice — against the video, and against the video played
        // backwards. Backwards keeps every chord the video has and none of its
        // order: a different song "matches" about as well either way, since
        // all it is doing is finding chords the two share, while the same song
        // sung again matches forwards far better. Taking the best key on both
        // sides keeps the comparison fair (the best of twelve tries is always
        // a little lucky). It is the one test that held across every case
        // tried, where cost and shape alone did not: a noisy rearranged cover
        // can cost more per frame than a clean different song.
        val coarseVideo = coarse(video.chroma, video.frames, video.silent)
        val m = coarseVideo.frames
        val reversed = FloatArray(m * 12)
        for (f in 0 until m) coarseVideo.chroma.copyInto(reversed, f * 12, (m - 1 - f) * 12, (m - f) * 12)
        val coarseOpenEnd = if (videoComplete) -1 else max(1, END_SLACK / COARSE)
        var bestKey = 0
        var bestCoarse: Path? = null
        var bestChance = Double.MAX_VALUE
        for (k in 0 until 12) {
            val coarseRecord = coarse(rotate(record.chroma, record.frames, k), record.frames, record.silent)
            val n = coarseRecord.frames
            val path = warp(
                coarseRecord.chroma, n, coarseVideo.chroma, m, IntArray(n) { 0 }, IntArray(n) { m - 1 },
                openEnd = coarseOpenEnd, norm = norm,
            ) ?: continue
            if (bestCoarse == null || path.total < bestCoarse.total) {
                bestCoarse = path
                bestKey = k
            }
            warp(
                coarseRecord.chroma, n, reversed, m, IntArray(n) { 0 }, IntArray(n) { m - 1 },
                openEnd = coarseOpenEnd, norm = norm, keepPath = false,
            )?.let { bestChance = minOf(bestChance, it.total) }
        }
        val coarsePath = bestCoarse ?: return refuse("no coarse path")
        val significance = if (bestChance <= 0.0 || bestChance == Double.MAX_VALUE) 1.0 else coarsePath.total / bestChance

        // Fine pass around the coarse path.
        val recordChroma = rotate(record.chroma, record.frames, bestKey)
        val (lo, hi) = band(coarsePath, record.frames, video.frames)
        val fine = warp(
            recordChroma, record.frames, video.chroma, video.frames, lo, hi,
            openEnd = if (videoComplete) -1 else END_SLACK,
            norm = norm,
        ) ?: return refuse("no fine path")

        // Per record frame: where the path enters it, whether it got there by
        // moving on in both at once, and what its cells cost.
        val n = record.frames
        val enter = IntArray(n) { -1 }
        val diagonalIn = BooleanArray(n)
        val rowCost = FloatArray(n)
        val rowCells = IntArray(n)
        var steps = 0
        var diagonal = 0
        // The path's ends can trail off along an edge — the record running on
        // past where a short version stops, the video's intro before the song
        // — which is leaving those parts out, not matching them. Its shape is
        // judged between its first and last step in step.
        var core = 1
        var coreEnd = fine.cells.size - 1
        fun diagonalAt(c: Int): Boolean {
            val i0 = (fine.cells[c - 1] ushr 32).toInt()
            val i1 = (fine.cells[c] ushr 32).toInt()
            val j0 = (fine.cells[c - 1] and 0xFFFF_FFFFL).toInt()
            val j1 = (fine.cells[c] and 0xFFFF_FFFFL).toInt()
            return i1 == i0 + 1 && j1 == j0 + 1
        }
        while (core < fine.cells.size && !diagonalAt(core)) core++
        while (coreEnd > core && !diagonalAt(coreEnd)) coreEnd--
        for (c in fine.cells.indices) {
            val row = (fine.cells[c] ushr 32).toInt()
            val j = (fine.cells[c] and 0xFFFF_FFFFL).toInt()
            if (enter[row] < 0) {
                enter[row] = j
                if (c > 0) {
                    val pi = (fine.cells[c - 1] ushr 32).toInt()
                    val pj = (fine.cells[c - 1] and 0xFFFF_FFFFL).toInt()
                    diagonalIn[row] = pi == row - 1 && pj == j - 1
                }
            }
            rowCost[row] += cost(recordChroma, row, video.chroma, j) * norm
            rowCells[row]++
            if (c in core..coreEnd && !record.silent[row]) {
                val pi = (fine.cells[c - 1] ushr 32).toInt()
                val pj = (fine.cells[c - 1] and 0xFFFF_FFFFL).toInt()
                steps++
                if (pi == row - 1 && pj == j - 1) diagonal++
            }
        }
        // Held to account only for the part of the record the video reached.
        val lastRow = if (videoComplete) n - 1 else (fine.cells.last() ushr 32).toInt()
        val reached = if (videoComplete) sounding else (0..lastRow).count { !record.silent[it] }
        val points = ArrayList<IntArray>()
        var onPath = 0
        var pathCost = 0.0
        val coreRows = if (core <= coreEnd && core < fine.cells.size) {
            (fine.cells[core - 1] ushr 32).toInt()..(fine.cells[coreEnd] ushr 32).toInt()
        } else {
            IntRange.EMPTY
        }
        for (i in 0 until n) {
            if (enter[i] < 0 || record.silent[i]) continue
            if (i !in coreRows) continue
            onPath++
            pathCost += rowCost[i] / rowCells[i]
            if (diagonalIn[i]) points += intArrayOf(i, enter[i])
        }
        val matchedShare = points.size.toDouble() / reached.coerceAtLeast(1)
        // The same song, sung again, moves through the two in step: the path
        // runs diagonally except where one take is a little slower, or the
        // video has something the record doesn't. A different song only
        // "matches" by stopping and starting to chase chords it shares, which
        // shows as a path that is mostly corners — even when, chord for
        // chord, it finds closer matches than a noisy cover of the right song
        // does. So the shape of the path is the main evidence, and the cost
        // only has to be clearly better than chance.
        val straight = if (steps == 0) 0.0 else diagonal.toDouble() / steps
        val ratio = if (onPath == 0) 1.0 else pathCost / onPath
        val figures = "matched ${(matchedShare * 100).roundToInt()}% straight ${(straight * 100).roundToInt()}% " +
            "cost ${"%.2f".format(java.util.Locale.ROOT, ratio)} " +
            "vs chance ${"%.2f".format(java.util.Locale.ROOT, significance)} key +$bestKey"
        // A short version (a TV-size cover, a clip of one chorus) only ever
        // holds part of the record. That is accepted too when what it holds
        // matches unmistakably: half a minute or more, almost all in step.
        val whole = matchedShare >= MIN_MATCHED && points.size >= MIN_FRAMES / 2 &&
            straight >= MIN_STRAIGHT && ratio <= MAX_COST_RATIO
        val part = matchedShare >= MIN_PART_MATCHED && points.size >= MIN_PART_FRAMES &&
            straight >= MIN_PART_STRAIGHT && ratio <= MAX_PART_COST_RATIO
        if (significance > MAX_CHANCE_RATIO || (!whole && !part)) return refuse(figures)

        // Straight line through the matched frames?
        val toMs = { frame: Int, f: Features -> (f.secondsAt(frame.toDouble()) * 1000.0).roundToLong() }
        val sample = if (points.size <= 240) points else List(240) { points[it * points.size / 240] }
        val pairs = sample.map { toMs(it[0], record) to toMs(it[1], video) }
        val line = straightLine(pairs)
        val result = if (line != null) {
            Result(
                map = if (line.first == 1.0) LyricsTimeMap.shift(line.second) else LyricsTimeMap.linear(line.first, line.second),
                rate = line.first,
                linear = true,
                matchPercent = (matchedShare * 100).roundToInt().coerceIn(0, 100),
                transpose = bestKey,
                costRatio = ratio,
                straightShare = straight,
                coveredUntilMs = toMs(points.last()[0], record),
            )
        } else {
            // Not one line: a cut, an insert, a tempo that wanders. Follow the
            // path itself, steadied over a second either way.
            val rate = overallRate(pairs)
            val src = ArrayList<Long>()
            val dst = ArrayList<Long>()
            val step = max(1, (1.0 / record.hopSeconds).roundToInt())
            var p = 0
            while (p < points.size) {
                val from = max(0, p - 5)
                val to = min(points.size - 1, p + 5)
                val js = (from..to).map { points[it][1] }.sorted()
                val j = js[js.size / 2]
                src += toMs(points[p][0], record)
                dst += toMs(j, video)
                p += step
            }
            val last = points.last()
            if (src.last() < toMs(last[0], record)) {
                src += toMs(last[0], record)
                dst += toMs(last[1], video)
            }
            // Carried on past the ends at the pace measured nearest each.
            val headRate = overallRate(pairs.filter { it.first <= pairs.first().first + EDGE_SPAN_MS }, rate)
            val tailRate = overallRate(pairs.filter { it.first >= pairs.last().first - EDGE_SPAN_MS }, rate)
            val map = LyricsTimeMap.through(src, dst, headRate, tailRate) ?: return refuse("no map")
            Result(
                map, rate, linear = false, (matchedShare * 100).roundToInt().coerceIn(0, 100), bestKey, ratio, straight,
                coveredUntilMs = toMs(last[0], record),
            )
        }
        lastVerdict = "accepted: $figures ${if (result.linear) "line" else "warp"} rate ${"%.3f".format(java.util.Locale.ROOT, result.rate)}"
        return result
    }

    /**
     * Why the last [align] came out the way it did, with its figures — for the
     * track log, since "no timing" otherwise says nothing about how close it was.
     */
    @Volatile
    var lastVerdict: String = ""
        private set

    private fun refuse(why: String): Result? {
        lastVerdict = "refused: $why"
        return null
    }

    // ---- The warp --------------------------------------------------------------

    /** A path through the grid: cells packed as (i shl 32) or j, start to end. */
    private class Path(val cells: LongArray, val total: Double)

    private fun cost(a: FloatArray, i: Int, b: FloatArray, j: Int): Float {
        val x = i * 12
        val y = j * 12
        var dot = 0f
        for (pc in 0 until 12) dot += a[x + pc] * b[y + pc]
        return (1f - dot).coerceIn(0f, 1f)
    }

    /**
     * Cheapest path through rows 0 until [n] (record) × columns [lo]..[hi]
     * (video, per row), open at both ends: it may start at any cell — paying
     * [SKIP_COST] for each record row it leaves out before it, and
     * [VIDEO_SKIP_COST] for each video column — and end at
     * any cell, paying the same for each row left out after. With [openEnd]
     * ≥ 0, ending within that many columns of the video's last costs nothing
     * however much record is left: the video simply hasn't got that far yet.
     */
    private fun warp(
        a: FloatArray,
        n: Int,
        b: FloatArray,
        m: Int,
        lo: IntArray,
        hi: IntArray,
        openEnd: Int = -1,
        norm: Float = 1f,
        /** False when only the total is wanted: no directions kept, no path traced. */
        keepPath: Boolean = true,
    ): Path? {
        if (n == 0 || m == 0) return null
        val rowStart = IntArray(n + 1)
        for (i in 0 until n) {
            val width = (hi[i] - lo[i] + 1).coerceAtLeast(0)
            rowStart[i + 1] = rowStart[i] + width
        }
        val from = ByteArray(if (keepPath) rowStart[n] else 0) // 0 start, 1 diagonal, 2 record step, 3 video step
        var prev = FloatArray(0)
        var prevLo = 0
        var prevHi = -1
        var bestTotal = Float.MAX_VALUE
        var bestI = -1
        var bestJ = -1
        for (i in 0 until n) {
            val l = lo[i]
            val h = hi[i]
            val row = FloatArray((h - l + 1).coerceAtLeast(0))
            val startCost = i * SKIP_COST
            val tail = (n - 1 - i) * SKIP_COST
            val freeFrom = if (openEnd >= 0) m - 1 - openEnd else Int.MAX_VALUE
            for (j in l..h) {
                val c = cost(a, i, b, j) * norm
                var best = startCost + j * VIDEO_SKIP_COST + c
                var dir: Byte = 0
                if (i > 0) {
                    if (j - 1 in prevLo..prevHi) {
                        val v = prev[j - 1 - prevLo] + c
                        if (v < best) { best = v; dir = 1 }
                    }
                    if (j in prevLo..prevHi) {
                        // The record moving on while the video holds still is
                        // the record's part being left out of the video: it
                        // costs at least what leaving a row out does, so a
                        // path doesn't drag on along the video's last frame
                        // where a short version has simply stopped.
                        val v = prev[j - prevLo] + maxOf(c + STEP_PENALTY, RECORD_STEP_FLOOR)
                        if (v < best) { best = v; dir = 2 }
                    }
                }
                if (j > l) {
                    val v = row[j - 1 - l] + c + STEP_PENALTY
                    if (v < best) { best = v; dir = 3 }
                }
                val total = best
                row[j - l] = total
                if (keepPath) from[rowStart[i] + j - l] = dir
                val end = if (j >= freeFrom) total else total + tail
                if (end < bestTotal) {
                    bestTotal = end
                    bestI = i
                    bestJ = j
                }
            }
            prev = row
            prevLo = l
            prevHi = h
        }
        if (bestI < 0) return null
        if (!keepPath) return Path(LongArray(0), bestTotal.toDouble())
        val cells = ArrayList<Long>()
        var i = bestI
        var j = bestJ
        while (true) {
            cells += (i.toLong() shl 32) or j.toLong()
            when (from[rowStart[i] + j - lo[i]].toInt()) {
                0 -> break
                1 -> { i--; j-- }
                2 -> i--
                else -> j--
            }
        }
        cells.reverse()
        return Path(cells.toLongArray(), bestTotal.toDouble())
    }

    /** Per fine row, the video columns within [BAND_RADIUS] of where the coarse path put it. */
    private fun band(coarse: Path, n: Int, m: Int): Pair<IntArray, IntArray> {
        val rows = (n + COARSE - 1) / COARSE
        val cLo = IntArray(rows) { Int.MAX_VALUE }
        val cHi = IntArray(rows) { Int.MIN_VALUE }
        for (cell in coarse.cells) {
            val i = (cell ushr 32).toInt()
            val j = (cell and 0xFFFF_FFFFL).toInt()
            if (i >= rows) continue
            cLo[i] = min(cLo[i], j)
            cHi[i] = max(cHi[i], j)
        }
        // Rows the coarse path skipped borrow from the nearest row it visited.
        val visited = (0 until rows).filter { cLo[it] != Int.MAX_VALUE }
        if (visited.isEmpty()) return IntArray(n) { 0 } to IntArray(n) { m - 1 }
        for (r in 0 until rows) {
            if (cLo[r] != Int.MAX_VALUE) continue
            val near = visited.minByOrNull { abs(it - r) }!!
            cLo[r] = cLo[near]
            cHi[r] = cHi[near]
        }
        val lo = IntArray(n)
        val hi = IntArray(n)
        for (i in 0 until n) {
            val r = i / COARSE
            var l = Int.MAX_VALUE
            var h = Int.MIN_VALUE
            for (q in max(0, r - 1)..min(rows - 1, r + 1)) {
                l = min(l, cLo[q] * COARSE)
                h = max(h, cHi[q] * COARSE + COARSE - 1)
            }
            lo[i] = (l - BAND_RADIUS).coerceIn(0, m - 1)
            hi[i] = (h + BAND_RADIUS).coerceIn(0, m - 1)
        }
        // A path only moves forward, so the band must too.
        for (i in n - 2 downTo 0) lo[i] = min(lo[i], lo[i + 1])
        for (i in 1 until n) hi[i] = max(hi[i], hi[i - 1])
        return lo to hi
    }

    // ---- Small helpers ---------------------------------------------------------------

    private class Coarse(val chroma: FloatArray, val frames: Int)

    private fun coarse(chroma: FloatArray, frames: Int, silent: BooleanArray): Coarse {
        val n = (frames + COARSE - 1) / COARSE
        val out = FloatArray(n * 12)
        for (i in 0 until n) {
            for (f in i * COARSE until min(frames, i * COARSE + COARSE)) {
                for (pc in 0 until 12) out[i * 12 + pc] += chroma[f * 12 + pc]
            }
            normalize(out, i * 12)
        }
        return Coarse(out, n)
    }

    private fun rotate(chroma: FloatArray, frames: Int, k: Int): FloatArray {
        if (k == 0) return chroma
        val out = FloatArray(frames * 12)
        for (f in 0 until frames) {
            for (pc in 0 until 12) out[f * 12 + (pc + k) % 12] = chroma[f * 12 + pc]
        }
        return out
    }

    /** What a match costs by chance: record and video frames paired at random. */
    private fun randomPairCost(recordChroma: FloatArray, record: Features, video: Features): Double {
        var seed = 0x9E3779B97F4A7C15uL
        fun next(bound: Int): Int {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed shr 7)
            seed = seed xor (seed shl 17)
            return ((seed shr 33).toLong() % bound).toInt()
        }
        var total = 0.0
        var count = 0
        repeat(4_000) {
            val i = next(record.frames)
            val j = next(video.frames)
            if (record.silent[i] || video.silent[j]) return@repeat
            total += cost(recordChroma, i, video.chroma, j)
            count++
        }
        return if (count == 0) 0.0 else total / count
    }

    /**
     * (rate, offsetMs) when nearly every point lies within 0.4 s of one line:
     * a Theil–Sen line through all of them, then least squares through the
     * ones on it — a slope off by 0.3% is most of a second by the last chorus.
     */
    private fun straightLine(points: List<Pair<Long, Long>>): Pair<Double, Long>? {
        val rough = overallRate(points, maxSpanMs = Long.MAX_VALUE)
        val roughOffset = TimingFit.median(points.map { (x, y) -> y - rough * x })
        val on = points.filter { (x, y) -> abs(y - (rough * x + roughOffset)) <= 400 }
        if (on.size * 100 < points.size * 90 || on.size < 2) return null
        val mx = on.sumOf { it.first.toDouble() } / on.size
        val my = on.sumOf { it.second.toDouble() } / on.size
        var sxy = 0.0
        var sxx = 0.0
        for ((x, y) in on) {
            sxy += (x - mx) * (y - my)
            sxx += (x - mx) * (x - mx)
        }
        val fitted = if (sxx > 0) (sxy / sxx).coerceIn(TimingFit.MIN_RATE, TimingFit.MAX_RATE) else rough
        val rate = if (abs(fitted - 1.0) < 0.003) 1.0 else fitted
        val offset = TimingFit.median(on.map { (x, y) -> y - rate * x }).roundToLong()
        val inside = points.count { (x, y) -> abs(y - (rate * x + offset)) <= 400 }
        return if (inside * 100 >= points.size * 90) rate to offset else null
    }

    /**
     * Theil–Sen pace over [points], kept within what a real cover runs at.
     *
     * Only pairs at least 8 seconds apart vote (closer ones are all jitter),
     * and by default at most [maxSpanMs]: far pairs straddle whatever cut or
     * insert the video has — a 25 s chat in the middle would otherwise read
     * as the whole song running 20% slow. [fallback] when nothing qualifies.
     */
    private fun overallRate(
        points: List<Pair<Long, Long>>,
        fallback: Double = 1.0,
        maxSpanMs: Long = 40_000L,
    ): Double {
        val slopes = ArrayList<Double>()
        val stride = max(1, points.size / 120)
        for (i in points.indices step stride) {
            for (j in i + 1 until points.size step stride) {
                val dx = points[j].first - points[i].first
                if (dx < 8_000) continue
                if (dx > maxSpanMs) break
                slopes += (points[j].second - points[i].second).toDouble() / dx
            }
        }
        if (slopes.isEmpty()) return fallback
        val rate = TimingFit.median(slopes).coerceIn(TimingFit.MIN_RATE, TimingFit.MAX_RATE)
        return if (abs(rate - 1.0) < 0.003) 1.0 else rate
    }

    private fun normalize(v: FloatArray, at: Int) {
        var s = 0f
        for (pc in 0 until 12) s += v[at + pc] * v[at + pc]
        if (s <= 0f) {
            val u = (1.0 / sqrt(12.0)).toFloat()
            for (pc in 0 until 12) v[at + pc] = u
            return
        }
        val inv = 1f / sqrt(s)
        for (pc in 0 until 12) v[at + pc] *= inv
    }

    /** Windowed-sinc low-pass, [cutoff] as a fraction of the sample rate. */
    private fun lowPass(length: Int, cutoff: Double): FloatArray {
        val mid = (length - 1) / 2.0
        val taps = DoubleArray(length) { k ->
            val x = k - mid
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            val w = 0.54 - 0.46 * cos(2 * PI * k / (length - 1))
            sinc * w
        }
        val sum = taps.sum()
        return FloatArray(length) { (taps[it] / sum).toFloat() }
    }

    /** In-place radix-2 FFT; size must be a power of two. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val table = twiddles(len)
            for (start in 0 until n step len) {
                for (k in 0 until half) {
                    val wr = table[2 * k]
                    val wi = table[2 * k + 1]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
            }
            len = len shl 1
        }
    }

    private val twiddleCache = HashMap<Int, DoubleArray>()

    private fun twiddles(len: Int): DoubleArray = synchronized(twiddleCache) {
        twiddleCache.getOrPut(len) {
            val half = len / 2
            DoubleArray(len) { idx ->
                val k = idx / 2
                val angle = -2.0 * PI * k / len
                if (idx % 2 == 0) cos(angle) else sin(angle)
            }.copyOf(half * 2)
        }
    }

}
