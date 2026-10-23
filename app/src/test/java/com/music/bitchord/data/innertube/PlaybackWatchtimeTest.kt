package com.music.bitchord.data.innertube

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Watchtime is what was heard, not where the playhead ended up: a seek must
 * not turn a short listen into a long one in the account's history.
 */
class PlaybackWatchtimeTest {

    private fun advance(watched: Long, from: Long, to: Long, maxStep: Long = 8L) =
        advanceWatchedSeconds(watched, from, to, maxStep)

    @Test
    fun testFirstSampleOnlyAnchorsTheClock() {
        assertEquals(0L, advance(watched = 0L, from = -1L, to = 30L))
    }

    @Test
    fun testOrdinaryTicksAccumulate() {
        var watched = 0L
        var position = 0L
        watched = advance(watched, position, 5L); position = 5L
        watched = advance(watched, position, 10L); position = 10L
        watched = advance(watched, position, 15L); position = 15L
        assertEquals(15L, watched)
    }

    @Test
    fun testForwardSeekIsNotWatchtime() {
        // A tap on the bar from 0:10 to 2:00 is one jump, not nearly two
        // minutes of listening.
        assertEquals(10L + 8L, advance(watched = 10L, from = 10L, to = 120L))
    }

    @Test
    fun testBackwardSeekAddsNothing() {
        assertEquals(40L, advance(watched = 40L, from = 60L, to = 5L))
    }

    @Test
    fun testReplayingASectionCountsAgain() {
        assertEquals(30L, advance(watched = 25L, from = 10L, to = 15L))
    }

    @Test
    fun testOneLongJumpCountsOnlyAsOneStep() {
        // A tick that arrived forty seconds late, or a seek with a late
        // sample behind it, cannot be more listening than the sampler could
        // have seen.
        assertEquals(28L, advance(watched = 20L, from = 20L, to = 60L))
    }
}
