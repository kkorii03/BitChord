package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A residual correction for a single line, learned from the words the app
 * already heard for this song. The whole-song refine moves a constant offset
 * or a drift; this layer moves only the lines the transcript clearly heard in
 * a different place, and lets the lines between them follow.
 *
 * Pure, so the rule can be checked without Android, a transcript or the disk.
 * A line is an anchor only when at least [MIN_ANCHOR_WORDS] heard words matched
 * it in order and those words agree on its start to within
 * [MAX_ANCHOR_MAD_MS]. Its residual is the median of its words' times (each
 * stepped back by its offset inside the line) less the line's time after the
 * whole-song map, so one misheard word cannot set the move.
 */
object LyricsPerLine {

    /** A line needs this many heard words in order before it may move on its own. */
    const val MIN_ANCHOR_WORDS = 2

    /** Words that scatter more than this around their line are not one placement. */
    const val MAX_ANCHOR_MAD_MS = 120L

    /** A residual this small is not worth moving the line for. */
    const val MIN_APPLY_MS = 40L

    /** A line heard through three or more words can be an arrangement's worth out. */
    const val MAX_SHIFT_MANY_WORDS_MS = 4_000L

    /** With only two words, a large move is more likely a mishearing than a real one. */
    const val MAX_SHIFT_TWO_WORDS_MS = 600L

    /** Two anchors further apart than this do not interpolate between themselves. */
    const val MAX_INTERPOLATE_SPAN_MS = 30_000L

    /** Anchors whose residuals differ by more than this do not interpolate either. */
    const val MAX_INTERPOLATE_DIFF_MS = 400L

    /** Otherwise a line takes the nearest anchor's residual when it is this close. */
    const val HOLD_RANGE_MS = 8_000L

    /** No move outside this much beyond the heard words. */
    const val OUTSIDE_HEARD_RANGE_MS = 8_000L

    /** Lines never start less than this after the one before them. */
    const val MIN_LINE_GAP_MS = 50L

    /** A line's sung pace against the nominal 180 ms a letter: 45 ms to 360 ms a letter. */
    const val MIN_PACE = 0.25
    const val MAX_PACE = 2.0

    /**
     * How fast [words] were sung against their nominal offsets: the median slope
     * over every pair (Theil–Sen), so one misheard word cannot set the pace.
     * 1.0 when no two words sit at different offsets.
     */
    private fun pace(words: List<LyricsWordAlign.MatchedWord>): Double {
        val slopes = ArrayList<Double>()
        for (a in words.indices) for (b in a + 1 until words.size) {
            val dOffset = words[b].offsetMs - words[a].offsetMs
            if (dOffset != 0L) slopes += (words[b].word.startMs - words[a].word.startMs).toDouble() / dOffset
        }
        if (slopes.isEmpty()) return 1.0
        slopes.sort()
        val mid = slopes.size / 2
        return if (slopes.size % 2 == 1) slopes[mid] else (slopes[mid - 1] + slopes[mid]) / 2
    }

    /** What [compute] decided for one song: each line's move and the figures for the log. */
    data class Layer(
        val shiftsMs: List<Long>,
        val anchors: Int,
        val applied: Int,
        val maxAbsMs: Long,
    ) {
        companion object {
            fun identity(lines: Int): Layer = Layer(List(lines) { 0L }, 0, 0, 0L)
        }
    }

    private class Anchor(val line: Int, val timeMs: Long, val residualMs: Long)

