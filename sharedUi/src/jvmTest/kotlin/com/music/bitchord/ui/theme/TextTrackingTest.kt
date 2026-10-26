package com.music.bitchord.ui.theme

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTrackingTest {
    @Test
    fun `dates and counts keep their natural spacing`() {
        assertEquals(0f, trackingFor("10/26", 1.6.sp).value, 0f)
        assertEquals(0f, trackingFor("2024", 1.sp).value, 0f)
        assertEquals(0f, trackingFor("12곡", 1.sp).value, 0f)
        assertTrue(isNumericText("10/26"))
        assertTrue(isNumericText("2024"))
    }

    @Test
    fun `latin labels keep the tracking their caller asks for`() {
        assertEquals(1.6f, trackingFor("MEMBER SINCE", 1.6.sp).value, 0f)
        assertEquals(1.6f, trackingFor("Track 1", 1.6.sp).value, 0f)
        assertFalse(isNumericText("Track 1"))
    }

    @Test
    fun `hangul still drops tracking`() {
        assertEquals(0f, trackingFor("가입일", 0.8.sp).value, 0f)
        assertTrue(containsHangul("가입일"))
    }
}
