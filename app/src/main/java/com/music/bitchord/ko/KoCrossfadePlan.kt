package com.music.bitchord.ko

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.CrossfadeSkipReason
import com.music.bitchord.playback.isVideoRow
import com.music.bitchord.playback.smart.AutomixWorkPolicy

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

/**
 * [KO] automix-preview-log
 *
 * Why the controller has no Automix plan for the pair playing now, in the order
 * [CrossfadeController.planAutoTransition] gives up — or null when it should
 * have one. Pure, so the order is tested.
 */
internal object KoMixPreviewWhy {
    fun reason(
        automixOn: Boolean,
        inParty: Boolean,
        bitPerfect: Boolean,
        hasNext: Boolean,
        durationKnown: Boolean,
        pairSkipped: Boolean,
        nextUnplayable: Boolean,
        formatsMix: Boolean,
        repeatOne: Boolean,
        videoPairPlain: Boolean,
    ): String? = when {
        !automixOn -> "automix-off"
        inParty -> "listen-together"
        bitPerfect -> "bit-perfect"
        !hasNext -> "no-next-track"
        !durationKnown -> "no-duration-yet"
        pairSkipped -> "pair-skipped"
        nextUnplayable -> "next-unplayable"
        !formatsMix -> "format-mismatch"
        repeatOne -> "repeat-one"
        videoPairPlain -> "video-pair-plain-crossfade"
        else -> null
    }

    /**
     * Reasons the playing path refuses a pair before planning but the paused
     * marker path used to plan anyway, so a paused track (and the mix popup
     * opened on it) showed a mix that was never going to play.
     */
    fun refusesPausedMarker(reason: String?): Boolean =
        reason == "bit-perfect" || reason == "pair-skipped" || reason == "next-unplayable" || reason == "format-mismatch"
}

/** [KO] automix-preview-log: [KoMixPreviewWhy.reason] read off the session player. */
internal fun CrossfadeController.koNoPlanReason(player: ExoPlayer): String? {
    val current = player.currentMediaItem
    val nextIndex = player.nextMediaItemIndex
    val next = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
    val duration = player.duration
    return KoMixPreviewWhy.reason(
        automixOn = AppSettings.smartFadeEnabled.value,
        inParty = ListenTogether.state.value.inParty,
        bitPerfect = BitPerfectOutput.status.value.active,
        hasNext = current != null && next != null,
        // A paused track plans its marker off the queued runtime instead.
        durationKnown = !player.isPlaying || (duration != C.TIME_UNSET && duration > 0L),
        pairSkipped = current != null && next != null && ko.skippedPair == (current.mediaId to next.mediaId),
        nextUnplayable = next != null && StreamResolver.isUnplayable(next.mediaId),
        formatsMix = current == null || next == null || canMix(current, next),
        repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
        videoPairPlain = AutomixWorkPolicy.videoPairNeedsPlainCrossfade(
            automixVideos = AppSettings.automixVideos.value,
            currentIsVideo = current?.isVideoRow == true,
            nextIsVideo = next?.isVideoRow == true,
        ),
    )
}

/**
 * [KO] crossfade-skip
 *
 * The paused marker plans through [CrossfadeController.considerSmartTransition]
 * directly, past the pair rules [koSkipAutoTransition] applies while playing.
 * A pair those rules refuse gets no marker and no popup plan either.
 */
internal fun CrossfadeController.koPausedMarkerRefused(player: ExoPlayer): Boolean {
    if (!KoMixPreviewWhy.refusesPausedMarker(koNoPlanReason(player))) return false
    AppSettings.smartTransitionWindow.value = null
    return true
}

/**
 * [KO] automix-preview-log
 *
 * One BitChord line when the mix popup is open and no plan reached it, naming
 * why, so a phone log says whether "예정된 자동 믹스 전환이 없습니다" was the
 * rules or a planner that never ran. Once per pair and reason; nothing while
 * the popup is closed.
 */
internal fun CrossfadeController.koLogMissingPreview() {
    if (!AppSettings.mixViewOpen.value ||
        AppSettings.smartMixView.value != null ||
        AppSettings.smartMixPreview.value != null
    ) {
        ko.lastPreviewWhy = ""
        return
    }
    val player = active()
    val current = player.currentMediaItem
    val nextIndex = player.nextMediaItemIndex
    val nextId = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex).mediaId
    val why = koNoPlanReason(player) ?: when {
        phase != CrossfadeController.Phase.IDLE -> "phase-${phase.name.lowercase()}"
        else -> "planner-stopped-early playing=${player.isPlaying} pos=${player.currentPosition}"
    }
    val pair = "${current?.mediaId}->$nextId"
    val key = "$pair|${why.substringBefore(" pos=")}"
    if (key == ko.lastPreviewWhy) return
    ko.lastPreviewWhy = key
    TrackLog.d("BitChord", "automix-preview: none for $pair because $why", about = current?.mediaId)
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
    val videoPair = AutomixWorkPolicy.videoPairNeedsPlainCrossfade(
        automixVideos = AppSettings.automixVideos.value,
        currentIsVideo = player.currentMediaItem?.isVideoRow == true,
        nextIsVideo = player.getMediaItemAt(player.nextMediaItemIndex).isVideoRow,
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
