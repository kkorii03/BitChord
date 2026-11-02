package com.music.bitchord.ko

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.CrossfadeArmPolicy
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.smart.AutomixWorkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong

/**
 * Emits whenever an analysis pass publishes a result. The idle ticker parks
 * between cues, so this is the event that lets a newly-landed analysis move
 * the planned cue without polling [com.music.bitchord.playback.smart.TrackAnalyzer].
 */
internal val koCrossfadeAnalysisCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

/**
 * [KO] crossfade-ticker
 *
 * Event-driven tick cadence: the loop sleeps until a player callback wakes it
 * instead of polling through a pause, and off screen the idle step is slower.
 */
internal fun CrossfadeController.koLaunchTicker(scope: CoroutineScope): Job =
    scope.launch {
        while (isActive) {
            tick()
            val step = koTickStepMs()
            if (step > 0L) {
                // The normal cadence, cut short by anything that made the next
                // step due sooner.
                withTimeoutOrNull(step) { ko.tickerWake.receive() }
            } else {
                // Nothing to advance: wait for the event that changes that.
                ko.tickerWake.receive()
            }
        }
    }

/**
 * Wakes the parked ticker when crossfade/Automix is switched, since an idle
 * tick no longer polls and a settings change would otherwise wait for the
 * next track.
 *
 * Playback speed belongs here too: the idle wait is media time, so a speed
 * change moves the real moment the cue arrives.
 */
internal fun CrossfadeController.koLaunchSettingsWake(scope: CoroutineScope): Job =
    scope.launch {
        combine(
            AppSettings.crossfadeSeconds,
            AppSettings.smartFadeEnabled,
            AppSettings.automixVideos,
            AppSettings.lowPower,
            AppSettings.playbackSpeed,
        ) { _, _, _, _, _ -> Unit }
            .drop(1)
            .collect { wakeTicker() }
    }

/**
 * Wakes the parked ticker when an analysis pass lands. Without this an
 * earlier analyzed cue (and the Automix marker) would stay at the fallback
 * until the horizon tick even though the plan just became available.
 */
internal fun CrossfadeController.koLaunchAnalysisWake(scope: CoroutineScope): Job =
    scope.launch {
        analysisCompleted.collect { wakeTicker() }
    }

/**
 * How long to wait before the next tick, or 0 to wait for an event.
 *
 * An armed or running transition still has to step at its own rate whatever
 * the player is doing. Idle playback parks until the planning window before the
 * cue instead of polling the whole track.
 */
internal fun CrossfadeController.koTickStepMs(): Long = when (phase) {
    CrossfadeController.Phase.IDLE -> koIdleStepMs()
    CrossfadeController.Phase.ARMING ->
        CrossfadeArmPolicy.armingStepMs(outgoing?.playWhenReady != false)
    CrossfadeController.Phase.FADING -> CrossfadeController.FADE_STEP_MS
    CrossfadeController.Phase.SLEEP_FADE -> CrossfadeController.FADE_STEP_MS
    CrossfadeController.Phase.BAILING -> CrossfadeController.BAIL_STEP_MS
    CrossfadeController.Phase.LOOP_ARMING ->
        CrossfadeArmPolicy.armingStepMs(outgoing?.playWhenReady != false)
    CrossfadeController.Phase.LOOP_RUNNING -> CrossfadeController.LOOP_STEP_MS
}

private fun CrossfadeController.koIdleStepMs(): Long {
    val player = active()
    val duration = player.duration
    return KoCrossfadeTickerPolicy.idleStepMs(
        playing = player.isPlaying,
        transitionEnabled = AppSettings.smartFadeEnabled.value || configuredFadeMs() > 0L,
        smartEnabled = AppSettings.smartFadeEnabled.value,
        hasNext = player.hasNextMediaItem(),
        // A tempo ease-back is still moving the audible rate; it needs the old
        // idle cadence until it settles, however far the next cue is.
        easing = koIsEasing(),
        durationMs = duration,
        positionMs = player.currentPosition,
        plannedCueMs = koPlannedCueMs(),
        plannedRampMs = koPlannedRampMs(),
        armLeadMs = AutomixWorkPolicy.STANDBY_LEAD_MS,
        fadeMs = fadeFor(duration),
        playbackSpeed = AppSettings.playbackSpeed.value,
        screenOn = screenOn(),
        onScreenStepMs = CrossfadeController.IDLE_STEP_MS,
        offScreenStepMs = CrossfadeController.SCREEN_OFF_IDLE_STEP_MS,
    )
}

/**
 * [KO] crossfade-ticker
 *
 * When the idle loop may next do useful work. Kept pure so the sleep length can
 * be tested without a player: 0 parks on events, a large value sleeps through
 * the quiet part of the track, and the near-cue cadence is the existing step.
 */
internal object KoCrossfadeTickerPolicy {
    fun idleStepMs(
        playing: Boolean,
        transitionEnabled: Boolean,
        smartEnabled: Boolean,
        hasNext: Boolean,
        easing: Boolean,
        durationMs: Long,
        positionMs: Long,
        plannedCueMs: Long?,
        plannedRampMs: Long,
        armLeadMs: Long,
        fadeMs: Long,
        playbackSpeed: Float,
        screenOn: Boolean,
        onScreenStepMs: Long,
        offScreenStepMs: Long,
    ): Long {
        if (!playing || !transitionEnabled || !hasNext) return 0L
        val nearStep = if (screenOn) onScreenStepMs else offScreenStepMs
        if (easing) return nearStep
        if (durationMs <= 0L) return 0L
        // The earliest legitimate plan start: an analyzed outro is never more
        // than the early-mix guard before the file end, and its waveforms decode
        // a prefetch window before that. A plain fade only needs its own length
        // and the arming lead. Sleep through everything earlier.
        val horizon = fadeMs.coerceAtLeast(0L) + armLeadMs.coerceAtLeast(0L) +
            if (smartEnabled) {
                AutomixWorkPolicy.EARLY_MIX_GUARD_MS + CrossfadeController.MIX_PREFETCH_MS
            } else {
                0L
            }
        val toUnplannedCue = durationMs - positionMs - horizon
        // Arming needs the lead and the tempo ramp the plan starts with; a
        // slow-tempo plan would otherwise wake after its ramp was due.
        val toPlannedCue = plannedCueMs?.let {
            it - positionMs - armLeadMs.coerceAtLeast(0L) - plannedRampMs.coerceAtLeast(0L)
        }
        val wait = minOf(toUnplannedCue, toPlannedCue ?: Long.MAX_VALUE)
        if (wait <= 0L) return nearStep
        // The player's position and duration are media time. At anything but
        // 1x the wall clock reaches that cue sooner or later than the media
        // distance says.
        val speed = playbackSpeed.toDouble().takeIf { it > 0.0 } ?: 1.0
        return (wait / speed).roundToLong().coerceAtLeast(1L)
    }
}
