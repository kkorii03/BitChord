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
 * Whether a batch appended at the end of a finished queue has to be started
 * explicitly.
 *
 * ExoPlayer stays in `STATE_ENDED` when items are added after the last one has
 * played; the new rows are queued but nothing seeks to them, so AutoPlay could
 * fill the tail and still leave the listener in silence until they pressed
 * play. Resuming is only right for a queue that actually ran out while the
 * listener still wanted sound: [playWhenReady] is false after a pause, and a
 * top-up that landed while some other row was current must not move the
 * playhead.
 *
 * @param playbackEnded the player was in `STATE_ENDED` before the append.
 * @param playWhenReady the listener had not paused.
 * @param appendedAtEnd the new rows were added after the current last row.
 */
fun shouldResumeAfterTopUp(
    playbackEnded: Boolean,
    playWhenReady: Boolean,
    appendedAtEnd: Boolean,
): Boolean = playbackEnded && playWhenReady && appendedAtEnd
