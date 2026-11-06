package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A sheet placed on a recording in stretches, for a song whose structure is not
 * the sheet's: a verse left out, a break put in, a long intro, a cover. [TimingFit]'s
 * one line through every placed line refused or misplaced 29 of 192 real songs
 * (11-06) whose lines mostly sat exactly on their own stretch's clock.
 *
 * Only lines the alignment really matched count. One pace fits all stretches, since
 * a cut does not change a recording's pace, and each stretch keeps its own offset.
 * The clock changes once, in the middle of a gap, so a line nobody heard takes its
 * nearer stretch's clock and never a time blended from two verses.
 *
 * Guards, measured on 2,115 wrong pairs of real sheets and transcripts (none got a
 * map): [MIN_LINES] and [MIN_COVER] decide them (the best wrong pair held 0.11 of its
 * sheet; 0.25 moved one real song 50 s wrong). Pieces, pace, jump, scatter and order
 * decided nothing there and stay as limits on what a recording can do.
 *
 * A right sheet can still be read wrong: when Whisper misses the middle of a song, the
 * last chorus it heard lands on an earlier repeat in the sheet (5bIWIoFDv80, +34.9 s,
 * 22 lines past the last word). Nothing after such a stretch can show it up, so lines
 * may not be pushed past the last word ([PAST_HEARD_MS]), and a move over
 * [LyricsAiGuard.SONG_MAX_SHIFT_MS] needs [BIG_MOVE_COVER] of the sheet (true large
 * moves held 0.69 and up, that song 0.48).
 */
object TimingPieces {

    const val MIN_SHARE = 0.6
    const val MIN_LETTERS = 4
    const val MAX_JUMP_MS = 120_000L

    private const val TOLERANCE_MS = 1_000L
    private const val LOOK_AHEAD = 6
    private const val MIN_LINES = 4
    private const val MIN_COVER = 1.0 / 3.0
    private const val MAX_PIECES = 4
    private const val MAX_MAD_MS = 400L
    private const val PIECE_PACE_SPAN_MS = 20_000L
    private const val OVERRULE_LINES = 6
    private const val OVERRULE_MISSED = 6
    private const val OVERRULE_FAR_MS = 700L
    private const val MIN_PAIR_SPAN_MS = 4_000L
    private const val PAST_HEARD_MS = 10_000L
    private const val BIG_MOVE_COVER = 0.6

    /** A line as [LyricsWordAlign.Placement] says it was placed. */
    data class Placed(val timeMs: Long, val startMs: Long?, val share: Double, val letters: Int)

    internal class Point(val x: Long, val y: Long)

    class Result internal constructor(
        val map: LyricsTimeMap,
        /** Recording seconds per sheet second. */
        val rate: Double,
        /** Lines in each stretch. */
        val sizes: List<Int>,
        /** How far the clock moves at each boundary. */
        val jumpsMs: List<Long>,
        /** Of the lines really heard, how many follow the stretches: not of the sheet. */
        val agreePercent: Int,
        /** The move of the line moved furthest, signed: what a limit on moving must read. */
        val largestMoveMs: Long,
        internal val heardPoints: List<Point>,
    )

    /** [result], or why there is none, for the log. */
    class Outcome(val result: Result?, val refused: String?)

    fun fit(
        timesMs: List<Long>,
        texts: List<String>,
        placement: LyricsWordAlign.Placement,
        /** The end of the last word heard. */
        lastHeardMs: Long,
        /** Lines the transcript could spell at all, as `Deepgram.spellableLines`. */
        spellable: Int = texts.size,
    ): Outcome {
        if (!placement.attempted || timesMs.size != texts.size || placement.starts.size != texts.size) {
            return Outcome(null, "not-aligned")
        }
        return fit(
            texts.indices.map { i ->
                Placed(timesMs[i], placement.starts[i], placement.matchedShare.getOrElse(i) { 0.0 }, LyricsWordAlign.fold(texts[i]).length)
            },
            lastHeardMs,
            spellable,
        )
    }

