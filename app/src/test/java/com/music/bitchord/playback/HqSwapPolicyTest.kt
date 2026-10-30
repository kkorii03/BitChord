package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a verified lossless copy may replace the one already playing.
 *
 * The reported failure: a FLAC passed verification with match=100% and the
 * current song still played Opus to its end. The swap is only worth its short
 * interruption when enough of the track is left, no crossfade owns the spare
 * player, and the verified offset lands inside the replacement.
 */
class HqSwapPolicyTest {

    @Test
    fun `enough of the track left and no transition allows the swap`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 1_200L,
            rate = 1.0,
        )

        assertTrue(decision.allowed)
        assertEquals(41_200L, decision.targetPositionMs)
    }

    @Test
    fun `less than twenty seconds left keeps the lossless copy for the next play`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 181_000L,
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
    fun `a crossfade in flight is never cut into`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = true,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("crossfade", decision.reason)
    }

    @Test
    fun `the verified offset moves the replacement onto the same audio`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 30_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = -2_500L,
            rate = 1.0,
        )

        assertTrue(decision.allowed)
        assertEquals(27_500L, decision.targetPositionMs)
    }

    @Test
    fun `a track held on the original is never replaced by a search already running`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
            pinned = true,
        )

        assertFalse(decision.allowed)
        assertEquals("pinned", decision.reason)
    }

    @Test
    fun `a stale answer for a track the queue left is refused`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = false,
            offsetMs = 0L,
            rate = 1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("track-changed", decision.reason)
    }

    @Test
    fun `an offset past the end of the replacement is refused`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 150_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 60_000L,
            rate = 1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("position-out-of-range", decision.reason)
    }

    @Test
    fun `a substitute already playing is mapped back through its own verified offset`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 42_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 1_500L,
            rate = 1.0,
            currentOffsetMs = 2_000L,
            currentRate = 1.0,
        )

        assertTrue(decision.allowed)
        assertEquals(41_500L, decision.targetPositionMs)
    }
}
