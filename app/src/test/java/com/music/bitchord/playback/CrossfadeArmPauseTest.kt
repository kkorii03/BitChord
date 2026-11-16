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

    // P2-1: a blend or sleep fade paused midway kept the loop at 30 ms until resume.
    @Test
    fun `a paused blend or sleep fade waits for the resume event`() {
        assertEquals(0L, com.music.bitchord.ko.koFadeStepMs(outgoing = false, incoming = false))
        assertEquals(0L, com.music.bitchord.ko.koFadeStepMs(outgoing = false, incoming = null))
    }

    @Test
    fun `a blend keeps stepping while either deck plays or one is missing`() {
        val step = CrossfadeController.FADE_STEP_MS
        assertEquals(step, com.music.bitchord.ko.koFadeStepMs(outgoing = true, incoming = false))
        assertEquals(step, com.music.bitchord.ko.koFadeStepMs(outgoing = false, incoming = true))
        assertEquals(step, com.music.bitchord.ko.koFadeStepMs(outgoing = null, incoming = false))
        assertEquals(step, com.music.bitchord.ko.koFadeStepMs(outgoing = true, incoming = null))
    }

    @Test
    fun `time spent paused does not consume the readiness grace`() {
        assertEquals(6_000L, CrossfadeArmPolicy.armDeadlineAfterPause(deadlineMs = 1_000L, pausedForMs = 5_000L))
        assertEquals(1_000L, CrossfadeArmPolicy.armDeadlineAfterPause(deadlineMs = 1_000L, pausedForMs = 0L))
        assertEquals(1_000L, CrossfadeArmPolicy.armDeadlineAfterPause(deadlineMs = 1_000L, pausedForMs = -250L))
    }
}