    fun fit(lines: List<Placed>, lastHeardMs: Long = Long.MAX_VALUE, spellable: Int = Int.MAX_VALUE): Outcome {
        val lettered = lines.count { it.letters > 0 }
        val heard = lines.mapNotNull { line ->
            line.startMs?.takeIf { line.share >= MIN_SHARE && line.letters >= MIN_LETTERS }?.let { Point(line.timeMs, it) }
        }
        if (heard.size < MIN_LINES) return Outcome(null, "few-heard")
        var pieces = segment(heard)
        if (pieces.isEmpty()) return Outcome(null, "no-stretch")
        var rate = pooledRate(pieces)
        // On the one pace, two stretches may turn out to be one clock.
        while (pieces.size > 1) {
            val offsets = pieces.map { offsetOf(it, rate) }
            val at = (0 until offsets.size - 1).firstOrNull { abs(offsets[it + 1] - offsets[it]) <= TOLERANCE_MS } ?: break
            pieces = pieces.take(at) + listOf(pieces[at] + pieces[at + 1]) + pieces.drop(at + 2)
            rate = pooledRate(pieces)
        }
        // A last stretch pushing a line past the last word is a chorus read as an earlier repeat.
        while (true) {
            val last = pieces.last()
            val offset = offsetOf(last, rate)
            val past = lines.any {
                it.letters > 0 && it.timeMs >= last.first().x &&
                    rate * it.timeMs + offset - PAST_HEARD_MS > maxOf(it.timeMs, lastHeardMs)
            }
            if (!past) break
            if (pieces.size == 1) return Outcome(null, "past-end")
            pieces = pieces.dropLast(1)
            rate = pooledRate(pieces)
        }
        val offsets = pieces.map { offsetOf(it, rate) }
        val used = pieces.sumOf { it.size }
        val base = spellable.takeIf { it >= MIN_LINES }?.coerceAtMost(lettered) ?: lettered
        refusal(pieces, offsets, rate, used, base)?.let { return Outcome(null, it) }
        val map = chain(pieces, offsets, rate) ?: return Outcome(null, "no-map")
        val jumps = (0 until offsets.size - 1).map { (offsets[it + 1] - offsets[it]).roundToLong() }
        val largest = lines.filter { it.letters > 0 }.map { map.map(it.timeMs) - it.timeMs }.maxBy { abs(it) }
        val biggest = maxOf(abs(largest), jumps.maxOfOrNull { abs(it) } ?: 0L)
        if (biggest > LyricsAiGuard.SONG_MAX_SHIFT_MS && used.toDouble() / base < BIG_MOVE_COVER) {
            return Outcome(null, "thin")
        }
        return Outcome(
            Result(
                map = map,
                rate = rate,
                sizes = pieces.map { it.size },
                jumpsMs = jumps,
                agreePercent = (used * 100.0 / heard.size).roundToInt().coerceIn(0, 100),
                largestMoveMs = largest,
                heardPoints = heard,
            ),
            null,
        )
    }

    /**
     * Whether [result] should replace a straight line that passed its own guards:
     * real structure, every stretch long enough, and the line leaves heard lines far
     * from where they were sung while the stretches do not.
     */
    fun overrules(straight: LyricsTimeMap, result: Result): Boolean {
        if (result.sizes.size < 2 || result.sizes.min() < OVERRULE_LINES) return false
        val missedByLine = result.heardPoints.count { abs(straight.map(it.x) - it.y) > OVERRULE_FAR_MS }
        if (missedByLine < OVERRULE_MISSED) return false
        val missedByPieces = result.heardPoints.count { abs(result.map.map(it.x) - it.y) > OVERRULE_FAR_MS }
        return missedByPieces * 2 <= missedByLine
    }

    private fun refusal(
        pieces: List<List<Point>>,
        offsets: List<Double>,
        rate: Double,
        used: Int,
        base: Int,
    ): String? {
        val spreads = pieces.indices.map { k ->
            TimingFit.median(pieces[k].map { abs(it.y - rate * it.x - offsets[k]) })
        }
        // One small move on a wide scatter is not one move: [RefinePolicy]'s own numbers.
        if (pieces.size == 1 && abs(offsets[0]) < RefinePolicy.MAX_SHIFT_MS && spreads[0] > RefinePolicy.MAX_MAD_MS) {
            return "scatter-small"
        }
        if (used.toDouble() / base < MIN_COVER) return "cover"
        if (pieces.size > MAX_PIECES) return "too-many"
        if (rate < TimingFit.MIN_RATE || rate > TimingFit.MAX_RATE) return "pace"
        for (k in 0 until offsets.size - 1) {
            if (abs(offsets[k + 1] - offsets[k]) > MAX_JUMP_MS) return "jump"
        }
        if (spreads.any { it > MAX_MAD_MS }) return "scatter"
        for (piece in pieces) {
            if (piece.last().x - piece.first().x < PIECE_PACE_SPAN_MS) continue
            val own = pooledRate(listOf(piece))
            if (own < TimingFit.MIN_RATE || own > TimingFit.MAX_RATE) return "pace-in-stretch"
        }
        for (k in 0 until pieces.size - 1) {
            val ends = rate * pieces[k].last().x + offsets[k]
            val begins = rate * pieces[k + 1].first().x + offsets[k + 1]
            if (begins < ends - TOLERANCE_MS) return "order"
        }
        return null
    }

    private fun offsetOf(piece: List<Point>, rate: Double): Double = TimingFit.median(piece.map { it.y - rate * it.x })

