package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The last, smallest correction to lyrics that are already on the video's
 * clock: a lyric provider's own line times can sit a fifth of a second early
 * or late even when the recording and the video agree perfectly, and some
 * videos drift a little further apart as they play.
 *
 * Each line's opening letters are looked for in the transcript's letters,
 * with a time for every letter. Matching letters rather than a first word
 * lets a language written without spaces — Japanese, where a "word" from the
 * transcript may be a whole line — still say where a line was heard. A line
 * whose opening is heard within [WINDOW_MS] of where it is shown gives one
 * residual (`heard start - shown start`). A repeated chorus is matched to the
 * nearest hearing rather than the first, so the second time through is not
 * pulled onto the first.
 *
 * The residuals are one constant move only when enough lines agree and their
 * spread is small. When enough lines also span enough of the song and the
 * residual grows steadily with time, a robust straight line is fitted instead
 * and its slope is kept with the offset. That is the drift a constant move
 * cannot follow. The fit is refused unless it is clearly better than the
 * constant move.
 *
 * A provider's line times can also be a whole arrangement apart — a cover
 * whose lyric file was timed to a different recording with a long intro. When
 * the narrow window finds nothing to apply, the same opening letters are
 * looked for in a wide window and the move every line supports together is
 * taken, rather than each line's nearest hearing. That is the one big move a
 * narrow window can never see.
 *
 * Pure, so the rule can be checked without Android, a transcript or the disk.
 */
object RefinePolicy {

    /** Which of the three models produced a [Result]. */
    enum class Model { CONST, LINEAR, WIDE }

    /** What one song's lines say about the timing, and whether it may be moved. */
    data class Result(
        val applied: Boolean,
        /**
         * The constant move applied, or the line's offset when [slopePerMin]
         * is non-zero; the median found when [applied] is false. A narrow
         * move is capped at ±[MAX_SHIFT_MS], a wide one at ±[MAX_WIDE_SHIFT_MS].
         */
        val medianMs: Long,
        /** Median absolute deviation of the line residuals from the model. */
        val madMs: Long,
        /** How many lines found their opening letters heard. */
        val lines: Int,
        /** The drift in ms per minute when a straight line applied; 0 for a constant move. */
        val slopePerMin: Double = 0.0,
        /** Whether this is the narrow constant/line fit or the wide common move. */
        val model: Model = Model.CONST,
    ) {
        companion object {
            val NONE = Result(applied = false, medianMs = 0L, madMs = 0L, lines = 0)
        }
    }

    /** A line's opening must be heard this close to where it is shown. */
    const val WINDOW_MS = 1_500L

    /** Fewer agreeing lines than this is a guess, not a song-wide offset. */
    const val MIN_LINES = 6

    /** Residuals spread wider than this are not one constant offset. */
    const val MAX_MAD_MS = 250L

    /** A median smaller than this is not worth moving the lyrics for. */
    const val MIN_SHIFT_MS = 80L

    /** However far the median points, one refine moves no further than this. */
    const val MAX_SHIFT_MS = 1_500L

    /** The second attempt looks this far either side of a line's shown time. */
    private const val WIDE_WINDOW_MS = 8_000L

    /** Candidate residuals are voted into bins this wide. */
    private const val WIDE_BIN_MS = 200L

    /** The winning bin's residuals within this distance give the wide median. */
    private const val WIDE_BIN_TOLERANCE_MS = 400L

    /** A wide move needs this many lines, and half of the lines that matched. */
    const val MIN_WIDE_LINES = 10

    /** Supporting residuals spread wider than this are not one arrangement's move. */
    const val MAX_WIDE_MAD_MS = 300L

    /** A move smaller than this belongs to the narrow window, not the wide one. */
    const val MIN_WIDE_SHIFT_MS = 1_500L

    /** However far the wide median points, one refine moves no further than this. */
    const val MAX_WIDE_SHIFT_MS = 10_000L

    /** How many opening letters of a line are matched against the transcript. */
    private const val PREFIX_LETTERS = 4

    /** A line with fewer letters than this says too little to be matched. */
    private const val MIN_PREFIX_LETTERS = 2

    /** A straight line needs this many lines, spanning at least this much time. */
    const val MIN_LINEAR_LINES = 8
    const val MIN_LINEAR_SPAN_MS = 40_000L

    /** A drift smaller than this (120 ms/min) is not worth a line. */
    const val MIN_SLOPE = 0.002

    /** No refine claims a drift faster than this (600 ms/min). */
    const val MAX_SLOPE = 0.01

    /** A line whose residuals still spread this far is not a straight drift. */
    const val MAX_LINEAR_MAD_MS = 200L

