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
