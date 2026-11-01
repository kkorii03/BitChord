package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pausing just before a track ends used to throw the armed standby away, so the
 * crossfade disappeared on resume. The arm is kept now; these are the two
 * timing rules that make keeping it safe: the loop stops polling while paused,
 * and the pause does not spend the incoming stream's readiness grace.
 */
class CrossfadeArmPauseTest {

    @Test
    fun `a paused arm waits for the resume event instead of polling`() {
        assertEquals(0L, CrossfadeArmPolicy.armingStepMs(playWhenReady = false))
        assertEquals(CrossfadeArmPolicy.ARM_STEP_MS, CrossfadeArmPolicy.armingStepMs(playWhenReady = true))
    }

    @Test
    fun `time spent paused does not consume the readiness grace`() {
        assertEquals(6_000L, CrossfadeArmPolicy.armDeadlineAfterPause(deadlineMs = 1_000L, pausedForMs = 5_000L))
        assertEquals(1_000L, CrossfadeArmPolicy.armDeadlineAfterPause(deadlineMs = 1_000L, pausedForMs = 0L))
        assertEquals(1_000L, CrossfadeArmPolicy.armDeadlineAfterPause(deadlineMs = 1_000L, pausedForMs = -250L))
    }
}
