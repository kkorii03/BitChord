package com.music.bitchord.download

import com.music.bitchord.data.sources.HqLibraryAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A prefetch is a new file nobody asked for, so on mobile it must wait for
 * Wi-Fi instead of being refused by the download queue and settled as done.
 */
class HqLibraryUpgradeWifiTest {

    @Test
    fun `prefetch waits for wifi while replacement may run on mobile`() {
        assertTrue(HqLibraryUpgrade.waitsForWifi(HqLibraryAction.PREFETCH, downloadsAllowedNow = false))
        assertFalse(HqLibraryUpgrade.waitsForWifi(HqLibraryAction.PREFETCH, downloadsAllowedNow = true))
        assertFalse(HqLibraryUpgrade.waitsForWifi(HqLibraryAction.REPLACE, downloadsAllowedNow = false))
        assertFalse(HqLibraryUpgrade.waitsForWifi(HqLibraryAction.REPLACE, downloadsAllowedNow = true))
    }
}
