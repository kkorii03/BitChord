package com.music.bitchord.ko

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.playback.CrossfadeArmPolicy
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.CrossfadeSkipReason

/**
 * [KO] crossfade-arming
 *
 * The fork's arming guards: the outgoing player may run out before the standby
 * is ready, and a pause parks the prepared arm instead of dropping it.
 */
internal fun CrossfadeController.koArmOutgoingMoved(out: ExoPlayer, into: ExoPlayer): Boolean {
    val outId = out.currentMediaItem?.mediaId
    val inId = into.currentMediaItem?.mediaId
    if (out.currentMediaItemIndex != outgoingItemIndex || (outId != null && outId == inId)) {
        bail(CrossfadeSkipReason.OUTGOING_MOVED)
        return true
    }
    return false
}

/**
 * @return true when the arm is parked and the caller should wait for a resume.
 */
internal fun CrossfadeController.koArmPauseOrRestore(out: ExoPlayer, into: ExoPlayer): Boolean {
    if (!out.playWhenReady) {
        if (ko.armPausedAt == 0L) ko.armPausedAt = SystemClock.elapsedRealtime()
        return true
    }
    if (ko.armPausedAt != 0L) {
        val pausedFor = SystemClock.elapsedRealtime() - ko.armPausedAt
        ko.armPausedAt = 0L
        armDeadline = CrossfadeArmPolicy.armDeadlineAfterPause(armDeadline, pausedFor)
        ko.armStartedAt += pausedFor.coerceAtLeast(0L)
    }
    // An earlier interrupted transition must not leave the audible track at a
    // gain the listener cannot hear. Nothing in ARMING automates this player's
    // volume; full level is the only correct state until FADING.
    if (out.volume <= 0.01f) {
        KoCrossfadeLog.armVolumeRestored("BitChordCrossfade", out.volume)
        out.volume = 1f
    }
    return false
}

/** [KO] crossfade-skip-log — remembers a refused pair once, or logs the abort. */
internal fun CrossfadeController.koRememberBailReason(
    reason: CrossfadeSkipReason,
    outId: String?,
    inId: String?,
) {
    if (phase == CrossfadeController.Phase.ARMING && outId != null && inId != null) {
        rememberSkipped(
            outId to inId,
            reason,
            outgoing?.currentMediaItem,
            incoming?.currentMediaItem,
        )
    } else {
        logSkipped(outId, inId, reason)
    }
}

/** [KO] crossfade-bail-step — one ramp sample for both sides of an abort. */
internal fun CrossfadeController.koDriveBailStep(
    leaving: ExoPlayer,
    now: Long,
    progress: Float,
) {
    val survivorGain = KoCrossfadeGain.bailSurvivor(ko.bailSurvivorFromGain, progress)
    ko.bailSurvivor?.volume = survivorGain
    val fall = when {
        leaving === outgoing && ranOn(leaving) -> 0f
        ko.bailSilenceLeaving -> {
            if (ko.skipSilenceStartedAt == 0L) ko.skipSilenceStartedAt = now
            KoCrossfadeGain.skipSilence(
                bailFromGain,
                now - ko.skipSilenceStartedAt,
                KoCrossfadeGain.SKIP_SILENCE_MS,
            )
        }
        else -> bailFromGain * KoCrossfadeGain.fall(progress)
    }
    leaving.volume = fall
    onBlendHeadroom(KoCrossfadeGain.headroomFor(survivorGain, fall))
}
