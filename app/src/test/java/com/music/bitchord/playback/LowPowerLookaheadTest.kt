package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resolving stream URLs past the immediate next track is work low power exists
 * to defer, and on a metered connection it is a request the listener pays for.
 * The next track's own URL is still minted so a transition does not wait.
 */
class LowPowerLookaheadTest {

    @Test
    fun `low power looks only as far as the immediate next track`() {
        assertEquals(1, BatteryPolicy.cacheUrlLookaheadCount(metered = false, lowPower = true, queueLookahead = 1))
    }

    @Test
    fun `metered data looks only as far as the immediate next track`() {
        assertEquals(1, BatteryPolicy.cacheUrlLookaheadCount(metered = true, lowPower = false, queueLookahead = 1))
    }

    @Test
    fun `ordinary power keeps the configured lookahead plus the next track`() {
        assertEquals(2, BatteryPolicy.cacheUrlLookaheadCount(metered = false, lowPower = false, queueLookahead = 1))
        assertEquals(3, BatteryPolicy.cacheUrlLookaheadCount(metered = false, lowPower = false, queueLookahead = 2))
    }

    @Test
    fun `low power wins even when the connection is metered`() {
        assertEquals(1, BatteryPolicy.cacheUrlLookaheadCount(metered = true, lowPower = true, queueLookahead = 2))
    }
}
