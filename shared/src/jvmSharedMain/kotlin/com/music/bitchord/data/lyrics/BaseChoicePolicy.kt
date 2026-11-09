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

    /** How far past the last heard word a line's own start may sit and still be heard: the widest move an answer may make. */
    const val REACH_MARGIN_MS = LyricsAiGuard.VIDEO_MAX_SHIFT_MS

    /**
     * Line starts heard in [words], as [LyricsWordAlign] places them. With the
     * lines' own starts ([lineStartsMs]) only the lines the words can reach are
     * placed; the rest stay null. An opening listen placed against the whole
     * sheet lands on a later repeat of the same words (the alignment's ends are
     * free), so every base looked 80-95 s off and a right one was refused (11-10
     * sbUKtlkRDvE: `none:95230/0%/17 ai:82050/0%/17 measured:82138/0%/17 -> none`;
     * the quick fit on the same 105 s of words, in-reach lines only, agreed 100%).
     */
    fun heardStarts(
        lines: List<String>,
        words: List<LyricsWordAlign.Heard>,
        lineStartsMs: List<Long>? = null,
    ): List<Long?> {
        val lastHeardMs = words.maxOfOrNull { it.endMs }
        if (lineStartsMs == null || lineStartsMs.size != lines.size || lastHeardMs == null) {
            return LyricsWordAlign.placeLines(lines, words)
        }
        val inReach = lines.indices.filter { lineStartsMs[it] <= lastHeardMs + REACH_MARGIN_MS }
        if (inReach.size == lines.size) return LyricsWordAlign.placeLines(lines, words)
        val placed = LyricsWordAlign.placeLines(inReach.map { lines[it] }, words)
        val starts = arrayOfNulls<Long>(lines.size)
        inReach.forEachIndexed { at, line -> starts[line] = placed[at] }
        return starts.toList()
    }

    /**
     * [defaultName] is the candidate the fixed order picked. A different
     * candidate replaces it only when it has at least [MIN_MATCHED_LINES]
     * matched lines, its within-[WITHIN_MS] share is [MIN_RATIO_GAIN] higher,
     * and its median error is [MIN_MEDIAN_GAIN_MS] smaller. A pick that is
     * [belowThreshold] becomes [NONE], unless [NONE] itself is clearly worse by
     * the same two gains, overall or on the lines a refine could still reach
     * ([clearlyBetterInReach]). [manual] keeps the listener's choice
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
        // Not when the same words put the provider's times clearly further off still: then
        // "none" is the worse of two refused answers, not the safe one (11-08 rmgyNRxWcKI:
        // `none:12830/0% measured:4970/31% -> none`, the measurement was +0.01 s on the
        // scorer and the sheet was shown 12.75 s early for the rest of the song).
        if (pick.first != NONE && belowThreshold(pick.second)) {
            val none = scores.firstOrNull { it.first == NONE }?.second
            if (none != null && clearlyBetter(pick.second, none)) {
                return Choice(pick.first, scores, reason = "below threshold, none further off")
            }
            if (clearlyBetterInReach(pick.first, candidates, heardStarts)) {
                return Choice(pick.first, scores, reason = "below threshold, right where the words can tell")
            }
            return Choice(NONE, scores, reason = "below threshold")
        }
        return Choice(pick.first, scores)
    }

    /**
     * What one base decision rested on: the candidate it picked, the heard
     * lines that candidate matched, and its [Score].
     */
    data class Evidence(val name: String, val matched: Int, val withinRatio: Double, val medianAbsMs: Long)

    /**
     * Whether [next] is backed by stronger heard evidence than [previous], so
     * it may replace it. A null [previous] is always replaced.
     *
     * More heard lines win first; the same count compares the within share and
     * then the median, and an exact tie goes to the name, so the same evidence
     * in any arrival order keeps the same base.
     *
     * Every caller that asks for the base compares its own evidence with the
     * decision already kept (11-08 1WZ1tfpyu3M: the same 69 heard lines scored
     * the AI answer 68 ms / 96% and the prepared measurement -200 ms at 418 ms
     * / 17%; a caller that lacked the AI answer re-picked the measurement, a
     * caller with no words re-picked it again from the fixed order, and the
     * base flipped three times in 350 ms).
     */
    fun replaces(previous: Evidence?, next: Evidence): Boolean = when {
        previous == null -> true
        // Neither rests on heard words (the fixed order): the newest reading of
        // the stored entry wins, so a re-measured or cleared value is not masked.
        next.matched == 0 && previous.matched == 0 -> true
        next.matched != previous.matched -> next.matched > previous.matched
        next.withinRatio != previous.withinRatio -> next.withinRatio > previous.withinRatio
        next.medianAbsMs != previous.medianAbsMs -> next.medianAbsMs < previous.medianAbsMs
        else -> next.name < previous.name
    }

    /** The switch rule of [choose]: [candidate] beats [other] on both the within share and the median. */
    private fun clearlyBetter(candidate: Score, other: Score): Boolean =
        other.matched >= MIN_MATCHED_LINES &&
            candidate.withinRatio - other.withinRatio >= MIN_RATIO_GAIN - 1e-9 &&
            other.medianAbsMs - candidate.medianAbsMs >= MIN_MEDIAN_GAIN_MS

    /**
     * Whether [pickName] clearly beats [NONE] on the lines where either of the two
     * sits within a refine's move ([RefinePolicy.MAX_SHIFT_MS]) of the heard start,
     * by enough of them and not itself refused there. A sheet timed to another
     * arrangement past some point is off for every base on those later lines, so
     * they cannot tell bases apart, yet their median decides [belowThreshold]
     * (11-10 RKX689yBTfE: the whole-song words put the opening at -1.26 s and the
     * lines after a 15 s longer break at +15.3 s, `none:15285/0%/20 ai:16544/10%/20
     * -> none`, and the right AI base of the 90 s answer was dropped).
     */
    private fun clearlyBetterInReach(
        pickName: String,
        candidates: List<Candidate>,
        heardStarts: List<Long?>,
    ): Boolean {
        val pick = candidates.firstOrNull { it.name == pickName }?.starts ?: return false
        val none = candidates.firstOrNull { it.name == NONE }?.starts ?: return false
        val decisive = heardStarts.indices.filter { index ->
            val heard = heardStarts[index] ?: return@filter false
            val p = pick.getOrNull(index) ?: return@filter false
            val n = none.getOrNull(index) ?: return@filter false
            minOf(abs(p - heard), abs(n - heard)) <= RefinePolicy.MAX_SHIFT_MS
        }
        val heardThere = decisive.map { heardStarts[it] }
        val pickScore = score(decisive.map { pick[it] }, heardThere)
        val noneScore = score(decisive.map { none[it] }, heardThere)
        return pickScore.matched >= MIN_MATCHED_LINES && !belowThreshold(pickScore) &&
            clearlyBetter(pickScore, noneScore)
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
