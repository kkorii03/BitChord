package com.music.bitchord.data.lyrics

import kotlin.math.abs

/**
 * Whether the provider whose lyrics were used is the odd one out among the
 * providers that answered for the same song.
 *
 * Measured, not acted on: the lookup keeps whichever answer priority gives it,
 * and this only says afterwards whether that answer was alone. It exists
 * because the same track scored pass or fail depending on whose timings the app
 * happened to use (v59g4HRNIkc: KuGou -8.68 s against Gemini -0.02 s), and
 * "prefer whoever the others agree with" cannot be tried before there is
 * evidence of how often the used answer really is the outlier.
 *
 * Two answers are compared on the lines they share, matched by text: the
 * offset is the median of the line-start differences, and only counts when at
 * least [MIN_MATCHING_LINES] lines matched and those differences are consistent
 * (median absolute deviation within [AGREE_MS]). Answers whose offset is within
 * [AGREE_MS] of each other are one cluster; a cluster of two or more *distinct*
 * timings is a vote. Identical timings are one voice however many providers
 * serve them: several hosts copy the same LRC file.
 */
object ProviderConsensus {

    /** Two answers whose line starts differ by no more than this agree. */
    const val AGREE_MS = 250L

    /** Fewer shared lines than this and two answers cannot be compared at all. */
    const val MIN_MATCHING_LINES = 3

    /**
     * One provider's answer, as it stood when the used answer was chosen.
     *
     * [firstLineMs] is when its first sung line starts, or null for a sheet
     * with no timings — one of those is not evidence about anyone's clock.
     */
    data class Candidate(
        val provider: String,
        val firstLineMs: Long?,
        val lines: List<LyricLine>,
    )

    /** What the comparison found; [Outlier] is the case worth measuring. */
    sealed class Verdict {
        /** The used provider sits in the largest group of agreeing answers. */
        data object Agree : Verdict()

        /**
         * The used provider disagrees with [votes] independent answers, whose
         * lines start [deltaMs] later than its own (negative: earlier).
         */
        data class Outlier(val deltaMs: Long, val votes: Int) : Verdict()

        /** Fewer than two independent timed answers: nothing to compare. */
        data object Unknown : Verdict()
    }

    /**
     * [answers] are the answers already in hand — never one still on its way —
     * and [used] is the provider whose answer the lookup took.
     */
    fun judge(answers: List<Candidate>, used: String): Verdict {
        val timed = answers.mapNotNull { answer -> answer.firstLineMs?.let { answer to it } }
        val usedAt = timed.indexOfFirst { (answer, _) -> answer.provider == used }
        if (usedAt < 0 || timed.size < 2) return Verdict.Unknown
        val roots = clusters(timed)
        val groups = (timed.indices).groupBy { roots[it] }.values
        // Copies of one file are one voice, however many hosts serve them.
        fun votes(group: List<Int>) = group.map { timed[it].second to timed[it].first.lines.size }.distinct().size
        val majority = groups
            .filter { votes(it) >= 2 }
            .maxWithOrNull(compareBy<List<Int>> { votes(it) }.thenBy { if (usedAt in it) 1 else 0 })
            ?: return Verdict.Unknown
        if (usedAt in majority) return Verdict.Agree
        val gaps = majority.mapNotNull { offset(timed[usedAt].first, timed[it].first) }
        val delta = if (gaps.isEmpty()) {
            median(majority.map { timed[it].second - timed[usedAt].second })
        } else {
            median(gaps)
        }
        return Verdict.Outlier(delta, votes(majority))
    }

    /** The log line the lookup writes once it has decided whose answer to use. */
    fun logLine(videoId: String, used: String, verdict: Verdict): String = when (verdict) {
        Verdict.Agree -> "lyrics consensus: $videoId used=$used verdict=agree"
        Verdict.Unknown -> "lyrics consensus: $videoId used=$used verdict=unknown"
        is Verdict.Outlier ->
            "lyrics consensus: $videoId used=$used verdict=outlier(" +
                "${if (verdict.deltaMs >= 0) "+" else ""}${verdict.deltaMs}ms,${verdict.votes})"
    }

    /** Which cluster each of [timed] belongs to, as an index into [timed]. */
    private fun clusters(timed: List<Pair<Candidate, Long>>): IntArray {
        val parent = IntArray(timed.size) { it }
        fun root(index: Int): Int {
            var at = index
            while (parent[at] != at) {
                parent[at] = parent[parent[at]]
                at = parent[at]
            }
            return at
        }
        for (i in timed.indices) {
            for (j in i + 1 until timed.size) {
                val gap = offset(timed[i].first, timed[j].first) ?: continue
                if (abs(gap) > AGREE_MS) continue
                val a = root(i)
                val b = root(j)
                if (a != b) parent[b] = a
            }
        }
        return IntArray(timed.size) { root(it) }
    }

    /**
     * How much later [b]'s lines start than [a]'s, from the lines the two
     * share; null when too few of them match, or when the differences are not
     * consistent enough to be one sheet's timing against another's.
     */
    private fun offset(a: Candidate, b: Candidate): Long? {
        val starts = a.lines.filter { isSung(it) }.associate { fold(it.text) to it.timeMs }
        if (starts.isEmpty()) return null
        val gaps = b.lines.filter { isSung(it) }.mapNotNull { line ->
            starts[fold(line.text)]?.let { line.timeMs - it }
        }
        if (gaps.size < MIN_MATCHING_LINES) return null
        val middle = median(gaps)
        val spread = median(gaps.map { abs(it - middle) })
        return if (spread <= AGREE_MS) middle else null
    }

    private fun isSung(line: LyricLine) = line.timeMs > 0L && line.text.isNotBlank()

    /** Case and spacing do not make two sheets different words. */
    private fun fold(text: String) = text.lowercase().filterNot { it.isWhitespace() }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