    /** Slopes between each line and the next [reach] lines, far enough apart to have one. */
    private fun slopes(points: List<Point>, reach: Int): List<Double> {
        val found = ArrayList<Double>()
        for (i in points.indices) {
            for (j in i + 1..minOf(i + reach, points.size - 1)) {
                val dx = points[j].x - points[i].x
                if (dx >= MIN_PAIR_SPAN_MS) found += (points[j].y - points[i].y).toDouble() / dx
            }
        }
        return found
    }

    private fun segment(heard: List<Point>): List<List<Point>> {
        // Neighbouring lines' pace is the same on both sides of a cut; a sped-up cover
        // (A4HLeFXyFEY, 0.87) would otherwise read as a cut at every line.
        val near = slopes(heard, 3)
        val pace = if (near.size < MIN_LINES) 1.0 else TimingFit.rateForSpan(TimingFit.median(near), heard.last().x - heard.first().x)
        fun off(point: Point): Double = (point.y - (pace * point.x).roundToLong()).toDouble()
        fun offset(points: List<Point>): Double = TimingFit.median(points.map { off(it) })
        val found = ArrayList<List<Point>>()
        var current = arrayListOf(heard[0])
        for (k in 1 until heard.size) {
            val point = heard[k]
            val here = off(point)
            val recent = offset(current.takeLast(5))
            if (abs(here - recent) <= TOLERANCE_MS) {
                current.add(point)
                continue
            }
            val ahead = heard.subList(k, minOf(heard.size, k + LOOK_AHEAD))
            val withThis = ahead.count { abs(off(it) - here) <= TOLERANCE_MS }
            val withStretch = ahead.count { abs(off(it) - recent) <= TOLERANCE_MS }
            if (withThis >= MIN_LINES && withThis > withStretch) {
                found.add(current)
                current = arrayListOf(point)
            }
            // Otherwise one line the alignment put wrong: left out.
        }
        found.add(current)
        val kept = found.filter { it.size >= MIN_LINES }.toMutableList()
        while (true) {
            // Neighbours on one clock are one stretch with a bad patch in it.
            val join = (0 until kept.size - 1).firstOrNull {
                abs(offset(kept[it].takeLast(5)) - offset(kept[it + 1].take(5))) <= TOLERANCE_MS
            }
            if (join != null) {
                kept[join] = kept[join] + kept[join + 1]
                kept.removeAt(join + 1)
                continue
            }
            // There and back to one clock: a repeated chorus read as the other one, not a cut.
            val excursion = (1 until kept.size - 1).firstOrNull {
                abs(offset(kept[it - 1]) - offset(kept[it + 1])) <= TOLERANCE_MS &&
                    kept[it].size < minOf(kept[it - 1].size, kept[it + 1].size)
            } ?: break
            kept.removeAt(excursion)
        }
        return kept
    }

    private fun pooledRate(pieces: List<List<Point>>): Double {
        // Pairs inside a stretch only: a pair across a cut says nothing about pace.
        val inside = pieces.flatMap { slopes(it, it.size) }
        if (inside.isEmpty()) return 1.0
        return TimingFit.rateForSpan(TimingFit.median(inside), pieces.sumOf { it.last().x - it.first().x })
    }

    /**
     * Knots for the chain. Where the recording has more than the sheet, the clock
     * steps once at the middle of the gap. Where the sheet has more, a flat stretch
     * there skips what is not sung: those lines start together and show for no time.
     */
    private fun chain(pieces: List<List<Point>>, offsets: List<Double>, rate: Double): LyricsTimeMap? {
        val src = ArrayList<Long>()
        val dst = ArrayList<Long>()
        fun push(x: Double, y: Double) {
            val at = x.roundToLong()
            if (src.isNotEmpty() && at <= src.last()) return
            val to = y.roundToLong()
            src += at
            dst += if (dst.isEmpty()) to else maxOf(to, dst.last())
        }
        for (k in pieces.indices) {
            val a = offsets[k]
            push(pieces[k].first().x.toDouble(), rate * pieces[k].first().x + a)
            push(pieces[k].last().x.toDouble(), rate * pieces[k].last().x + a)
            if (k == pieces.size - 1) break
            val b = offsets[k + 1]
            val from = pieces[k].last().x.toDouble()
            val to = pieces[k + 1].first().x.toDouble()
            val cut = (from + to) / 2
            if (a <= b) {
                push(cut, rate * cut + a)
                push(cut + 1, rate * (cut + 1) + b)
            } else {
                val width = (a - b) / rate
                var low = maxOf(from + 1, cut - width / 2)
                val high = minOf(to - 1, low + width)
                low = maxOf(from + 1, high - width)
                push(low, rate * low + a)
                push(high, rate * high + b)
            }
        }
        return LyricsTimeMap.through(src, dst, rate, rate)
    }
}
