package com.music.bitchord.data.lyrics

import kotlin.math.abs

/**
 * Which of the sources that answered should be shown when their first timed
 * lines do not agree.
 *
 * Sources are raced, so the answer that arrives first is not evidence that its
 * timing is right: the same song landed on a provider 8-12 s off on one run and
 * on a matching one on the next. The first sung line is already in every
 * answer, and providers that agree on it are the ones whose times belong to
 * the same arrangement. When more than one timed answer is in hand, the one
 * that agrees with most of the others wins; priority in [order] only breaks a
 * tie. A single timed answer is kept as-is — there is nothing to compare it
 * with.
 *
 * Pure: no Android, no disk, no coroutine. Callers pass the candidates in
 * priority order and get back the index to take.
 */
object ProviderChoice {

    /** First lines this close belong to the same arrangement. */
    const val AGREE_WITHIN_MS = 2_000L

    /** One source's first timed line; null when it has no timed line at all. */
    data class Candidate(val source: LyricsSource, val firstStartMs: Long?)

    /**
     * The index of the candidate to keep, or null for an empty list.
     * [Candidate]s are in priority order; agreement with other candidates
     * decides first, and that order decides ties.
     */
    fun choose(candidates: List<Candidate>): Int? {
        if (candidates.isEmpty()) return null
        val timed = candidates.mapIndexedNotNull { index, candidate ->
            candidate.firstStartMs?.let { index to it }
        }
        if (timed.isEmpty()) return 0
        if (timed.size == 1) return timed[0].first
        val agreement = IntArray(timed.size)
        for (a in timed.indices) {
            for (b in a + 1 until timed.size) {
                if (abs(timed[a].second - timed[b].second) <= AGREE_WITHIN_MS) {
                    agreement[a]++
                    agreement[b]++
                }
            }
        }
        val best = agreement.max()
        if (best == 0) return 0
        val winner = timed.indices
            .filter { agreement[it] == best }
            .minOf { timed[it].first }
        return timed[winner].first
    }
}
