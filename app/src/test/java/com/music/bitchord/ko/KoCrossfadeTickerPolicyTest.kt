package com.music.bitchord.ko

import com.music.bitchord.playback.CrossfadeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoCrossfadeTickerPolicyTest {

    private val armLeadMs = 15_000L
    private val fadeMs = 6_000L

    private fun idleStep(
        playing: Boolean = true,
        transitionEnabled: Boolean = true,
        smartEnabled: Boolean = true,
        hasNext: Boolean = true,
        easing: Boolean = false,
        durationMs: Long = 240_000L,
        positionMs: Long = 0L,
        plannedCueMs: Long? = null,
        screenOn: Boolean = true,
    ) = KoCrossfadeTickerPolicy.idleStepMs(
        playing = playing,
        transitionEnabled = transitionEnabled,
        smartEnabled = smartEnabled,
        hasNext = hasNext,
        easing = easing,
        durationMs = durationMs,
        positionMs = positionMs,
        plannedCueMs = plannedCueMs,
        armLeadMs = armLeadMs,
        fadeMs = fadeMs,
        screenOn = screenOn,
        onScreenStepMs = CrossfadeController.IDLE_STEP_MS,
        offScreenStepMs = CrossfadeController.SCREEN_OFF_IDLE_STEP_MS,
    )

    @Test
    fun `crossfade off parks on events instead of polling`() {
        assertEquals(0L, idleStep(transitionEnabled = false))
    }

    @Test
    fun `paused playback parks`() {
        assertEquals(0L, idleStep(playing = false))
    }

    @Test
    fun `no next track parks`() {
        assertEquals(0L, idleStep(hasNext = false))
    }

    @Test
    fun `unknown duration parks instead of guessing a cue`() {
        assertEquals(0L, idleStep(durationMs = -1L))
    }

    @Test
    fun `a tempo ease-back keeps the old cadence far from the cue`() {
        assertEquals(CrossfadeController.IDLE_STEP_MS, idleStep(easing = true))
    }

    @Test
    fun `the quiet part of a playing track sleeps instead of polling`() {
        val step = idleStep(positionMs = 0L)
        assertTrue("expected a long sleep, was $step", step > 60_000L)
    }

    @Test
    fun `within the planning window the idle tick keeps its old cadence`() {
        assertEquals(
            CrossfadeController.IDLE_STEP_MS,
            idleStep(positionMs = 240_000L - 80_000L),
        )
    }

    @Test
    fun `screen off uses the slower cadence near the cue`() {
        assertEquals(
            CrossfadeController.SCREEN_OFF_IDLE_STEP_MS,
            idleStep(positionMs = 240_000L - 80_000L, screenOn = false),
        )
    }

    @Test
    fun `an earlier planned cue wakes before the end-of-track window`() {
        val unplanned = idleStep(positionMs = 0L)
        val planned = idleStep(positionMs = 0L, plannedCueMs = 120_000L)
        assertTrue("planned=$planned unplanned=$unplanned", planned < unplanned)
        assertEquals(120_000L - armLeadMs, planned)
    }
}
