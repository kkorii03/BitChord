package com.music.bitchord.data.net

/**
 * How many tracks after the current one make an AutoPlay top-up unnecessary.
 *
 * AutoPlay used to fetch a radio batch on every track change, even when ten
 * suggestions were already waiting; the queue only ever lost one track per
 * change, so the radio request bought nothing. Five is the point where the
 * remaining queue is still long enough that the listener cannot reach its end
 * before the next track change, where the top-up will be reconsidered anyway.
 */
const val AUTOPLAY_MIN_QUEUED_AHEAD = 5

/**
 * Whether a radio request is needed to keep AutoPlay's tail filled.
 *
 * [queuedAhead] counts *every* track after the current one, not only the
 * AutoPlay tier: a playlist with six songs left needs no suggestions yet, and
 * counting only suggestions made the app fetch a station for every track it
 * played (nine tracks, sixteen radio requests in one measured run).
 *
 * Pure so the rule is unit-tested without a player, Android or a network.
 */
fun autoplayTopUpNeeded(
    enabled: Boolean,
    repeatAll: Boolean,
    queuedAhead: Int,
    minAhead: Int = AUTOPLAY_MIN_QUEUED_AHEAD,
): Boolean = enabled && !repeatAll && queuedAhead < minAhead

/**
 * Whether a repeat transition exposed a player looping a queue the listener
 * asked it not to loop.
 *
 * Repeat modes are Media3's values (0 off, 1 one, 2 all), passed as plain ints
 * so this module needs no player dependency. A crossfade used to copy the mode
 * onto the standby only when it armed; a change made while the blend was
 * running reached the outgoing player alone, and the incoming one became the
 * session still repeating. With a one-track queue that is `idx=0/1
 * reason=REPEAT` forever, while [autoplayTopUpNeeded] refuses to fill it
 * because it reads the stale repeat-all.
 */
fun staleRepeatNeedsAutoplay(
    transitionReasonRepeat: Boolean,
    currentIndex: Int,
    itemCount: Int,
    playerRepeatMode: Int,
    settingsRepeatMode: Int,
): Boolean = transitionReasonRepeat &&
    itemCount > 0 &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1 &&
    playerRepeatMode != settingsRepeatMode
