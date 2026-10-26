package com.music.bitchord.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the Apple Music scale the screens now share. The numbers are the
 * contract: a 34sp display, 22sp section, 17sp body and 15/13sp secondary copy.
 */
class AppTypeTest {
    @Test
    fun typeScaleKeepsAppleMusicSteps() {
        assertEquals(34f, AppType.DisplayLarge.value, 0.001f)
        assertEquals(30f, AppType.Display.value, 0.001f)
        assertEquals(22f, AppType.Section.value, 0.001f)
        assertEquals(20f, AppType.Title.value, 0.001f)
        assertEquals(17f, AppType.Body.value, 0.001f)
        assertEquals(15f, AppType.Secondary.value, 0.001f)
        assertEquals(13f, AppType.Caption.value, 0.001f)
        assertEquals(11f, AppType.Micro.value, 0.001f)
    }

    @Test
    fun spacingKeepsTwentyDpGutterAndTouchRow() {
        assertEquals(20f, AppSpacing.ScreenHorizontal.value, 0.001f)
        assertEquals(24f, AppSpacing.Section.value, 0.001f)
        assertEquals(52f, AppSpacing.ListRowMinHeight.value, 0.001f)
    }
}
