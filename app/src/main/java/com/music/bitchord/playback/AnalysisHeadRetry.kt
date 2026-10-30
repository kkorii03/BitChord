package com.music.bitchord.playback

/**
 * Whether a fresh analysis-head round is worth starting after the last one
 * ended with [heldAfter] bytes on disk, [heldBefore] having been there when it
 * started.
 *
 * `requestAnalysisHead` sizes a round from the track's real length — one
 * resolve, one range, one encoding — so a round that adds nothing will add
 * nothing again: the same URL, the same range, the same refusal. Retrying it
 * used to produce three "came up short: held=0 want=<whole file>" rounds
 * within two seconds for one track and no measurement. Progress (the head
 * grew, but is still short of [minBytes]) is the only reason to try again,
 * and only while fewer than [maxRounds] have been started.
 *
 * Pure so the rule is unit-tested without the cache, Android or a network.
 */
internal fun analysisHeadShouldRetry(
    heldBefore: Long,
    heldAfter: Long,
    roundsStarted: Int,
    maxRounds: Int,
    minBytes: Long,
): Boolean = roundsStarted < maxRounds && heldAfter < minBytes && heldAfter > heldBefore

/**
 * Whether a fresh analysis-head round may be claimed right now.
 *
 * A track already requested this session is not asked again, and a track whose
 * cache entry another writer is filling must not race it. A track with a round
 * still in the air must not be re-claimed either: resetting the claim while its
 * round runs (discard/remove/purge clears [analysisHeads] but not the in-flight
 * set) used to let a tick put the claim back, fail the in-flight `add`, and
 * return — leaving a claim with no round behind it, so the analyzer never
 * retried. The second case is the one that used to dead-end for another
 * reason: the claim was recorded *before* the writer check, so a deferred round
 * left the track marked "already asked" and no later tick could ever start the
 * fetch once the writer let go. Deferral must therefore not consume the claim.
 */
internal fun analysisHeadMayClaim(
    alreadyRequested: Boolean,
    anotherWriterHas: Boolean,
    inFlight: Boolean,
): Boolean = !alreadyRequested && !anotherWriterHas && !inFlight

/**
 * As [analysisHeadMayClaim], but a claim kept while the entry was held by a
 * live reader may start its deferred round once that reader lets go.
 *
 * Releasing the claim on `HeldElsewhere` looked like it cured a dead end, but
 * the idle tick asks again every 250 ms: for as long as the player or
 * read-ahead held the entry the analyzer launched a coroutine, re-resolved the
 * content length and retried the locked `removeResource` four times a second.
 * Keeping the claim turns that into one event-driven retry at release time.
 * The flag still prevents a second round for a claim that was not held.
 */
internal fun analysisHeadMayClaimOrRetry(
    alreadyRequested: Boolean,
    heldForReader: Boolean,
    anotherWriterHas: Boolean,
    inFlight: Boolean,
): Boolean = !inFlight && !anotherWriterHas && (!alreadyRequested || heldForReader)

/**
 * What an analysis-head round should do after inspecting the entry it is about
 * to write.
 *
 * [clearPartialHead] used to answer a plain boolean, so "already complete" and
 * "a live reader holds a partial entry" were both `false`. The caller returned
 * from the coroutine for either, leaving the one-round claim set. In the second
 * case the head stayed part-written and claimed for the whole session — the
 * permanent `mix=none` dead-end this round removes.
 */
internal enum class AnalysisHeadClear {
    /** The entry is empty, or its partial prefix was successfully thrown away. */
    Proceed,

    /** The entry already covers what the round would fetch, so no fetch is needed. */
    AlreadyComplete,

    /** A partial entry could not be removed because a reader has it open. */
    HeldElsewhere,
}

internal fun analysisHeadClearOutcome(
    held: Long,
    want: Long,
    removalSucceeded: Boolean,
): AnalysisHeadClear = when {
    held <= 0L -> AnalysisHeadClear.Proceed
    held >= want -> AnalysisHeadClear.AlreadyComplete
    removalSucceeded -> AnalysisHeadClear.Proceed
    else -> AnalysisHeadClear.HeldElsewhere
}
