package com.music.bitchord

import com.music.bitchord.ui.LyricsRequestGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every track, local file included, waits for the player's length before the
 * lookup key is claimed. Claiming without it dropped the re-trigger that
 * carried the length, and a downloaded file got no request at all
 * (a59AuyMjb68).
 */
class LyricsRequestGateTest {

    @Test
    fun everyTrackWaitsForAKnownLength() {
        assertFalse(LyricsRequestGate.canClaim(0L))
        assertFalse(LyricsRequestGate.canClaim(-1L))
        assertTrue(LyricsRequestGate.canClaim(1L))
        assertTrue(LyricsRequestGate.canClaim(215_000L))
    }
}
