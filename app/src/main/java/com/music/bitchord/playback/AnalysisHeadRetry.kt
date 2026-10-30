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
 * cache entry another writer is filling must not race it. The second case is
 * the one that used to dead-end: the claim was recorded *before* the writer
 * check, so a deferred round left the track marked "already asked" and no
 * later tick could ever start the fetch once the writer let go. Deferral must
 * therefore not consume the claim.
 */
internal fun analysisHeadMayClaim(
    alreadyRequested: Boolean,
    anotherWriterHas: Boolean,
): Boolean = !alreadyRequested && !anotherWriterHas
