package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerModeControlTest {

    @Test
    fun `repeat off all and one are each drawn and announced distinctly`() {
        val off = repeatModeControl(RepeatModes.OFF)
        val all = repeatModeControl(RepeatModes.ALL)
        val one = repeatModeControl(RepeatModes.ONE)

        assertFalse(off.active)
        assertEquals(PlayerModeHighlight.None, off.highlight)
        assertEquals(PlayerModeGlyph.Repeat, off.glyph)
        assertNull(off.badge)
        assertEquals(PlayerModeDescription.RepeatOff, off.description)

        assertTrue(all.active)
        assertEquals(PlayerModeHighlight.Strong, all.highlight)
        assertEquals(PlayerModeGlyph.Repeat, all.glyph)
        assertNull(all.badge)
        assertEquals(PlayerModeDescription.RepeatAll, all.description)

        assertTrue(one.active)
        assertEquals(PlayerModeHighlight.Strong, one.highlight)
        assertEquals(PlayerModeGlyph.Repeat, one.glyph)
        assertEquals("1", one.badge)
        assertEquals(PlayerModeDescription.RepeatOne, one.description)

        // The regression: all-repeat and repeat off used to render the same
        // repeat glyph with the same highlight, so the screen looked unchanged.
        assertNotEquals(all.highlight, off.highlight)
        assertNotEquals(all.description, off.description)
    }

    @Test
    fun `shuffle on is active and announced as on`() {
        val on = shuffleModeControl(enabled = true)
        val off = shuffleModeControl(enabled = false)

        assertTrue(on.active)
        assertEquals(PlayerModeHighlight.Strong, on.highlight)
        assertEquals(PlayerModeGlyph.Shuffle, on.glyph)
        assertEquals(PlayerModeDescription.ShuffleOn, on.description)

        assertFalse(off.active)
        assertEquals(PlayerModeHighlight.None, off.highlight)
        assertEquals(PlayerModeGlyph.Shuffle, off.glyph)
        assertEquals(PlayerModeDescription.ShuffleOff, off.description)
    }

    @Test
    fun `autoplay on is active and announced as on`() {
        val on = autoplayModeControl(enabled = true)
        val off = autoplayModeControl(enabled = false)

        assertTrue(on.active)
        assertEquals(PlayerModeHighlight.Strong, on.highlight)
        assertEquals(PlayerModeGlyph.Autoplay, on.glyph)
        assertEquals(PlayerModeDescription.AutoplayOn, on.description)

        assertFalse(off.active)
        assertEquals(PlayerModeHighlight.None, off.highlight)
        assertEquals(PlayerModeGlyph.Autoplay, off.glyph)
        assertEquals(PlayerModeDescription.AutoplayOff, off.description)
    }
}
