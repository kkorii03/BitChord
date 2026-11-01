package com.music.bitchord.ko

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.isVideoOrigin
import com.music.bitchord.playback.smart.AnalysisWindow
import com.music.bitchord.playback.smart.AutomixWorkPolicy

/**
 * [KO] crossfade-analysis
 *
 * Requests only the immediate transition's two useful windows: the incoming
 * track's opening and the outgoing track's tail. Each is skipped once measured,
 * so a track is decoded once rather than on every tick.
 *
 * Returns true once it has handled the request; [CrossfadeController.requestAnalysisAround]
 * is only a [KO] hook around this function.
 */
internal fun CrossfadeController.koRequestAnalysisAround(player: ExoPlayer, duration: Long): Boolean {
    val currentItem = player.currentMediaItem ?: return true
    val nextIndex = player.nextMediaItemIndex
    if (nextIndex == C.INDEX_UNSET) return true
    val nextItem = player.getMediaItemAt(nextIndex)
    if (!AppSettings.automixVideos.value && (currentItem.isVideoOrigin || nextItem.isVideoOrigin)) return true
    val automix = AppSettings.smartFadeEnabled.value
    val nextTarget = AutomixWorkPolicy.nextAnalysisTarget(
        nextId = nextItem.mediaId,
        nextAnalysed = analysisWindowMeasured(nextItem, AnalysisWindow.HEAD),
        automixEnabled = automix,
    )
    nextTarget?.let {
        requestAnalysis(nextItem, nextItemDurationMs(nextIndex, nextItem), it.window)
    }
    val outgoingTarget = AutomixWorkPolicy.outgoingAnalysisTarget(
        currentId = currentItem.mediaId,
        currentAnalysed = analysisWindowMeasured(currentItem, AnalysisWindow.TAIL),
        automixEnabled = automix,
    )
    outgoingTarget?.let { requestAnalysis(currentItem, duration, it.window) }
    return true
}
