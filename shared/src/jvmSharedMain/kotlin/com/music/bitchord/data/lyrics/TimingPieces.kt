package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A sheet placed on a recording in stretches, for a song whose structure is not
 * the sheet's: a verse left out, a break put in, a long intro, a cover.
 *
 * [TimingFit] draws one straight line through every line [LyricsWordAlign]
 * placed, weak ones included, and gives up when less than half lie on it. With a
 * whole song's words that throws away what is known: most lines sit exactly on
 * their own stretch's clock (11-06 corpus of 192 songs: 29 refused or misplaced
 * by the one line, such as a verse 7.5 s off, or a sheet a minute early. Scored
 * by scripts/synctest.py the 29 went from 19 FAIL to none, and none got worse).
 *
 * Here only lines the alignment really matched are believed ([MIN_SHARE],
 * [MIN_LETTERS]). The pace is read off neighbouring lines first. Walking the
 * lines in sheet order on that pace, a line that disagrees with the stretch so
 * far either starts a new stretch, when [MIN_LINES] of the next [LOOK_AHEAD]
 * agree with it, or is left out. One pace is fitted across all stretches, since
 * pace belongs to the recording and a cut does not change it, and each stretch
 * keeps its own offset. Between stretches the clock changes once, in the middle
 * of the gap, so a line nobody heard takes the clock of the stretch it sits
 * nearer and never a time blended from two verses.
 *
 * The guards and what they rest on. 2,115 wrong pairs of real sheets and real
 * transcripts, same language and other (735 whole songs, 651 of 40 s and 729 of
 * 90 s), gave no accepted answer:
 *  - [MIN_LINES] lines in every stretch; a shorter one is dropped, not guessed.
 *    All but 47 of the whole-song wrong pairs stop here or before.
 *  - [MIN_COVER] of the sheet in stretches. The best wrong pair held 0.11, and
 *    0.25 applied one real song 50 s wrong.
 *  - The rest never decided a real or a wrong sheet in that corpus and stay as
 *    limits on what a recording can do. [MAX_PIECES]: a cut or two (real at most
 *    4). [TimingFit.MIN_RATE]..[TimingFit.MAX_RATE] for the pace overall and in
 *    each stretch of [PIECE_PACE_SPAN_MS] or more (real 0.83 to 1.19).
 *    [MAX_JUMP_MS] at a boundary: a verse and a chorus, or a video's interlude
 *    (real at most 35 s applied).
 *  - [MAX_MAD_MS] inside a stretch: lines on one clock agree closely (real 350
 *    at most, 76 typically).
 *  - The order must hold: a stretch may not begin more than [TOLERANCE_MS] before
 *    the last one ended. A stretch that leaves and returns to the same clock is a
 *    repeated chorus read as the other one, and is dropped (a recording does not
 *    cut away and come back to the same offset).
 *
 * Whoever applies a [Result] still holds it to the AI answer's own guards, and
 * [overrules] keeps a straight line that already explains its lines: most songs
 * do not move at all.
 */
object TimingPieces {

    /** A line counts as heard when this much of its letters were matched. */
    const val MIN_SHARE = 0.6

    /** Fewer letters than this can be matched by chance. */
    const val MIN_LETTERS = 4

    /** Lines further than this from their stretch's clock are not on it. */
    const val TOLERANCE_MS = 1_000L

    /** How many lines, this one included, are read when one disagrees. */
    const val LOOK_AHEAD = 6

    /** The fewest lines a stretch may rest on. */
    const val MIN_LINES = 4

    /** The share of the sheet that must lie in stretches. */
    const val MIN_COVER = 1.0 / 3.0

    const val MAX_PIECES = 4
    const val MAX_JUMP_MS = 120_000L
    const val MAX_MAD_MS = 400L
    const val PIECE_PACE_SPAN_MS = 20_000L

    /** Overruling a line that passed: each stretch at least this long... */
    const val OVERRULE_LINES = 6

    /** ...the line missing this many of the heard lines... */
    const val OVERRULE_MISSED = 6

    /** ...by more than this... */
    const val OVERRULE_FAR_MS = 700L

    /** A line whose opening is matched and how much of it, as [LyricsWordAlign.Placement] says. */
    data class Placed(val timeMs: Long, val startMs: Long?, val share: Double, val letters: Int)

    /** The stretches found: record ms → recording ms. */
    class Result(
        val map: LyricsTimeMap,
        /** Recording seconds per sheet second, one for all stretches. */
        val rate: Double,
        /** Lines in each stretch. */
        val sizes: List<Int>,
        /** How far the clock moves at each boundary, ms. */
        val jumpsMs: List<Long>,
        /** Lines in stretches. */
        val used: Int,
        /** Lines the alignment really matched. */
        val heard: Int,
        internal val heardPoints: List<Point>,
    ) {
        /** Of the lines really heard, how many follow the stretches. */
        val agreePercent: Int get() = (used * 100.0 / heard).roundToInt().coerceIn(0, 100)
    }

    /** [result], or why there is none, for the log. */
    class Outcome(val result: Result?, val refused: String?)

    internal class Point(val x: Long, val y: Long)

