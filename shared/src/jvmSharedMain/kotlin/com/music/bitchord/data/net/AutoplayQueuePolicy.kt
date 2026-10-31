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
 * Media3's repeat-mode values, repeated here so this policy stays pure and
 * compiles without Android. The numbers are [androidx.media3.common.Player]'s.
 */
const val AUTOPLAY_REPEAT_MODE_OFF = 0
const val AUTOPLAY_REPEAT_MODE_ONE = 1
const val AUTOPLAY_REPEAT_MODE_ALL = 2

/**
 * Whether a radio request is needed to keep AutoPlay's tail filled.
 *
 * [queuedAhead] counts *every* track after the current one, not only the
 * AutoPlay tier: a playlist with six songs left needs no suggestions yet, and
 * counting only suggestions made the app fetch a station for every track it
 * played (nine tracks, sixteen radio requests in one measured run).
 *
 * Repeat-one is deliberately not part of this answer. The listener's rule is
 * that AutoPlay on means the queue is filled; repeat-one coexists with it
 * because the current track simply loops while the tail is prepared behind it.
 * Repeat-all is different — it owns the whole queue and AutoPlay is switched
 * off when it is chosen — so it still stops the top-up.
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
 * Whether the AutoPlay switch is drawn as on.
 *
 * Repeat-all and AutoPlay are mutually exclusive; when both somehow arrive
 * from an older preference file the player still must not advertise a filling
 * switch while repeat-all is holding the queue still.
 */
fun autoplayShownEnabled(autoplay: Boolean, repeatMode: Int): Boolean =
    autoplay && repeatMode != AUTOPLAY_REPEAT_MODE_ALL

/** What the queue's AutoPlay row says beneath its heading. */
enum class AutoplayQueueDescription { QUEUED, EMPTY, DISABLED, WAITING }

/**
 * Chooses the AutoPlay row's subtitle.
 *
 * "No recommendations found" is reserved for a request that really finished
 * with zero songs for the track now playing. An empty tail on its own means
 * nothing — it is also what the queue looks like before the first request has
 * finished — so anything else reads as waiting rather than as a failure.
 */
fun autoplayQueueDescription(
    hasTracks: Boolean,
    autoplayEnabled: Boolean,
    currentSeedId: String?,
    emptyRequestSeedId: String?,
): AutoplayQueueDescription = when {
    // Off wins over rows already queued: repeat-all keeps the AutoPlay rows it
    // found but adds no more, and the row must say so.
    !autoplayEnabled -> AutoplayQueueDescription.DISABLED
    hasTracks -> AutoplayQueueDescription.QUEUED
    currentSeedId != null && currentSeedId == emptyRequestSeedId -> AutoplayQueueDescription.EMPTY
    else -> AutoplayQueueDescription.WAITING
}

/** The state AutoPlay and repeat-all take when the AutoPlay switch is pressed. */
data class AutoplayToggleResult(
    val autoplay: Boolean,
    val repeatMode: Int,
    val heldAutoplay: Boolean?,
)

/**
 * What pressing AutoPlay means for the two settings.
 *
 * Turning it on is the listener asking for the queue to be filled, so repeat-all
 * — which loops the queue instead — is released in the same gesture. Turning it
 * off is a deliberate choice, so any value repeat-all was holding is dropped:
 * leaving repeat-all later must not switch AutoPlay back on behind their back.
 */
fun autoplayToggleResult(
    autoplay: Boolean,
    repeatMode: Int,
    heldAutoplay: Boolean?,
): AutoplayToggleResult = if (!autoplay) {
    AutoplayToggleResult(
        autoplay = true,
        repeatMode = if (repeatMode == AUTOPLAY_REPEAT_MODE_ALL) {
            AUTOPLAY_REPEAT_MODE_OFF
        } else {
            repeatMode
        },
        heldAutoplay = null,
    )
} else {
    AutoplayToggleResult(autoplay = false, repeatMode = repeatMode, heldAutoplay = null)
}

/** The state AutoPlay takes when the repeat mode changes. */
data class RepeatModeAutoplayResult(
    val autoplay: Boolean,
    val heldAutoplay: Boolean?,
)

/**
 * What a repeat-mode change means for AutoPlay.
 *
 * Entering repeat-all remembers whether AutoPlay was on and turns it off.
 * Leaving repeat-all puts that remembered value back, so a listener who only
 * borrowed repeat-all gets their tail back, while one who had AutoPlay off
 * before — or switched it off during the loop — stays off. Moving between
 * repeat-one and off does not change AutoPlay at all.
 */
fun repeatModeAutoplayResult(
    previousRepeatMode: Int,
    repeatMode: Int,
    autoplay: Boolean,
    heldAutoplay: Boolean?,
): RepeatModeAutoplayResult {
    val enteringRepeatAll =
        repeatMode == AUTOPLAY_REPEAT_MODE_ALL && previousRepeatMode != AUTOPLAY_REPEAT_MODE_ALL
    val leavingRepeatAll =
        repeatMode != AUTOPLAY_REPEAT_MODE_ALL && previousRepeatMode == AUTOPLAY_REPEAT_MODE_ALL
    return when {
        enteringRepeatAll -> RepeatModeAutoplayResult(
            autoplay = false,
            heldAutoplay = autoplay,
        )
        leavingRepeatAll -> RepeatModeAutoplayResult(
            autoplay = heldAutoplay ?: autoplay,
            heldAutoplay = null,
        )
        else -> RepeatModeAutoplayResult(autoplay = autoplay, heldAutoplay = heldAutoplay)
    }
}

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
