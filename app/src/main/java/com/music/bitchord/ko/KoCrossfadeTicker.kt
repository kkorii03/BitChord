package com.music.bitchord.ko

import com.music.bitchord.playback.CrossfadeArmPolicy
import com.music.bitchord.playback.CrossfadeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
                // Nothing to advance: idle, and the player is not making sound.
                ko.tickerWake.receive()
            }
        }
    }

/**
 * How long to wait before the next tick, or 0 to wait for an event.
 *
 * Only the idle-and-silent case is parked: an armed or running transition still
 * has to step at its own rate whatever the player is doing.
 */
internal fun CrossfadeController.koTickStepMs(): Long = when (phase) {
    CrossfadeController.Phase.IDLE -> if (active().isPlaying) {
        if (screenOn()) CrossfadeController.IDLE_STEP_MS else CrossfadeController.SCREEN_OFF_IDLE_STEP_MS
    } else {
        0L
    }
    CrossfadeController.Phase.ARMING ->
        CrossfadeArmPolicy.armingStepMs(outgoing?.playWhenReady != false)
    CrossfadeController.Phase.FADING -> CrossfadeController.FADE_STEP_MS
    CrossfadeController.Phase.SLEEP_FADE -> CrossfadeController.FADE_STEP_MS
    CrossfadeController.Phase.BAILING -> CrossfadeController.BAIL_STEP_MS
}
