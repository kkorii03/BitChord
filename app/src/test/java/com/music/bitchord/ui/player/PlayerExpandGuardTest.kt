package com.music.bitchord.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerExpandGuardTest {

    @Test
    fun `a tap with a touch since resume opens the player`() {
        assertTrue(PlayerExpandGuard.allow(PlayerExpandSource.TAP, touchedSinceResume = true))
    }

    @Test
    fun `a tap with no touch since resume is refused`() {
        assertFalse(PlayerExpandGuard.allow(PlayerExpandSource.TAP, touchedSinceResume = false))
    }

    @Test
    fun `a pull with a touch since resume opens the player`() {
        assertTrue(PlayerExpandGuard.allow(PlayerExpandSource.PULL, touchedSinceResume = true))
    }

    @Test
    fun `a pull with no touch since resume is refused`() {
        assertFalse(PlayerExpandGuard.allow(PlayerExpandSource.PULL, touchedSinceResume = false))
    }

    @Test
    fun `the sheet moving the player itself needs a touch too`() {
        assertFalse(PlayerExpandGuard.allow(PlayerExpandSource.MOTION, touchedSinceResume = false))
        assertTrue(PlayerExpandGuard.allow(PlayerExpandSource.MOTION, touchedSinceResume = true))
    }

    @Test
    fun `an accessibility click on the mini player opens it without a touch`() {
        // Review FX2 C2b-1: TalkBack and Switch Access never send ACTION_DOWN.
        assertTrue(
            PlayerExpandGuard.allow(
                PlayerExpandSource.TAP,
                touchedSinceResume = false,
                accessibilityActive = true,
            ),
        )
        assertFalse(
            PlayerExpandGuard.allow(
                PlayerExpandSource.TAP,
                touchedSinceResume = false,
                accessibilityActive = false,
            ),
        )
    }

    @Test
    fun `accessibility on does not let the sheet raise itself`() {
        assertFalse(PlayerExpandGuard.allow(PlayerExpandSource.MOTION, touchedSinceResume = false, accessibilityActive = true))
        assertFalse(PlayerExpandGuard.allow(PlayerExpandSource.PULL, touchedSinceResume = false, accessibilityActive = true))
    }

    @Test
    fun `a widget deep link is allowed with or without a touch`() {
        assertTrue(PlayerExpandGuard.allow(PlayerExpandSource.DEEPLINK, touchedSinceResume = true))
        assertTrue(PlayerExpandGuard.allow(PlayerExpandSource.DEEPLINK, touchedSinceResume = false))
    }
}
