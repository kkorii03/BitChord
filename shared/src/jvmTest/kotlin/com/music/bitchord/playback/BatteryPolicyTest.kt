package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryPolicyTest {

    @Test
    fun `party ticker runs only inside a playing party`() {
        assertTrue(BatteryPolicy.partyTickerActive(inParty = true, partyPlaying = true))
        assertFalse(BatteryPolicy.partyTickerActive(inParty = true, partyPlaying = false))
        assertFalse(BatteryPolicy.partyTickerActive(inParty = false, partyPlaying = true))
        assertFalse(BatteryPolicy.partyTickerActive(inParty = false, partyPlaying = false))
    }

    @Test
    fun `health monitor runs only on screen in a party without a live socket`() {
        assertTrue(BatteryPolicy.healthMonitorActive(true, true, false))
        assertFalse(BatteryPolicy.healthMonitorActive(false, true, false))
        assertFalse(BatteryPolicy.healthMonitorActive(true, false, false))
        assertFalse(BatteryPolicy.healthMonitorActive(true, true, true))
    }

    @Test
    fun `foreign origin is always a purge candidate`() {
        assertTrue(BatteryPolicy.cacheEntryNeedsPurge("OTHER", "OTHER", false, false, false))
        assertTrue(BatteryPolicy.cacheEntryNeedsPurge("OTHER", "OTHER", true, true, true))
    }

    @Test
    fun `analysis only entries are candidates until playback or a play marks them`() {
        assertTrue(BatteryPolicy.cacheEntryNeedsPurge("YOUTUBE", "OTHER", true, false, false))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("YOUTUBE", "OTHER", true, true, false))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("YOUTUBE", "OTHER", true, false, true))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("YOUTUBE", "OTHER", false, false, false))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge(null, "OTHER", false, false, false))
    }
}