    /**
     * The per-line moves for [lines] (already after base, HQ and refine) from
     * [heard] (on the same clock). [heardAudioKey] is the rendition those words
     * were heard on; when it is not [currentAudioKey] the layer does nothing.
     */
    fun compute(
        lines: List<LyricLine>,
        heard: List<LyricsWordAlign.Heard>,
        heardAudioKey: Int?,
        currentAudioKey: Int?,
    ): Layer {
        if (heardAudioKey != currentAudioKey) return Layer.identity(lines.size)
        if (lines.isEmpty() || heard.isEmpty()) return Layer.identity(lines.size)
        // The words are expected on the same clock as the lines: LyricsTiming
        // converts them from the rendition they were heard on, and drops them
        // when no verified map can carry them onto the playing clock.
        val matched = LyricsWordAlign.matchedWords(lines.map { it.text }, heard)
        val anchors = ArrayList<Anchor>(lines.size)
        for (index in lines.indices) {
            val words = matched[index]
            if (words.size < MIN_ANCHOR_WORDS) continue
            // The offsets assume 180 ms a letter; English is often sung at half
            // that, which scattered every English line by seconds (11-03 Grenade:
            // 'perline anchors=0'). The line's own pace, read from its words,
            // scales them instead. Two words always fit their own pace, so an
            // implausible one means a mishearing rather than a fast singer.
            val rawPace = pace(words)
            if (words.size < 3 && rawPace !in MIN_PACE..MAX_PACE) continue
            val pace = rawPace.coerceIn(MIN_PACE, MAX_PACE)
            val residuals = words.map { word ->
                word.word.startMs - (word.offsetMs * pace).roundToLong() - lines[index].timeMs
            }
            val median = median(residuals)
            val mad = median(residuals.map { abs(it - median) })
            if (mad > MAX_ANCHOR_MAD_MS) continue
            val cap = if (words.size >= 3) MAX_SHIFT_MANY_WORDS_MS else MAX_SHIFT_TWO_WORDS_MS
            // Beyond the cap the residual is not a line that moved: three words
            // agreeing that far out are almost always the same words heard at a
            // repeated chorus, and moving the line by the cap would be a wrong
            // move rather than a partial one (Opus review, 11-04). Drop the
            // anchor and let its neighbours interpolate.
            val residual = median(residuals)
            if (abs(residual) > cap) continue
            anchors += Anchor(index, lines[index].timeMs, residual)
        }
        if (anchors.isEmpty()) return Layer.identity(lines.size)

        val heardFrom = heard.minOf { it.startMs }
        val heardTo = heard.maxOf { it.endMs }
        val anchorAt = HashMap<Int, Anchor>(anchors.size)
        for (anchor in anchors) anchorAt[anchor.line] = anchor
        val shifts = LongArray(lines.size)
        var previousStart = Long.MIN_VALUE
        var previousAnchor = 0
        for (index in lines.indices) {
            while (previousAnchor < anchors.size && anchors[previousAnchor].line < index) {
                previousAnchor++
            }
            val own = anchorAt[index]
            val before = anchors.getOrNull(previousAnchor - 1)?.takeIf { it.line < index }
            val after = if (own != null) {
                anchors.getOrNull(previousAnchor + 1)?.takeIf { it.line > index }
            } else {
                anchors.getOrNull(previousAnchor)?.takeIf { it.line > index }
            }
            val lineTime = lines[index].timeMs
            val target = when {
                lineTime < heardFrom - OUTSIDE_HEARD_RANGE_MS ||
                    lineTime > heardTo + OUTSIDE_HEARD_RANGE_MS -> 0L
                own != null && !loneAnchorDisagrees(own, before, after, lineTime) -> own.residualMs
                before != null && after != null -> {
                    val span = after.timeMs - before.timeMs
                    if (span in 0..MAX_INTERPOLATE_SPAN_MS &&
                        abs(after.residualMs - before.residualMs) < MAX_INTERPOLATE_DIFF_MS
                    ) {
                        if (span <= 0L) {
                            before.residualMs
                        } else {
                            val fraction = (lineTime - before.timeMs).toDouble() / span
                            (before.residualMs +
                                (after.residualMs - before.residualMs) * fraction).roundToLong()
                        }
                    } else {
                        nearest(before, after, lineTime)
                    }
                }
                else -> {
                    val only = before ?: after
                    if (only != null && abs(only.timeMs - lineTime) <= HOLD_RANGE_MS) {
                        only.residualMs
                    } else {
                        0L
                    }
                }
            }
            var shift = if (abs(target) >= MIN_APPLY_MS) target else 0L
            val start = lineTime
            if (index > 0) {
                val minimum = previousStart + MIN_LINE_GAP_MS
                if (start + shift < minimum) shift = minimum - start
            }
            shifts[index] = shift
            previousStart = start + shift
        }
        val applied = shifts.count { it != 0L }
        val maxAbs = shifts.maxOfOrNull { abs(it) } ?: 0L
        return Layer(shifts.toList(), anchors.size, applied, maxAbs)
    }

    private fun loneAnchorDisagrees(own: Anchor, before: Anchor?, after: Anchor?, lineTime: Long): Boolean {
        val nearBefore = before?.takeIf { abs(it.timeMs - lineTime) <= HOLD_RANGE_MS } ?: return false
        val nearAfter = after?.takeIf { abs(it.timeMs - lineTime) <= HOLD_RANGE_MS } ?: return false
        if (abs(nearAfter.residualMs - nearBefore.residualMs) >= MAX_INTERPOLATE_DIFF_MS) return false
        return abs(nearBefore.residualMs - own.residualMs) > MAX_INTERPOLATE_DIFF_MS &&
            abs(nearAfter.residualMs - own.residualMs) > MAX_INTERPOLATE_DIFF_MS
    }

    private fun nearest(before: Anchor, after: Anchor, atMs: Long): Long {
        val toBefore = abs(atMs - before.timeMs)
        val toAfter = abs(atMs - after.timeMs)
        val nearest = if (toBefore <= toAfter) before else after
        return if (minOf(toBefore, toAfter) <= HOLD_RANGE_MS) nearest.residualMs else 0L
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
