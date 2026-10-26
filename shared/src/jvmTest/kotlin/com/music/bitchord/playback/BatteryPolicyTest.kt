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

    @Test
    fun `an entry with no origin is still purgeable when it is analysis only`() {
        assertTrue(BatteryPolicy.cacheEntryNeedsPurge(null, "OTHER", true, false, false))
        assertTrue(BatteryPolicy.cacheEntryNeedsPurge("", "OTHER", true, false, false))
    }

    @Test
    fun `an entry with no origin is kept once playback has read it`() {
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge(null, "OTHER", true, true, false))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge(null, "OTHER", true, false, true))
    }

    @Test
    fun `foreign origin wins even when both playback flags are set`() {
        assertTrue(BatteryPolicy.cacheEntryNeedsPurge("OTHER", "OTHER", true, true, true))
        assertTrue(BatteryPolicy.cacheEntryNeedsPurge("OTHER", "OTHER", false, true, true))
    }

    @Test
    fun `an origin that merely looks foreign is not the foreign origin`() {
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("OTHER ", "OTHER", false, false, false))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("other", "OTHER", false, false, false))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("", "OTHER", false, false, false))
    }

    @Test
    fun `ordinary entries are kept in every non analysis-only combination`() {
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("YOUTUBE", "OTHER", false, false, true))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("YOUTUBE", "OTHER", false, true, false))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("YOUTUBE", "OTHER", false, true, true))
        assertFalse(BatteryPolicy.cacheEntryNeedsPurge("YOUTUBE", "OTHER", true, true, true))
    }
}
