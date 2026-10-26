package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Where a moment of the record falls in a video: record ms → video ms.
 *
 * A straight line (a shift, or a shift and a different pace) or, for a video
 * with a cut or a stretch in it, a chain of straight pieces through [srcMs] →
 * [dstMs] knots. Before the first knot the line carries on at [headRate], and
 * after the last at [tailRate] — the pace measured nearest each end — so an
 * answer measured over most of the song still places the lines either side
 * of it.
 *
 * Always non-decreasing: lyrics never run backwards.
 */
class LyricsTimeMap private constructor(
    private val srcMs: LongArray,
    private val dstMs: LongArray,
    /** Pace before the first knot: video ms per record ms. */
    val headRate: Double,
    /** Pace after the last knot. */
    val tailRate: Double,
) {
    val knots: Int get() = srcMs.size

    /** The video moment for record moment [ms]. */
    fun map(ms: Long): Long {
        val n = srcMs.size
        if (ms <= srcMs[0]) return dstMs[0] + ((ms - srcMs[0]) * headRate).roundToLong()
        if (ms >= srcMs[n - 1]) return dstMs[n - 1] + ((ms - srcMs[n - 1]) * tailRate).roundToLong()
        // Last knot at or before [ms].
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (srcMs[mid] <= ms) lo = mid else hi = mid
        }
        val span = srcMs[hi] - srcMs[lo]
        if (span <= 0L) return dstMs[hi]
        val t = (ms - srcMs[lo]).toDouble() / span
        return dstMs[lo] + ((dstMs[hi] - dstMs[lo]) * t).roundToLong()
    }

    /** The knots, flattened [src0, dst0, src1, dst1, …], for keeping on disk. */
    fun flatten(): List<Long> = buildList(srcMs.size * 2) {
        for (i in srcMs.indices) {
            add(srcMs[i])
            add(dstMs[i])
        }
    }

    override fun equals(other: Any?): Boolean = other is LyricsTimeMap &&
        srcMs.contentEquals(other.srcMs) && dstMs.contentEquals(other.dstMs) &&
        headRate == other.headRate && tailRate == other.tailRate

    override fun hashCode(): Int =
        ((srcMs.contentHashCode() * 31 + dstMs.contentHashCode()) * 31 + headRate.hashCode()) * 31 + tailRate.hashCode()

    override fun toString(): String = "LyricsTimeMap(knots=$knots, head=$headRate, tail=$tailRate)"

    companion object {
        /** Every moment moved by [ms]. */
        fun shift(ms: Long): LyricsTimeMap = LyricsTimeMap(longArrayOf(0L), longArrayOf(ms), 1.0, 1.0)

        /** `video = rate × record + offsetMs`. */
        fun linear(rate: Double, offsetMs: Long): LyricsTimeMap {
            require(rate > 0.0 && rate.isFinite()) { "rate must be positive" }
            return LyricsTimeMap(longArrayOf(0L), longArrayOf(offsetMs), rate, rate)
        }

        /**
         * Through the given knots. Knots out of order, or one that would make
         * the map run backwards, are dropped; null when none are left.
         */
        fun through(src: List<Long>, dst: List<Long>, headRate: Double, tailRate: Double = headRate): LyricsTimeMap? {
            if (src.size != dst.size || src.isEmpty()) return null
            if (!(headRate > 0.0) || !headRate.isFinite() || !(tailRate > 0.0) || !tailRate.isFinite()) return null
            val s = ArrayList<Long>(src.size)
            val d = ArrayList<Long>(src.size)
            for (i in src.indices) {
                val a = src[i]
                val b = dst[i]
                if (s.isNotEmpty() && (a <= s.last() || b < d.last())) continue
                s += a
                d += b
            }
            if (s.isEmpty()) return null
            return LyricsTimeMap(s.toLongArray(), d.toLongArray(), headRate, tailRate)
        }

        /** The inverse of [flatten]. */
        fun unflatten(flat: List<Long>, headRate: Double, tailRate: Double): LyricsTimeMap? {
            if (flat.isEmpty() || flat.size % 2 != 0) return null
            val src = ArrayList<Long>(flat.size / 2)
            val dst = ArrayList<Long>(flat.size / 2)
            for (i in flat.indices step 2) {
                src += flat[i]
                dst += flat[i + 1]
            }
            return through(src, dst, headRate, tailRate)
        }
    }
}

/**
 * [lines] moved onto the video's clock by [map], then by [extraMs] on top (a
 * listener's own correction). Every time a line carries — its start, its
 * words and syllables, its stated end, its answering vocal — goes through the
 * same map, so a video on a different pace stretches the words with the line
 * instead of only moving where it begins.
 */
fun List<LyricLine>.timedBy(map: LyricsTimeMap?, extraMs: Long = 0L): List<LyricLine> {
    if (map == null && extraMs == 0L) return this
    val at: (Long) -> Long = if (map == null) {
        { ms -> (ms + extraMs).coerceAtLeast(0L) }
    } else {
        { ms -> (map.map(ms) + extraMs).coerceAtLeast(0L) }
    }
    return map { it.retimed(at) }
}