    /** [fit] for lines placed by [LyricsWordAlign.placeLinesDetailed]. */
    fun fit(
        timesMs: List<Long>,
        texts: List<String>,
        placement: LyricsWordAlign.Placement,
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
            spellable,
        )
    }

    fun fit(lines: List<Placed>, spellable: Int = Int.MAX_VALUE): Outcome {
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
        val offsets = pieces.map { offsetOf(it, rate) }
        val used = pieces.sumOf { it.size }
        val base = spellable.takeIf { it >= MIN_LINES }?.coerceAtMost(lettered) ?: lettered
        refusal(pieces, offsets, rate, used, base)?.let { return Outcome(null, it) }
        val map = chain(pieces, offsets, rate) ?: return Outcome(null, "no-map")
        return Outcome(
            Result(
                map = map,
                rate = rate,
                sizes = pieces.map { it.size },
                jumpsMs = (0 until offsets.size - 1).map { (offsets[it + 1] - offsets[it]).roundToLong() },
                used = used,
                heard = heard.size,
                heardPoints = heard,
            ),
            null,
        )
    }

    /**
     * Whether [result] should replace a straight line that passed its own
     * guards. Only for a real structure, every stretch long enough, when the line
     * leaves heard lines far from where they were sung and the stretches do not.
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
        if (base <= 0 || used.toDouble() / base < MIN_COVER) return "cover"
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

    /**
     * The pace read off neighbouring heard lines, which is the same on both sides
     * of a cut. A sped-up cover moves 0.7 s a line against the sheet (11-06
     * A4HLeFXyFEY, 0.87), which would otherwise read as a cut at every line.
     */
    private fun firstPace(heard: List<Point>): Double {
        val slopes = ArrayList<Double>()
        for (k in 1 until heard.size) {
            for (j in maxOf(0, k - 3) until k) {
                val dx = heard[k].x - heard[j].x
                if (dx >= MIN_PAIR_SPAN_MS) slopes += (heard[k].y - heard[j].y).toDouble() / dx
            }
        }
        if (slopes.size < MIN_LINES) return 1.0
        return TimingFit.rateForSpan(TimingFit.median(slopes), heard.last().x - heard.first().x)
    }

    private fun segment(heard: List<Point>): List<List<Point>> {
        val pace = firstPace(heard)
        fun off(point: Point): Double = (point.y - (pace * point.x).roundToLong()).toDouble()
        fun lastOffset(piece: List<Point>): Double = TimingFit.median(piece.takeLast(5).map { off(it) })
        fun firstOffset(piece: List<Point>): Double = TimingFit.median(piece.take(5).map { off(it) })
        fun wholeOffset(piece: List<Point>): Double = TimingFit.median(piece.map { off(it) })
        val found = ArrayList<List<Point>>()
        var current = ArrayList<Point>()
        heard.forEachIndexed { k, point ->
            if (current.isEmpty()) {
                current.add(point)
                return@forEachIndexed
            }
            val offset = off(point)
            val recent = lastOffset(current)
            if (abs(offset - recent) <= TOLERANCE_MS) {
                current.add(point)
                return@forEachIndexed
            }
            val ahead = heard.subList(k, minOf(heard.size, k + LOOK_AHEAD))
            val withThis = ahead.count { abs(off(it) - offset) <= TOLERANCE_MS }
            val withStretch = ahead.count { abs(off(it) - recent) <= TOLERANCE_MS }
            if (withThis >= MIN_LINES && withThis > withStretch) {
                found.add(current)
                current = arrayListOf(point)
            }
            // Otherwise one line the alignment put wrong: left out.
        }
        if (current.isNotEmpty()) found.add(current)
        val kept = found.filter { it.size >= MIN_LINES }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            // Neighbours on one clock are one stretch with a bad patch in it.
            for (k in 0 until kept.size - 1) {
                if (abs(lastOffset(kept[k]) - firstOffset(kept[k + 1])) <= TOLERANCE_MS) {
                    kept[k] = kept[k] + kept[k + 1]
                    kept.removeAt(k + 1)
                    changed = true
                    break
                }
            }
            if (changed) continue
            // There and back: a repeated chorus read as the other one.
            for (k in 1 until kept.size - 1) {
                if (abs(wholeOffset(kept[k - 1]) - wholeOffset(kept[k + 1])) <= TOLERANCE_MS &&
                    kept[k].size < minOf(kept[k - 1].size, kept[k + 1].size)
                ) {
                    kept.removeAt(k)
                    changed = true
                    break
                }
            }
        }
        return kept
    }

    /** The median slope of every pair of lines inside a stretch, a pair across a cut says nothing. */
    private fun pooledRate(pieces: List<List<Point>>): Double {
        val slopes = ArrayList<Double>()
        for (piece in pieces) {
            for (i in piece.indices) {
                for (j in i + 1 until piece.size) {
                    val dx = piece[j].x - piece[i].x
                    if (dx >= MIN_PAIR_SPAN_MS) slopes += (piece[j].y - piece[i].y).toDouble() / dx
                }
            }
        }
        if (slopes.isEmpty()) return 1.0
        return TimingFit.rateForSpan(TimingFit.median(slopes), pieces.sumOf { it.last().x - it.first().x })
    }

    /**
     * Knots for the chain. Where the recording has more than the sheet, the clock
     * steps once at the middle of the gap. Where the sheet has more than the
     * recording, a flat stretch there skips what is not sung: those lines start
     * together and show for no time, and a monotone map has no better answer.
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
            val over = (rate * cut + a) - (rate * cut + b)
            if (over <= 0) {
                push(cut, rate * cut + a)
                push(cut + 1, rate * (cut + 1) + b)
            } else {
                val width = over / rate
                var low = maxOf(from + 1, cut - width / 2)
                val high = minOf(to - 1, low + width)
                low = maxOf(from + 1, high - width)
                push(low, rate * low + a)
                push(high, rate * high + b)
            }
        }
        return LyricsTimeMap.through(src, dst, rate, rate)
    }

    private const val MIN_PAIR_SPAN_MS = 4_000L
}
