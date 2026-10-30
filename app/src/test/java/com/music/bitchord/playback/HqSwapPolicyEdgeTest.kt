package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edges around the mid-song lossless swap that the first test file does not
 * exercise: a player that has not reported a duration yet, the exact
 * twenty-second floor, and a stored verdict whose rate cannot be used to move
 * the playhead.
 */
class HqSwapPolicyEdgeTest {

    @Test
    fun `a player without a duration is not treated as almost over`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 0L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
        )

        assertTrue(decision.allowed)
        assertEquals(40_000L, decision.targetPositionMs)
    }

    @Test
    fun `exactly twenty seconds left is still worth the swap`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 180_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun `one millisecond under the floor is refused`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 180_001L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("too-late", decision.reason)
    }

    @Test
    fun `a target exactly at the end is not a position inside the file`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 160_000L,
            rate = 1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("position-out-of-range", decision.reason)
    }

    @Test
    fun `a stored verdict with a zero rate cannot move the playhead`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 1_200L,
            rate = 0.0,
        )

        assertFalse("a zero rate would seek every position to the offset", decision.allowed)
        assertEquals("bad-rate", decision.reason)
    }

    @Test
    fun `a stored verdict with a negative rate is refused`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = -1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("bad-rate", decision.reason)
    }

    @Test
    fun `a stored verdict with a non-finite rate is refused`() {
        val nan = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = Double.NaN,
        )
        val infinite = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = Double.POSITIVE_INFINITY,
        )

        assertFalse(nan.allowed)
        assertEquals("bad-rate", nan.reason)
        assertFalse(infinite.allowed)
        assertEquals("bad-rate", infinite.reason)
    }

    @Test
    fun `a youtube clock whose current rate is unusable falls back to the raw position`() {
        assertEquals(42_000L, HqSwapPolicy.toYoutubeMs(42_000L, 2_000L, 0.0))
        assertEquals(42_000L, HqSwapPolicy.toYoutubeMs(42_000L, 2_000L, Double.NaN))
    }
}
