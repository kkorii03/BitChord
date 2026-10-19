package com.music.bitchord.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetOpenIntentTest {

    @Test
    fun `the switch is off by default, so a widget tap only opens the app`() {
        assertFalse(WidgetOpenIntent.shouldExpand(setting = false))
    }

    @Test
    fun `the switch on raises the player`() {
        assertTrue(WidgetOpenIntent.shouldExpand(setting = true))
    }
}
