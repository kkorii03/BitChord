package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    @Test
    fun customSliderSpansOneStepToTwelveHours() {
        assertEquals(SleepTimer.STEP_MINUTES, SleepTimer.minutesAt(0f))
        assertEquals(SleepTimer.MAX_MINUTES, SleepTimer.minutesAt(1f))
        // Dragged past either end, it stays inside the range.
        assertEquals(SleepTimer.STEP_MINUTES, SleepTimer.minutesAt(-0.5f))
        assertEquals(SleepTimer.MAX_MINUTES, SleepTimer.minutesAt(1.5f))
    }

    @Test
    fun customSliderLandsOnWholeSteps() {
        for (i in 0..100) {
            assertEquals(0, SleepTimer.minutesAt(i / 100f) % SleepTimer.STEP_MINUTES)
        }
    }

    @Test
    fun fractionOfIsTheInverseOfMinutesAt() {
        for (minutes in SleepTimer.STEP_MINUTES..SleepTimer.MAX_MINUTES step SleepTimer.STEP_MINUTES) {
            assertEquals(minutes, SleepTimer.minutesAt(SleepTimer.fractionOf(minutes)))
        }
    }

    /**
     * The sheet's countdown only ticks with a timer armed *and* the app on
     * screen. Off screen it used to go on waking once a second for a row
     * nobody could see, because a composition outlives the activity being
     * stopped.
     */
    @Test
    fun countdownTicksOnlyWhileArmedAndOnScreen() {
        assertFalse(SleepTimer.countdownTicking(deadline = null, foreground = false))
        assertFalse(SleepTimer.countdownTicking(deadline = null, foreground = true))
        assertFalse(SleepTimer.countdownTicking(deadline = 1_000L, foreground = false))
        assertTrue(SleepTimer.countdownTicking(deadline = 1_000L, foreground = true))
    }

    @Test
    fun calculateFadeVolumeKeepsFullVolumeWhenDisabledOrOutsideWindow() {
        assertEquals(1.0f, SleepTimer.calculateFadeVolume(45_000L, fadeOutEnabled = true), 0.001f)
        assertEquals(1.0f, SleepTimer.calculateFadeVolume(30_000L, fadeOutEnabled = true), 0.001f)
        assertEquals(1.0f, SleepTimer.calculateFadeVolume(15_000L, fadeOutEnabled = false), 0.001f)
    }

    @Test
    fun calculateFadeVolumeFadesLinearlyDuringLastThirtySeconds() {
        assertEquals(0.5f, SleepTimer.calculateFadeVolume(15_000L, fadeOutEnabled = true), 0.001f)
        assertEquals(0.25f, SleepTimer.calculateFadeVolume(7_500L, fadeOutEnabled = true), 0.001f)
        assertEquals(0.0f, SleepTimer.calculateFadeVolume(0L, fadeOutEnabled = true), 0.001f)
        assertEquals(0.0f, SleepTimer.calculateFadeVolume(-100L, fadeOutEnabled = true), 0.001f)
    }
}
