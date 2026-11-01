package com.music.bitchord.ko

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.HandoffQueuePolicy
import com.music.bitchord.playback.handoffKey

/**
 * [KO] crossfade-queue
 *
 * Queue edits during an arm or blend: the incoming row has to still be the next
 * one, and the standby's tail is rebuilt from the outgoing player at handoff.
 * Both are fork additions; upstream's controller only calls in.
 */
internal fun CrossfadeController.koIncomingQueueChanged(): Boolean {
    if (handedOff || (phase != CrossfadeController.Phase.ARMING && phase != CrossfadeController.Phase.FADING)) return false
    val out = outgoing ?: return false
    val into = incoming ?: return false
    val incomingKey = into.currentMediaItem?.handoffKey() ?: return false
    val keys = (0 until out.mediaItemCount).map { out.getMediaItemAt(it).handoffKey() }
    if (!HandoffQueuePolicy.incomingStillNext(
            outgoingKeys = keys,
            outgoingCurrentIndex = out.currentMediaItemIndex,
            incomingKey = incomingKey,
            wrapsToStart = ko.incomingWrapsToStart,
        )
    ) {
        bail()
        return true
    }
    val mediaIds = (0 until out.mediaItemCount).map { out.getMediaItemAt(it).mediaId }
    if (HandoffQueuePolicy.incomingMediaReplaced(
            outgoingKeys = keys,
            outgoingMediaIds = mediaIds,
            incomingKey = incomingKey,
            incomingMediaId = into.currentMediaItem?.mediaId,
            incomingIndexHint = ko.incomingItemIndex,
            outgoingCurrentIndex = out.currentMediaItemIndex,
        )
    ) {
        val index = HandoffQueuePolicy.incomingIndex(
            outgoingKeys = keys,
            incomingKey = incomingKey,
            incomingIndexHint = ko.incomingItemIndex,
            outgoingCurrentIndex = out.currentMediaItemIndex,
        )
        val replacement = index?.let { out.getMediaItemAt(it) }
        if (phase == CrossfadeController.Phase.ARMING && index != null && replacement != null &&
            rearmIncoming(replacement, index)
        ) {
            wakeTicker()
            return true
        }
        bail()
        return true
    }
    return false
}

internal fun CrossfadeController.koReconcileQueue(out: ExoPlayer, into: ExoPlayer) {
    val incomingKey = into.currentMediaItem?.handoffKey() ?: return
    val outgoingKeys = (0 until out.mediaItemCount).map { out.getMediaItemAt(it).handoffKey() }
    val incomingIndex = HandoffQueuePolicy.incomingIndex(
        outgoingKeys = outgoingKeys,
        incomingKey = incomingKey,
        incomingIndexHint = ko.incomingItemIndex,
        outgoingCurrentIndex = out.currentMediaItemIndex,
    ) ?: return
    val intoCurrentIndex = into.currentMediaItemIndex
    if (intoCurrentIndex !in 0 until into.mediaItemCount) return
    val desiredTail = (incomingIndex + 1 until out.mediaItemCount).map { out.getMediaItemAt(it) }
    val currentTailKeys = (intoCurrentIndex + 1 until into.mediaItemCount)
        .map { into.getMediaItemAt(it).handoffKey() }
    if (currentTailKeys == desiredTail.map { it.handoffKey() }) return
    into.replaceMediaItems(intoCurrentIndex + 1, into.mediaItemCount, desiredTail)
    Log.d(TAG_QUEUE, "reconciled ${desiredTail.size} tail item(s) onto the incoming player")
}

private const val TAG_QUEUE = "BitChordCrossfade"
