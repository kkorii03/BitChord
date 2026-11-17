package com.music.bitchord.ko

import com.music.bitchord.ui.player.PlayerExpandSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review FX2 C2b-1: the player opens for TalkBack / Switch Access users after a resume. */
class PlayerExpandKoTest {

    private fun openWith(accessibilityActive: Boolean): List<Boolean> {
        val changes = mutableListOf<Boolean>()
        koSetPlayerShown(
            shown = true,
            source = PlayerExpandSource.TAP,
            currentlyShown = false,
            touchedSinceResume = false,
            msSinceResume = 1_000L,
            msSinceTouch = -1L,
            accessibilityActive = accessibilityActive,
            onShownChange = { changes += it },
        )
        return changes
    }

    @Test
    fun `an accessibility tap with no touch since resume opens the player`() {
        assertEquals(listOf(true), openWith(accessibilityActive = true))
    }

    @Test
    fun `without accessibility the untouched tap is still refused`() {
        assertEquals(emptyList<Boolean>(), openWith(accessibilityActive = false))
    }

    @Test
    fun `the setter reads accessibility at decision time`() {
        val changes = mutableListOf<Boolean>()
        var talkBack = false
        val set = koPlayerShownSetter(
            { false },
            { false },
            { 1_000L },
            { -1L },
            accessibilityActive = { talkBack },
        ) { changes += it }
        set(true, PlayerExpandSource.TAP)
        talkBack = true
        set(true, PlayerExpandSource.TAP)
        assertEquals(listOf(true), changes)
    }
}
