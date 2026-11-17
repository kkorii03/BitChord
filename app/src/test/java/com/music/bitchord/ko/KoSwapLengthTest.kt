package com.music.bitchord.ko

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.playback.HqSwapPolicy
import com.music.bitchord.playback.smart.HqVerificationStore
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 11-17, NR2b-2. The audition upgrade compared a proved copy's length with
 * the YouTube copy's raw length, so a studio file without YouTube's 15 s intro
 * (verified offset -15 s) was refused as "replacement is 185000ms against
 * 200000ms" on every attempt. The expected length now goes through the verdicts,
 * the same figure the mid-song lossless swap already used.
 */
class KoSwapLengthTest {

    private val slackMs = 5_000L

    private fun verified(offsetMs: Long, rate: Double = 1.0) =
        HqVerificationStore.Verified(offsetMs = offsetMs, rate = rate, matchPercent = 95, tier = HqTier.NORMAL)

    @Test
    fun aCopyWithAShorterIntroIsExpectedThatMuchShorter() {
        val youtubeLength = 200_000L
        val candidateLength = 185_000L
        // The old audition check, raw length against raw length: refused.
        assertTrue(abs(candidateLength - youtubeLength) > slackMs)
        val expected = KoSwapLength.expectedMs(youtubeLength, 0L, 1.0, verified(-15_000L))
        assertEquals(185_000L, expected)
        assertTrue(abs(candidateLength - expected) <= slackMs)
    }

    @Test
    fun aCopyWithNoVerdictKeepsTheRawLength() {
        assertEquals(200_000L, KoSwapLength.expectedMs(200_000L, 0L, 1.0, null))
    }

    @Test
    fun anUnknownLengthIsLeftForTheCallerToSkip() {
        assertEquals(0L, KoSwapLength.expectedMs(0L, 0L, 1.0, verified(-15_000L)))
        val unset = Long.MIN_VALUE + 1 // C.TIME_UNSET
        assertEquals(unset, KoSwapLength.expectedMs(unset, 0L, 1.0, verified(-15_000L)))
    }

    @Test
    fun aSubstitutePlayingNowIsMappedBackThroughItsOwnVerdict() {
        // Playing: a copy 2 s longer at the head than YouTube (202 s for YouTube's 200 s).
        val expected = KoSwapLength.expectedMs(202_000L, 2_000L, 1.0, verified(-15_000L))
        assertEquals(185_000L, expected)
    }

    @Test
    fun theFigureIsTheOneTheLosslessSwapAlwaysUsed() {
        val incoming = verified(offsetMs = 3_400L, rate = 1.004)
        val old = HqSwapPolicy.targetPositionMs(
            HqSwapPolicy.toYoutubeMs(231_000L, -1_200L, 0.998),
            incoming.offsetMs,
            incoming.rate,
        )
        assertEquals(old, KoSwapLength.expectedMs(231_000L, -1_200L, 0.998, incoming))
    }
}
