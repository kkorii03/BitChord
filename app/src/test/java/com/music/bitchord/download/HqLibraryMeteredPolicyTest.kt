package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic high-quality pass is background work, not a download the
 * listener just asked for, so it must obey the Wi-Fi-only setting. Only the
 * pass started from the Downloads button may spend mobile data.
 */
class HqLibraryMeteredPolicyTest {

    @Test
    fun `the background pass does not override the wi-fi-only rule`() {
        assertFalse(hqPassAllowsMeteredData(manualPass = false))
    }

    @Test
    fun `a pass the listener started may use the connection in hand`() {
        assertTrue(hqPassAllowsMeteredData(manualPass = true))
    }
}