/** [lines] retimed by [at], for a correction that is not a straight map (a capped refine). */
fun List<LyricLine>.retimedBy(at: (Long) -> Long): List<LyricLine> = map { it.retimed(at) }

private fun LyricLine.retimed(at: (Long) -> Long): LyricLine = copy(
    timeMs = at(timeMs),
    words = words.map { it.retimed(at) },
    sungUntilMs = sungUntilMs?.let(at),
    background = background?.retimed(at),
    timingSource = timingSource?.retimed(at),
)

private fun LyricWord.retimed(at: (Long) -> Long): LyricWord {
    val start = at(startMs)
    return copy(
        startMs = start,
        endMs = maxOf(start, at(endMs)),
        syllables = syllables.map {
            val s = at(it.startMs)
            it.copy(startMs = s, endMs = maxOf(s, at(it.endMs)))
        },
    )
}

/**
 * Fitting an AI's "this line starts at second N" answers to a straight line.
 *
 * Gemini hears the video and names a second for each line. Those seconds are
 * right more often than not, but not reliably: some are guessed, some are a
 * line early or late, and on a long video the later ones tend to drift. What a
 * real video can actually differ from the record by is far simpler than what
 * the answers can say — it starts earlier or later, and it may run a little
 * faster or slower — so the answers are read as evidence for that line, not
 * taken one by one:
 *
 *  1. Theil–Sen: the slope is the median of the slopes between every pair of
 *     answers far enough apart to have one, and the offset the median of what
 *     is left. Half the answers can be wrong and the line still comes out of
 *     the right half.
 *  2. Answers further than [INLIER_MS] from that line are set aside; the line
 *     is fitted again on the rest.
 *  3. When more answers are set aside than kept, there is no line the answers
 *     agree on, and nothing is applied — better the record's timing than a
 *     confident wrong one.
 *  4. A pace within [SNAP_RATE] of the record's is the record's: a lyric
 *     drifting by a tenth of a second over four minutes is measuring noise,
 *     not a slower video.
 */
object TimingFit {

    /** Result of [fit]: `video = rate × record + offsetMs` for the lines that agreed. */
    data class Line(
        val rate: Double,
        val offsetMs: Long,
        /** How many answers agree with the line. */
        val inliers: Int,
        /** How many answers there were. */
        val answered: Int,
    ) {
        val map: LyricsTimeMap get() = LyricsTimeMap.linear(rate, offsetMs)
    }

    /** An answer this far off the fitted line disagrees with it. */
    const val INLIER_MS = 1_500L

    /** Paces closer than this to 1 are taken as exactly the record's. */
    const val SNAP_RATE = 0.006

    /** Paces beyond these are not a cover of the same song; they're a wrong answer. */
    const val MIN_RATE = 0.75
    const val MAX_RATE = 1.35

    /** Pairs of answers closer than this (record time) don't say anything about pace. */
    private const val MIN_PAIR_SPAN_MS = 4_000L

    /**
     * Fits [points] — (record ms, video ms) — and returns the line, or null
     * when fewer than [minPoints] answers exist, the pace is implausible, or
     * fewer than half of the answers lie on the line.
     */
    fun fit(points: List<Pair<Long, Long>>, minPoints: Int = 3): Line? {
        if (points.size < minPoints) return null
        val first = theilSen(points) ?: return null
        val inliers = points.filter { (x, y) -> abs(y - first.predict(x)) <= INLIER_MS }
        if (inliers.size * 2 < points.size || inliers.size < minPoints) return null
        val refined = theilSen(inliers) ?: first
        val rate = refined.rate
        if (rate < MIN_RATE || rate > MAX_RATE) return null
        val snapped = abs(rate - 1.0) < SNAP_RATE
        val finalRate = if (snapped) 1.0 else rate
        val offset = median(inliers.map { (x, y) -> (y - finalRate * x) })
        val agreeing = points.count { (x, y) -> abs(y - (finalRate * x + offset)) <= INLIER_MS }
        if (agreeing * 2 < points.size) return null
        return Line(finalRate, offset.roundToLong(), agreeing, points.size)
    }

    private class Estimate(val rate: Double, val offset: Double) {
        fun predict(x: Long): Double = rate * x + offset
    }

    private fun theilSen(points: List<Pair<Long, Long>>): Estimate? {
        if (points.isEmpty()) return null
        val slopes = ArrayList<Double>(points.size * (points.size - 1) / 2)
        for (i in points.indices) {
            val (xi, yi) = points[i]
            for (j in i + 1 until points.size) {
                val (xj, yj) = points[j]
                val dx = xj - xi
                if (abs(dx) < MIN_PAIR_SPAN_MS) continue
                slopes += (yj - yi).toDouble() / dx
            }
        }
        // Not enough spread to say anything about pace: a plain shift.
        val rate = if (slopes.isEmpty()) 1.0 else median(slopes)
        if (!rate.isFinite() || rate <= 0.0) return null
        val offset = median(points.map { (x, y) -> y - rate * x })
        return Estimate(rate, offset)
    }

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
