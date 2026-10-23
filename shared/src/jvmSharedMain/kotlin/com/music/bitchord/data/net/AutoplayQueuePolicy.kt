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
