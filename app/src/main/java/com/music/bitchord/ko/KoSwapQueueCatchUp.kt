package com.music.bitchord.ko

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.playback.HqSwapPolicy
import com.music.bitchord.playback.handoffKey

/**
 * [KO] review V-3: a swap fills its standby from a copy of the queue, then waits
 * (up to 15 s for a late handover, about 8 s for a lossless one) and fades before
 * adopting it. "Play next", an autoplay append, or a next row turned from MV into
 * its song in that time land on the session player, so adopting the copy as it
 * stands drops the added song or brings the MV back. This brings the standby's
 * rows on both sides of [index] up to the live queue right before the adoption,
 * with the modes the listener may have changed meanwhile. The current row is left
 * alone: it differs on purpose, being the swapped-in item, and is what is playing.
 *
 * Returns the edits applied, or null without touching anything when the standby
 * is no longer on [index] (it ran on to another row), which the caller must not adopt.
 */
internal fun koCatchUpStandbyQueue(
    active: ExoPlayer,
    standby: ExoPlayer,
    index: Int,
): HqSwapPolicy.QueueEdits? {
    if (standby.currentMediaItemIndex != index) return null
    standby.repeatMode = active.repeatMode
    standby.shuffleModeEnabled = active.shuffleModeEnabled
    val edits = HqSwapPolicy.queueEdits(
        activeKeys = (0 until active.mediaItemCount).map { active.getMediaItemAt(it).swapQueueKey() },
        standbyKeys = (0 until standby.mediaItemCount).map { standby.getMediaItemAt(it).swapQueueKey() },
        currentIndex = index,
    )
    if (edits.tail) {
        standby.replaceMediaItems(
            index + 1,
            standby.mediaItemCount,
            (index + 1 until active.mediaItemCount).map { active.getMediaItemAt(it) },
        )
    }
    if (edits.head) {
        standby.replaceMediaItems(0, index, (0 until index).map { active.getMediaItemAt(it) })
    }
    return edits
}

/**
 * A queue row's identity for catching edits made during a swap. The URI is part
 * of it because a row can be replaced in place under the same entry id.
 */
internal fun MediaItem.swapQueueKey(): String =
    "${handoffKey()}|$mediaId|${localConfiguration?.uri}"
