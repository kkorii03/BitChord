package com.music.bitchord.ko

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.isVideoRow
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
    if (AutomixWorkPolicy.videoPairNeedsPlainCrossfade(
            AppSettings.automixVideos.value,
            currentItem.isVideoRow,
            nextItem.isVideoRow,
        )
    ) {
        return true
    }
    val automix = AppSettings.smartFadeEnabled.value
    val nextDurationMs = nextItemDurationMs(nextIndex, nextItem)
    val nextTarget = AutomixWorkPolicy.nextAnalysisTarget(
        nextId = nextItem.mediaId,
        nextAnalysed = analysisWindowMeasured(nextItem, nextDurationMs, AnalysisWindow.HEAD),
        automixEnabled = automix,
    )
    nextTarget?.let {
        requestAnalysis(nextItem, nextDurationMs, it.window)
    }
    val outgoingTarget = AutomixWorkPolicy.outgoingAnalysisTarget(
        currentId = currentItem.mediaId,
        currentAnalysed = analysisWindowMeasured(currentItem, duration, AnalysisWindow.TAIL),
        automixEnabled = automix,
    )
    outgoingTarget?.let { requestAnalysis(currentItem, duration, it.window) }
    return true
}
