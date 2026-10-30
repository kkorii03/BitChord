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
    fun `a corrupt current rate refuses the swap instead of using the raw position`() {
        val zero = HqSwapPolicy.decide(
            positionMs = 42_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 2_000L,
            rate = 1.0,
            currentRate = 0.0,
        )
        val nan = HqSwapPolicy.decide(
            positionMs = 42_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 2_000L,
            rate = 1.0,
            currentRate = Double.NaN,
        )

        assertFalse(zero.allowed)
        assertEquals("bad-current-rate", zero.reason)
        assertFalse(nan.allowed)
        assertEquals("bad-current-rate", nan.reason)
    }

    @Test
    fun `a video radio row is never swapped to a catalogue master`() {
        val radio = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
            videoRadio = true,
        )
        val video = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
            isVideo = true,
        )

        assertFalse(radio.allowed)
        assertEquals("video-radio", radio.reason)
        assertFalse(video.allowed)
        assertEquals("video-radio", video.reason)
    }
}
