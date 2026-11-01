package com.music.bitchord.ko

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.CrossfadeSkipReason
import com.music.bitchord.playback.isVideoOrigin

/**
 * [KO] crossfade-skip
 *
 * Pair-level skip rules before Automix arms: a pair already refused is not
 * retried every tick, an upload read-ahead marked unplayable is left to the
 * gapless advance, and a known-incompatible format never holds the outgoing
 * tail for a blend that cannot render.
 *
 * @return true when the caller should stop planning this pair.
 */
internal fun CrossfadeController.koSkipAutoTransition(player: ExoPlayer): Boolean {
    val currentItem = player.currentMediaItem ?: return true
    val nextIndex = player.nextMediaItemIndex
    if (nextIndex == C.INDEX_UNSET) return true
    val nextItem = player.getMediaItemAt(nextIndex)

    // A pair this outgoing track has already failed to arm is left to the
    // ordinary gapless advance. Without this, a cold stream that missed the
    // deadline is retried on every idle tick, each attempt opening a decoder
    // and each miss re-arming the outgoing filter for a blend that is never
    // going to happen.
    val pair = currentItem.mediaId to nextItem.mediaId
    if (ko.skippedPair == pair) {
        AppSettings.smartTransitionWindow.value = null
        return true
    }
    if (ko.skippedPair?.first != null && ko.skippedPair?.first != currentItem.mediaId) {
        ko.skippedPair = null
    }

    // Read-ahead has already settled that nobody can serve this upload.
    // Arming would spend the standby decoder on it and hold the outgoing
    // track's tail for a blend that can only end in the failure path.
    if (StreamResolver.isUnplayable(nextItem.mediaId)) {
        rememberSkipped(pair, CrossfadeSkipReason.UNPLAYABLE, currentItem, nextItem)
        AppSettings.smartTransitionWindow.value = null
        AppSettings.smartMixInProgress.value = false
        return true
    }

    if (!canMix(currentItem, nextItem)) {
        rememberSkipped(pair, CrossfadeSkipReason.FORMAT_MISMATCH, currentItem, nextItem)
        AppSettings.smartTransitionWindow.value = null
        AppSettings.smartMixInProgress.value = false
        return true
    }
    return false
}

/** [KO] crossfade-video-fallback */
internal data class KoAutoTransitionPlan(
    val smartPass: Boolean,
    val fallbackLength: Boolean,
    val fadeMs: Long,
)

/**
 * [KO] crossfade-video-fallback
 *
 * Automix never plans a pair with a video row unless the fork setting allows
 * it. Such a pair still gets the plain fade: the slider's length, or Automix's
 * own fallback length when the slider is off.
 */
internal fun CrossfadeController.koChooseAutoTransition(
    player: ExoPlayer,
    duration: Long,
): KoAutoTransitionPlan? {
    val videoPair = !AppSettings.automixVideos.value && (
        player.currentMediaItem?.isVideoOrigin == true ||
            player.getMediaItemAt(player.nextMediaItemIndex).isVideoOrigin
        )
    if (AppSettings.smartFadeEnabled.value && !videoPair) {
        return KoAutoTransitionPlan(smartPass = true, fallbackLength = false, fadeMs = 0L)
    }
    val automixVideoPair = AppSettings.smartFadeEnabled.value && videoPair
    if (automixVideoPair) {
        AppSettings.smartTransitionWindow.value = null
        AppSettings.smartMixInProgress.value = false
    }

    val fallbackLength = automixVideoPair && configuredFadeMs() <= 0L
    val fade = if (fallbackLength) {
        minOf((CrossfadeController.DEFAULT_SMART_FALLBACK_SECONDS * 1000).toLong(), duration / 3).coerceAtLeast(0L)
    } else {
        if (configuredFadeMs() <= 0L) return null
        fadeFor(duration)
    }
    if (fade <= 0L) return null
    return KoAutoTransitionPlan(smartPass = false, fallbackLength = fallbackLength, fadeMs = fade)
}

/**
 * [KO] crossfade-same-item
 *
 * A queue that points at the song already playing has nothing to fade into.
 * This is also what a natural advance during an earlier ARMING leaves behind,
 * so it is refused before touching either player.
 */
internal fun CrossfadeController.koBeginRejectsSameItem(out: ExoPlayer): Boolean {
    val nextIndex = out.nextMediaItemIndex
    if (nextIndex == C.INDEX_UNSET) return false
    val nextItem = out.getMediaItemAt(nextIndex)
    val currentItem = out.currentMediaItem ?: return false
    if (nextItem.mediaId != currentItem.mediaId) return false
    rememberSkipped(
        currentItem.mediaId to nextItem.mediaId,
        CrossfadeSkipReason.SAME_ITEM,
        currentItem,
        nextItem,
    )
    releaseHold()
    return true
}