    /**
     * [lines] as they are shown, against the [heard] words of the sound that
     * is playing. Both are on the same clock.
     */
    fun compute(lines: List<LyricLine>, heard: List<LyricsWordAlign.Heard>): Result {
        if (lines.isEmpty() || heard.isEmpty()) return Result.NONE
        val stream = letterStream(heard)
        if (stream.chars.isEmpty()) return Result.NONE
        val times = ArrayList<Long>(lines.size)
        val residuals = ArrayList<Long>(lines.size)
        for (line in lines) {
            if (line.timeMs < 0L || line.text.isBlank()) continue
            val prefix = LyricsWordAlign.fold(line.text).take(PREFIX_LETTERS)
            if (prefix.length < MIN_PREFIX_LETTERS) continue
            val heardStart = matchStart(prefix, stream, line.timeMs) ?: continue
            times += line.timeMs
            residuals += heardStart - line.timeMs
        }
        if (residuals.isNotEmpty()) {
            val median = median(residuals)
            val mad = median(residuals.map { abs(it - median) })
            linearFit(times, residuals, mad)?.let { line ->
                return Result(
                    applied = true,
                    medianMs = line.offsetMs,
                    madMs = line.madMs,
                    lines = residuals.size,
                    slopePerMin = line.slopePerMin,
                    model = Model.LINEAR,
                )
            }
            val applied = residuals.size >= MIN_LINES && mad <= MAX_MAD_MS && abs(median) >= MIN_SHIFT_MS
            if (applied) {
                return Result(
                    applied = true,
                    medianMs = median.coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS),
                    madMs = mad,
                    lines = residuals.size,
                )
            }
        }
        // The narrow window found nothing worth moving. Only then look for the
        // one big move a provider from another arrangement leaves behind.
        wide(lines, stream)?.let { return it }
        if (residuals.isEmpty()) return Result.NONE
        val median = median(residuals)
        val mad = median(residuals.map { abs(it - median) })
        return Result(
            applied = false,
            medianMs = median.coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS),
            madMs = mad,
            lines = residuals.size,
        )
    }

    /** The transcript's letters, each with the moment it was heard. */
    private class LetterStream(val chars: CharArray, val times: LongArray)

    private fun letterStream(heard: List<LyricsWordAlign.Heard>): LetterStream {
        val chars = StringBuilder()
        val times = ArrayList<Long>()
        for (word in heard) {
            val letters = LyricsWordAlign.fold(word.text)
            if (letters.isEmpty()) continue
            val span = (word.endMs - word.startMs).coerceAtLeast(0L)
            letters.forEachIndexed { index, letter ->
                chars.append(letter)
                times += word.startMs + span * index / letters.length
            }
        }
        return LetterStream(chars.toString().toCharArray(), times.toLongArray())
    }

    /**
     * Where [prefix] starts in [stream] within [WINDOW_MS] of [shownMs], or
     * null. A full match is preferred; with four letters, three of them in
     * order are accepted when nothing matches fully. Several places: the one
     * nearest the shown time.
     */
    private fun matchStart(prefix: String, stream: LetterStream, shownMs: Long): Long? {
        exact(prefix, stream, shownMs)?.let { return it }
        if (prefix.length < PREFIX_LETTERS) return null
        return nearest(prefix, stream, shownMs, allowOneWrong = true)
    }

    private fun exact(prefix: String, stream: LetterStream, shownMs: Long): Long? =
        nearest(prefix, stream, shownMs, allowOneWrong = false)

    private fun nearest(
        prefix: String,
        stream: LetterStream,
        shownMs: Long,
        allowOneWrong: Boolean,
    ): Long? {
        val length = prefix.length
        if (stream.chars.size < length) return null
        var best: Long? = null
        var bestBy = Long.MAX_VALUE
        for (start in 0..stream.chars.size - length) {
            val same = if (allowOneWrong) {
                commonInOrder(prefix, stream.chars, start, length)
            } else {
                var count = 0
                for (offset in 0 until length) {
                    if (stream.chars[start + offset] == prefix[offset]) count++
                }
                count
            }
            val matches = if (allowOneWrong) same >= length - 1 else same == length
            if (!matches) continue
            val at = stream.times[start]
            val by = abs(at - shownMs)
            if (by <= WINDOW_MS && by < bestBy) {
                best = at
                bestBy = by
            }
        }
        return best
    }

    /** The longest run of [prefix]'s letters that appears in order in the [length] letters at [start]. */
    private fun commonInOrder(prefix: String, chars: CharArray, start: Int, length: Int): Int {
        var previous = IntArray(length + 1)
        for (i in 1..length) {
            val current = IntArray(length + 1)
            for (j in 1..length) {
                current[j] = if (prefix[i - 1] == chars[start + j - 1]) {
                    previous[j - 1] + 1
                } else {
                    maxOf(previous[j], current[j - 1])
                }
            }
            previous = current
        }
        return previous[length]
    }

    /**
     * The one move all lines support, found in a window wide enough for a
     * provider timed to another arrangement. Every line's candidate residuals
     * vote in [WIDE_BIN_MS] bins, a line at most once per bin; the bin the most
     * lines support wins, its residuals within [WIDE_BIN_TOLERANCE_MS] give the
     * median. Null unless enough lines agree tightly on a move the narrow
     * window could not have made.
     */
    private fun wide(lines: List<LyricLine>, stream: LetterStream): Result? {
        val perLine = ArrayList<List<Long>>(lines.size)
        for (line in lines) {
            if (line.timeMs < 0L || line.text.isBlank()) continue
            val prefix = LyricsWordAlign.fold(line.text).take(PREFIX_LETTERS)
            if (prefix.length < MIN_PREFIX_LETTERS) continue
            val heard = candidates(prefix, stream, line.timeMs)
            if (heard.isEmpty()) continue
            perLine += heard.map { it - line.timeMs }
        }
        if (perLine.size < MIN_WIDE_LINES) return null
        val votes = HashMap<Long, Int>()
        for (residuals in perLine) {
            val bins = HashSet<Long>()
            for (residual in residuals) bins += residual.floorDiv(WIDE_BIN_MS)
            for (bin in bins) votes[bin] = (votes[bin] ?: 0) + 1
        }
        var bestBin = 0L
        var bestVotes = -1
        var bestDistance = Long.MAX_VALUE
        for ((bin, count) in votes) {
            val center = bin * WIDE_BIN_MS + WIDE_BIN_MS / 2
            val distance = abs(center)
            val better = count > bestVotes ||
                (count == bestVotes && (distance < bestDistance ||
                    (distance == bestDistance && bin < bestBin)))
            if (better) {
                bestBin = bin
                bestVotes = count
                bestDistance = distance
            }
        }
        if (bestVotes < MIN_WIDE_LINES) return null
        if (bestVotes * 2 < perLine.size) return null
        val center = bestBin * WIDE_BIN_MS + WIDE_BIN_MS / 2
        val supporting = ArrayList<Long>()
        for (residuals in perLine) {
            for (residual in residuals) {
                if (abs(residual - center) <= WIDE_BIN_TOLERANCE_MS) supporting += residual
            }
        }
        if (supporting.isEmpty()) return null
        val median = median(supporting)
        val mad = median(supporting.map { abs(it - median) })
        if (mad > MAX_WIDE_MAD_MS) return null
        if (abs(median) < MIN_WIDE_SHIFT_MS) return null
        return Result(
            applied = true,
            medianMs = median.coerceIn(-MAX_WIDE_SHIFT_MS, MAX_WIDE_SHIFT_MS),
            madMs = mad,
            lines = perLine.size,
            model = Model.WIDE,
        )
    }

    /**
     * Every place [prefix] is heard within [WIDE_WINDOW_MS] of [shownMs], in
     * time order. A full match is preferred; with four letters, three of them
     * in order are used only when nothing matches fully.
     */
    private fun candidates(prefix: String, stream: LetterStream, shownMs: Long): List<Long> {
        val exact = candidatesWithin(prefix, stream, shownMs, allowOneWrong = false)
        if (exact.isNotEmpty()) return exact
        if (prefix.length < PREFIX_LETTERS) return emptyList()
        return candidatesWithin(prefix, stream, shownMs, allowOneWrong = true)
    }

    private fun candidatesWithin(
        prefix: String,
        stream: LetterStream,
        shownMs: Long,
        allowOneWrong: Boolean,
    ): List<Long> {
        val length = prefix.length
        if (stream.chars.size < length) return emptyList()
        val found = ArrayList<Long>()
        for (start in 0..stream.chars.size - length) {
            val same = if (allowOneWrong) {
                commonInOrder(prefix, stream.chars, start, length)
            } else {
                var count = 0
                for (offset in 0 until length) {
                    if (stream.chars[start + offset] == prefix[offset]) count++
                }
                count
            }
            val matches = if (allowOneWrong) same >= length - 1 else same == length
            if (!matches) continue
            val at = stream.times[start]
            if (abs(at - shownMs) <= WIDE_WINDOW_MS) found += at
        }
        return found.distinct()
    }

    /** A robust straight line through the residual points, when it is clearly better than one move. */
    private class Linear(val offsetMs: Long, val slopePerMin: Double, val madMs: Long)

    private fun linearFit(times: List<Long>, residuals: List<Long>, constantMad: Long): Linear? {
        if (times.size < MIN_LINEAR_LINES) return null
        val span = (times.maxOrNull() ?: return null) - (times.minOrNull() ?: return null)
        if (span < MIN_LINEAR_SPAN_MS) return null
        val slopes = ArrayList<Double>(times.size * (times.size - 1) / 2)
        for (i in times.indices) {
            for (j in i + 1 until times.size) {
                val dx = times[j] - times[i]
                if (dx == 0L) continue
                slopes += (residuals[j] - residuals[i]).toDouble() / dx
            }
        }
        if (slopes.isEmpty()) return null
        var slope = median(slopes)
        if (!slope.isFinite() || abs(slope) < MIN_SLOPE) return null
        slope = slope.coerceIn(-MAX_SLOPE, MAX_SLOPE)
        val offset = median(times.indices.map { residuals[it] - slope * times[it] })
        val mad = median(
            times.indices.map {
                abs(residuals[it] - (offset + slope * times[it])).roundToLong()
            },
        )
        if (mad > MAX_LINEAR_MAD_MS) return null
        if (mad * 10 > constantMad * 7) return null
        return Linear(
            offsetMs = offset.roundToLong(),
            slopePerMin = slope * 60_000.0,
            madMs = mad,
        )
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
