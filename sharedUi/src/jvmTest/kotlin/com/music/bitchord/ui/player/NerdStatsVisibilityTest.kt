package com.music.bitchord.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NerdStatsVisibilityTest {

    @Test
    fun `a tapped sleeve reveals the stats for a moment`() {
        assertTrue(nerdStatsVisible(revealed = true, alwaysShow = false))
    }

    @Test
    fun `the settings switch keeps the stats up without a tap`() {
        assertTrue(nerdStatsVisible(revealed = false, alwaysShow = true))
    }

    @Test
    fun `hidden stats need either a tap or the settings switch`() {
        assertFalse(nerdStatsVisible(revealed = false, alwaysShow = false))
    }
}
