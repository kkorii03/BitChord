package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Skipping a track the resolver has ruled out, including the repeat-one case
 * where Media3's "next" is the same item and a naive skip loops forever.
 */
class SkipTargetPolicyTest {

    @Test
    fun `an ordinary queue moves to the next row`() {
        assertEquals(3, SkipTargetPolicy.targetAfterFailure(2, 3, 5, repeatOne = false))
    }

    @Test
    fun `the last row with repeat off has nowhere to go`() {
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(4, SkipTargetPolicy.NO_TARGET, 5, repeatOne = false),
        )
    }

    @Test
    fun `repeat one moves past the failed row instead of seeking to itself`() {
        assertEquals(3, SkipTargetPolicy.targetAfterFailure(2, 2, 5, repeatOne = true))
    }

    @Test
    fun `repeat one wraps past the last row`() {
        assertEquals(0, SkipTargetPolicy.targetAfterFailure(4, 4, 5, repeatOne = true))
    }

    @Test
    fun `a single row under repeat one cannot be skipped`() {
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(0, 0, 1, repeatOne = true),
        )
    }

    @Test
    fun `an invalid current index has no target`() {
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(-1, 0, 5, repeatOne = false),
        )
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(5, 0, 5, repeatOne = false),
        )
    }
}
