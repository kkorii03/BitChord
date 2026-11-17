package com.music.bitchord.ko

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KO] P1-1: headphones must not undo a sleep-timer or focus-loss pause. */
class KoHeadphoneResumePolicyTest {

    private val window = 30L * 60L * 1000L
    private val now = 100L * 60L * 1000L
    private val tenMinutesAgo = now - 10L * 60L * 1000L

    @Test
    fun `a fresh pause the listener made may resume`() {
        assertTrue(KoHeadphoneResumePolicy.mayResume(tenMinutesAgo, now, vetoed = false, windowMs = window))
    }

    @Test
    fun `a fresh pause the sleep timer made may not resume`() {
        assertFalse(KoHeadphoneResumePolicy.mayResume(tenMinutesAgo, now, vetoed = true, windowMs = window))
    }

    @Test
    fun `an old pause or one that never happened may not resume`() {
        assertFalse(KoHeadphoneResumePolicy.mayResume(now - window - 1, now, vetoed = false, windowMs = window))
        assertFalse(KoHeadphoneResumePolicy.mayResume(0L, now, vetoed = false, windowMs = window))
        assertTrue(KoHeadphoneResumePolicy.mayResume(now - window, now, vetoed = false, windowMs = window))
    }

    @Test
    fun `only a permanent focus loss pause vetoes`() {
        assertTrue(
            KoHeadphoneResumePolicy.vetoesPause(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
        )
        assertFalse(
            KoHeadphoneResumePolicy.vetoesPause(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST),
        )
        assertFalse(
            KoHeadphoneResumePolicy.vetoesPause(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY),
        )
        assertFalse(
            KoHeadphoneResumePolicy.vetoesPause(true, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
        )
    }
}
