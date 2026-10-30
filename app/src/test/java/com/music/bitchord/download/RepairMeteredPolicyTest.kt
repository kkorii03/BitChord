package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download health and the MV-sound repair run on their own after playback, not
 * because the listener just asked for a download. They must therefore obey the
 * Wi-Fi-only download setting; only a row or banner the listener tapped may
 * spend mobile data.
 */
class RepairMeteredPolicyTest {

    @Test
    fun `an automatic quality repair waits for an allowed connection`() {
        assertFalse(hqUpgradeAllowedNow(automatic = true, downloadsAllowedNow = false))
        assertTrue(hqUpgradeAllowedNow(automatic = true, downloadsAllowedNow = true))
    }

    @Test
    fun `a listener started quality repair may use the connection in hand`() {
        assertTrue(hqUpgradeAllowedNow(automatic = false, downloadsAllowedNow = false))
        assertTrue(hqUpgradeAllowsMetered(automatic = false))
        assertFalse(hqUpgradeAllowsMetered(automatic = true))
    }

    @Test
    fun `an automatic mv-sound repair waits for an allowed connection`() {
        assertFalse(mvRepairAllowsMetered(manual = false, downloadsAllowedNow = false))
        assertTrue(mvRepairAllowsMetered(manual = false, downloadsAllowedNow = true))
    }

    @Test
    fun `a banner started mv-sound repair may use the connection in hand`() {
        assertTrue(mvRepairAllowsMetered(manual = true, downloadsAllowedNow = false))
    }
}
