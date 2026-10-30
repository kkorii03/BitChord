package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song

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
 *
 * [repeatOne] is not the same question as [repeatAll], but it has the same
 * answer for a top-up: the player is about to repeat the current track, so no
 * suggestion can be reached until the mode changes. Fetching a station on every
 * loop was pure battery and radio use. Turning repeat-one off calls the top-up
 * again, so the queue is filled the moment it can be heard.
 */
fun autoplayTopUpNeeded(
    enabled: Boolean,
    repeatAll: Boolean,
    queuedAhead: Int,
    minAhead: Int = AUTOPLAY_MIN_QUEUED_AHEAD,
    repeatOne: Boolean = false,
): Boolean = enabled && !repeatAll && !repeatOne && queuedAhead < minAhead

/**
 * The suggestions one top-up may actually put in the queue.
 *
 * A suggestion already waiting after the current track buys nothing, and the
 * queue keeps at most [maxAutoplay] suggestions ahead. Returned rather than
 * applied so the caller can record only what was really queued as "already
 * suggested" — writing off a row that never landed made every later top-up
 * skip it too.
 */
fun autoplayTopUpSelection(
    suggestions: List<Song>,
    waitingIds: Set<String>,
    waitingAutoplay: Int,
    maxAutoplay: Int,
): List<Song> = suggestions
    .filterNot { it.videoId in waitingIds }
    .take((maxAutoplay - waitingAutoplay).coerceAtLeast(0))
