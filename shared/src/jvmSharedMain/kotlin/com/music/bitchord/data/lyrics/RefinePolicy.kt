package com.music.bitchord.data.lyrics

import kotlin.math.abs

/**
 * The last, smallest correction to lyrics that are already on the video's
 * clock: a lyric provider's own line times can sit a fifth of a second early
 * or late even when the recording and the video agree perfectly.
 *
 * For each line whose first word was heard, the closest hearing of that word
 * within [WINDOW_MS] of where the line is shown gives one residual
 * (`heard start - shown start`). A whole song's residuals are one number only
 * when enough lines agree and their spread is small; then that median moves
 * every line. A repeated chorus is matched to the nearest hearing rather than
 * the first, so the second time through is not pulled onto the first.
 *
 * Pure, so the rule can be checked without Android, a transcript or the disk.
 */
object RefinePolicy {

    /** What one song's lines say about the timing, and whether it may be moved. */
    data class Result(
        val applied: Boolean,
        /** The move applied, or the median found when [applied] is false; capped at ±[MAX_SHIFT_MS]. */
        val medianMs: Long,
        /** Median absolute deviation of the line residuals. */
        val madMs: Long,
        /** How many lines found their first word heard. */
        val lines: Int,
    ) {
        companion object {
            val NONE = Result(applied = false, medianMs = 0L, madMs = 0L, lines = 0)
        }
    }

    /** A line's first word must be heard this close to where it is shown. */
    const val WINDOW_MS = 1_500L

    /** Fewer agreeing lines than this is a guess, not a song-wide offset. */
    const val MIN_LINES = 6

    /** Residuals spread wider than this are not one constant offset. */
    const val MAX_MAD_MS = 250L

    /** A median smaller than this is not worth moving the lyrics for. */
    const val MIN_SHIFT_MS = 80L

    /** However far the median points, one refine moves no further than this. */
    const val MAX_SHIFT_MS = 1_500L

    /**
     * [lines] as they are shown, against the [heard] words of the sound that
     * is playing. Both are on the same clock.
     */
    fun compute(lines: List<LyricLine>, heard: List<LyricsWordAlign.Heard>): Result {
        if (lines.isEmpty() || heard.isEmpty()) return Result.NONE
        val residuals = ArrayList<Long>(lines.size)
        for (line in lines) {
            if (line.timeMs < 0L || line.text.isBlank()) continue
            val first = firstWord(line.text)
            if (first.isEmpty()) continue
            var closest: LyricsWordAlign.Heard? = null
            var closestBy = Long.MAX_VALUE
            for (word in heard) {
                if (LyricsWordAlign.fold(word.text) != first) continue
                val by = abs(word.startMs - line.timeMs)
                if (by <= WINDOW_MS && by < closestBy) {
                    closest = word
                    closestBy = by
                }
            }
            if (closest != null) residuals += closest.startMs - line.timeMs
        }
        if (residuals.isEmpty()) return Result.NONE
        val median = median(residuals)
        val mad = median(residuals.map { abs(it - median) })
        val applied = residuals.size >= MIN_LINES && mad <= MAX_MAD_MS && abs(median) >= MIN_SHIFT_MS
        return Result(
            applied = applied,
            medianMs = median.coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS),
            madMs = mad,
            lines = residuals.size,
        )
    }

    /** The line's first word, folded the same way as a heard word. */
    private fun firstWord(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        var end = 0
        while (end < trimmed.length && !trimmed[end].isWhitespace()) end++
        return LyricsWordAlign.fold(trimmed.substring(0, end))
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
