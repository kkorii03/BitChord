package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic high-quality pass fetches, verifies and decodes whole tracks,
 * so it must wait out the phone's power saving. A pass the listener started
 * with the button is a one-off instruction and keeps going.
 */
class HqLibraryLowPowerPolicyTest {

    @Test
    fun `the automatic pass waits out power saving`() {
        assertFalse(hqPassAllowsLowPower(manualPass = false, lowPower = true))
    }

    @Test
    fun `the automatic pass runs out of power saving`() {
        assertTrue(hqPassAllowsLowPower(manualPass = false, lowPower = false))
    }

    @Test
    fun `a pass the listener started ignores power saving`() {
        assertTrue(hqPassAllowsLowPower(manualPass = true, lowPower = true))
        assertTrue(hqPassAllowsLowPower(manualPass = true, lowPower = false))
    }
}
