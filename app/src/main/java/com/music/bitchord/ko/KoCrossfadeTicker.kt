package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
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
        // Opening the mix popup is an event too: a paused ticker would
        // otherwise never plan the preview it is waiting for, and the popup
        // gave up after 2.5 s with "예정된 자동 믹스 전환이 없습니다".
        // Collected, not polled; cancelled with the ticker.
        launch { AppSettings.mixViewOpen.collect { open -> if (open) wakeTicker() } }
        while (isActive) {
            tick()
            val step = koTickStepMs()
            if (step > 0L) {
                // The normal cadence, cut short by anything that made the next
                // step due sooner.
                withTimeoutOrNull(step) { ko.tickerWake.receive() }
            } else {
                // Nothing to advance: idle, and the player is not making sound.
                // Only the session player's callbacks can wake this, so make
                // sure they are the ones being listened to.
                koFollowSession("ticker parked")
                ko.tickerWake.receive()
            }
        }
    }

/**
 * [KO] crossfade-follow
 *
 * Re-attaches the controller to the session player. A version swap moved the
 * session onto the standby without telling the controller, which kept
 * listening to the retired player: the next pause parked the ticker for good,
 * and from then on nothing planned, armed or blended, the Automix line froze
 * and the mix popup stayed empty (vc192, 20:27 hq-swap onward).
 */
internal fun CrossfadeController.koFollowSession(why: String) {
    if (!followSession()) return
    TrackLog.d("BitChord", "crossfade: listener moved to the session player ($why)")
    wakeTicker()
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
    CrossfadeController.Phase.LOOP_ARMING ->
        CrossfadeArmPolicy.armingStepMs(outgoing?.playWhenReady != false)
    CrossfadeController.Phase.LOOP_RUNNING -> CrossfadeController.LOOP_STEP_MS
}
