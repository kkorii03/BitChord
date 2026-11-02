package com.music.bitchord.ko

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.CrossfadeArmPolicy
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.applyPlaybackSpeed

/**
 * [KO] crossfade-rearm
 *
 * Reloads an armed standby when the verified lossless copy lands between arming
 * and the fade. The controller keeps only the public entry point.
 */
internal fun CrossfadeController.koRearmIncoming(
    replacement: MediaItem,
    index: Int,
): Boolean {
    if (!isArming()) return false
    val out = outgoing ?: return false
    val into = incoming ?: return false
    if (index !in 0 until out.mediaItemCount) return false
    if (out.getMediaItemAt(index).mediaId != replacement.mediaId) return false
    if (into.currentMediaItem?.localConfiguration?.uri == replacement.localConfiguration?.uri) {
        return false
    }
    val items = (0 until out.mediaItemCount).map { i ->
        if (i == index) replacement else out.getMediaItemAt(i)
    }
    into.stop()
    into.clearMediaItems()
    into.skipSilenceEnabled = out.skipSilenceEnabled
    into.repeatMode = out.repeatMode
    into.shuffleModeEnabled = out.shuffleModeEnabled
    into.applyPlaybackSpeed((AppSettings.playbackSpeed.value * incomingPlaybackRate).toFloat())
    into.volume = 0f
    into.setMediaItems(items, index, incomingCueTimeMs)
    ko.incomingItemIndex = index
    val after = into.nextMediaItemIndex
    val afterId = if (after == C.INDEX_UNSET) null else items.getOrNull(after)?.mediaId
    onArmIncoming(replacement, afterId)
    into.playWhenReady = false
    into.prepare()
    ko.armStartedAt = SystemClock.elapsedRealtime()
    ko.armPausedAt = 0L
    armDeadline = CrossfadeArmPolicy.armDeadlineFrom(ko.armStartedAt, rampSteps * rampStepMs)
    return true
}
