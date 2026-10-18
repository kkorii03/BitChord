package com.music.bitchord.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowPowerModeTest {
    @Test
    fun `auto follows the system saver at any battery level`() {
        assertTrue(lowPowerActive(LowPowerMode.AUTO, powerSave = true, batteryPercent = 80, charging = true))
    }

    @Test
    fun `auto turns on at twenty percent while unplugged`() {
        assertTrue(lowPowerActive(LowPowerMode.AUTO, powerSave = false, batteryPercent = 20, charging = false))
    }

    @Test
    fun `auto stays off above twenty percent while unplugged`() {
        assertFalse(lowPowerActive(LowPowerMode.AUTO, powerSave = false, batteryPercent = 21, charging = false))
    }

    @Test
    fun `auto does not count a low battery while charging`() {
        assertFalse(lowPowerActive(LowPowerMode.AUTO, powerSave = false, batteryPercent = 5, charging = true))
    }

    @Test
    fun `on ignores the battery and charger`() {
        assertTrue(lowPowerActive(LowPowerMode.ON, powerSave = false, batteryPercent = 100, charging = true))
    }

    @Test
    fun `off ignores the system saver`() {
        assertFalse(lowPowerActive(LowPowerMode.OFF, powerSave = true, batteryPercent = 1, charging = false))
    }
}
