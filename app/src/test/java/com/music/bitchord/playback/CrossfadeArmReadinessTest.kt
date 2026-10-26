package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeArmReadinessTest {

    @Test
    fun `a ready player with the whole fade buffered may start`() {
        assertTrue(CrossfadeArmPolicy.canStartFade(true, bufferedAheadMs = 8_000L, fadeMs = 8_000L))
        assertTrue(CrossfadeArmPolicy.canStartFade(true, bufferedAheadMs = 20_000L, fadeMs = 8_000L))
    }

    @Test
    fun `a ready player that has not buffered the fade is not safe to start`() {
        assertFalse(CrossfadeArmPolicy.canStartFade(true, bufferedAheadMs = 2_500L, fadeMs = 8_000L))
        assertFalse(CrossfadeArmPolicy.canStartFade(true, bufferedAheadMs = 7_999L, fadeMs = 8_000L))
    }

    @Test
    fun `an unready player never starts a fade`() {
        assertFalse(CrossfadeArmPolicy.canStartFade(false, bufferedAheadMs = 30_000L, fadeMs = 8_000L))
        assertFalse(CrossfadeArmPolicy.canStartFade(false, bufferedAheadMs = 0L, fadeMs = 0L))
    }

    @Test
    fun `a missing or negative buffer is treated as empty`() {
        assertFalse(CrossfadeArmPolicy.canStartFade(true, bufferedAheadMs = -1L, fadeMs = 1L))
    }

    @Test
    fun `no fade length only asks for readiness`() {
        assertTrue(CrossfadeArmPolicy.canStartFade(true, bufferedAheadMs = 0L, fadeMs = 0L))
        assertTrue(CrossfadeArmPolicy.canStartFade(true, bufferedAheadMs = 0L, fadeMs = -1L))
    }
}
