package com.music.bitchord.ko

import androidx.media3.common.Player
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigins
import com.music.bitchord.playback.ContinuationState

/**
 * Whether a continuation reaching a terminal state has to make AutoPlay look
 * at the queue again right away.
 *
 * A bounded playlist that runs out of continuation while its last row is
 * already the current one has no upcoming item transition left to reconsider
 * AutoPlay. Without this, an enabled AutoPlay queue stayed empty until the
 * listener touched something. The terminal snapshot is the event; no timer is
 * involved.
 */
internal fun autoplayNeedsContinuationTerminalRecheck(state: ContinuationState): Boolean =
    state == ContinuationState.EXHAUSTED || state == ContinuationState.BLOCKED

/**
 * The seed to keep after an AutoPlay load came back empty.
 *
 * The seed normally stays latched to suppress duplicate loads from the several
 * callbacks one event can produce. Once the bounded empty-response retries are
 * used up it must be released, or every later pause/resume and queue edit is
 * skipped as "same seed" and AutoPlay stays on with nothing queued. Clearing
 * only when it still names this track keeps a newer load's seed intact.
 */
internal fun autoplaySeedAfterExhaustedRetries(
    seed: String?,
    currentVideoId: String,
    retriesRemaining: Int,
): String? = if (retriesRemaining <= 0 && seed == currentVideoId) null else seed

fun needsYoutubeSeedLookup(song: Song): Boolean =
    !SongOrigins.hasYouTubeIdentity(song.videoId)

/**
 * Whether a repeat-mode change has to release AutoPlay's seed latch.
 *
 * While repeat-one loops the current track the top-up is skipped, but the seed
 * stays latched to that track. The reload that runs when repeat-one ends then
 * sees the same seed and returns without fetching, so the promised refill never
 * happens. Clearing the seed on the way out is the event that makes it happen.
 *
 * Leaving repeat-all has the same latch problem: its top-ups were skipped while
 * the loop owned the queue, so the seed may name a track whose last request
 * returned nothing. Restoring AutoPlay after the loop has to be able to ask
 * again instead of stopping at "same-seed".
 */
internal fun repeatModeSeedShouldClear(previousMode: Int, currentMode: Int): Boolean =
    (previousMode == Player.REPEAT_MODE_ONE || previousMode == Player.REPEAT_MODE_ALL) &&
        currentMode != previousMode

/**
 * Whether a refill needs to start a load of its own.
 *
 * [autoplayLoadJob] is only a handle to the newest load, and a load started
 * for the previous track can still be active when the last row fails. That
 * job will exit on its own media-id check, but treating it as "the refill is
 * already in flight" left this track with no load at all. A load only counts
 * when it was started for the same media id.
 */
internal fun autoplayRefillNeedsLoad(
    loadActive: Boolean,
    loadedSeed: String?,
    mediaId: String,
): Boolean = !loadActive || loadedSeed != mediaId
