package com.music.bitchord.data.lyrics

import kotlin.math.abs

/**
 * Which line timing to start from when a transcript has heard the song.
 *
 * The kept facts can disagree: a measurement may have lined the record up with
 * a different arrangement, while an earlier AI answer or the provider's own
 * times match what is actually sung on the video. The fixed order (measurement
 * first, then no-change, then AI) cannot see that — it once let a +1.77 s
 * measurement overwrite a +0.06 s AI answer on a cover. Here every candidate is
 * compared with the line starts [LyricsWordAlign] hears in the transcript, and
 * the fixed order's candidate is kept unless another is clearly better.
 *
 * Pure: no Android, no disk, no coroutine. The caller supplies the candidate
 * line starts and the heard line starts.
 */
object BaseChoicePolicy {

    const val NONE = "none"
    const val AI = "ai"
    const val MEASURED = "measured"

    /** Fewer heard line starts than this is not evidence about the whole song. */
    const val MIN_MATCHED_LINES = 6

    /** A start this close to what was heard counts as on time. */
    const val WITHIN_MS = 300L

    /** A candidate must beat the default's within-window share by this much (15%p). */
    const val MIN_RATIO_GAIN = 0.15

    /** ...and its median error must be at least this much smaller. */
    const val MIN_MEDIAN_GAIN_MS = 300L

    /** The lateness sample is only trusted from a measurement this on time. */
    const val LATENESS_MIN_MATCHED_LINES = 6
    const val LATENESS_MIN_WITHIN_RATIO = 0.70

    /** One candidate line timing, named for the log (`none`, `ai`, `measured`). */
    data class Candidate(val name: String, val starts: List<Long?>)

    /** How one candidate lines up with the heard starts. */
    data class Score(val matched: Int, val medianAbsMs: Long, val withinRatio: Double)

    /** The chosen name and every candidate's score, for the one-line log. */
    data class Choice(
        val name: String,
        val scores: List<Pair<String, Score>>,
        /** Set when the pick was refused for the heard words, for the log. */
        val reason: String? = null,
    )

    /**
     * Whether the heard words, with enough lines to be evidence, put this
     * candidate further off than a refine can move — not this sheet's timing
     * (11-06 sVwYpHqdt3o: `none:86560/0% measured:87359/0% -> measured`).
     */
    fun belowThreshold(score: Score): Boolean =
        score.matched >= MIN_MATCHED_LINES && score.medianAbsMs > RefinePolicy.MAX_SHIFT_MS

    /** Line starts heard in [words], as [LyricsWordAlign] places them. */
    fun heardStarts(lines: List<String>, words: List<LyricsWordAlign.Heard>): List<Long?> =
        LyricsWordAlign.placeLines(lines, words)

    /**
     * [defaultName] is the candidate the fixed order picked. A different
     * candidate replaces it only when it has at least [MIN_MATCHED_LINES]
     * matched lines, its within-[WITHIN_MS] share is [MIN_RATIO_GAIN] higher,
     * and its median error is [MIN_MEDIAN_GAIN_MS] smaller. A pick that is
     * [belowThreshold] becomes [NONE]. [manual] keeps the listener's choice
     * whatever the scores say.
     */
    fun choose(
        defaultName: String,
        heardStarts: List<Long?>,
        candidates: List<Candidate>,
        manual: Boolean = false,
    ): Choice {
        val scores = candidates.map { it.name to score(it.starts, heardStarts) }
        if (manual) return Choice(defaultName, scores)
        val defaultScore = scores.firstOrNull { it.first == defaultName }?.second
            ?: return Choice(defaultName, scores)
        if (defaultScore.matched < MIN_MATCHED_LINES) return Choice(defaultName, scores)
        val better = scores.asSequence()
            .filter { it.first != defaultName }
            .filter { it.second.matched >= MIN_MATCHED_LINES }
            .filter { it.second.withinRatio - defaultScore.withinRatio >= MIN_RATIO_GAIN - 1e-9 }
            .filter { defaultScore.medianAbsMs - it.second.medianAbsMs >= MIN_MEDIAN_GAIN_MS }
            .sortedWith(
                compareByDescending<Pair<String, Score>> { it.second.withinRatio }
                    .thenBy { it.second.medianAbsMs },
            )
            .firstOrNull()
        val pick = better ?: (defaultName to defaultScore)
        // No correction beats a wrong one: a pick the words refuse leaves the provider's times.
        if (pick.first != NONE && belowThreshold(pick.second)) {
            return Choice(NONE, scores, reason = "below threshold")
        }
        return Choice(pick.first, scores)
    }

    /** Matched lines, median absolute error and the within-[WITHIN_MS] share of one candidate. */
    fun score(candidateStarts: List<Long?>, heardStarts: List<Long?>): Score {
        val errors = ArrayList<Long>(minOf(candidateStarts.size, heardStarts.size))
        var within = 0
        for (index in 0 until minOf(candidateStarts.size, heardStarts.size)) {
            val candidate = candidateStarts[index] ?: continue
            val heard = heardStarts[index] ?: continue
            val error = abs(candidate - heard)
            errors += error
            if (error <= WITHIN_MS) within++
        }
        if (errors.isEmpty()) return Score(0, 0L, 0.0)
        errors.sort()
        return Score(errors.size, errors[errors.size / 2], within.toDouble() / errors.size)
    }

    /** Whether a measured timing may contribute a lateness sample. */
    fun latenessSampleAllowed(score: Score): Boolean =
        score.matched >= LATENESS_MIN_MATCHED_LINES &&
            score.withinRatio >= LATENESS_MIN_WITHIN_RATIO

    /** The lines needed before a gross disagreement is the whole song's, not one guess. */
    const val GROSS_MIN_MATCHED_LINES = RefinePolicy.MIN_WIDE_LINES

    /**
     * How much of a sheet a base may put at or before zero before it is not
     * this sheet's timing at all.
     *
     * A provider's sheet starts at or after zero by construction, so a map that
     * puts a quarter of it before the song starts is the record of another
     * arrangement: 11-02 AMILJn51YBk, where a measured −59.5 s pinned 7 of 19
     * lines at 0:00 for the whole song. The failure is one-sided — refusing the
     * base leaves the provider's own times showing, which is the state before
     * any measurement — so the rule can be strict without costing a good answer.
     */
    const val MAX_BURIED_PERCENT = 25

    /** Fewer timed lines than this and "most of the sheet is before zero" is not a verdict. */
    const val MIN_BURIED_LINES = 4

    /** Whether [starts] (a base's line times) puts too much of the sheet before the song starts. */
    fun buriesSheet(starts: List<Long?>): Boolean {
        val timed = starts.filterNotNull()
        if (timed.size < MIN_BURIED_LINES) return false
        return timed.count { it <= 0L } * 100 > timed.size * MAX_BURIED_PERCENT
    }

    /**
     * True when enough lines were heard and the median line sits further from
     * the transcript than a narrow refine can move. That is an
     * arrangement-sized disagreement, so the timing has not settled.
     */
    fun grosslyOff(candidateStarts: List<Long?>, heardStarts: List<Long?>): Boolean {
        val score = score(candidateStarts, heardStarts)
        return score.matched >= GROSS_MIN_MATCHED_LINES &&
            score.medianAbsMs > RefinePolicy.MAX_SHIFT_MS
    }
}
