package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Skipping a track the resolver has ruled out, including the repeat-one case
 * where Media3's "next" is the same item and a naive skip loops forever.
 *
 * The queue is described by media ids rather than a count because "the next
 * row" can be a second copy of the recording that just failed, and skipping to
 * that copy is the same permanent loop wearing a different index.
 */
class SkipTargetPolicyTest {

    private fun ids(vararg values: String) = values.toList()

    @Test
    fun `an ordinary queue moves to the next row`() {
        assertEquals(
            3,
            SkipTargetPolicy.targetAfterFailure(2, 3, ids("a", "b", "c", "d", "e"), emptySet(), repeatOne = false),
        )
    }

    @Test
    fun `the last row with repeat off has nowhere to go`() {
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(
                4,
                SkipTargetPolicy.NO_TARGET,
                ids("a", "b", "c", "d", "e"),
                emptySet(),
                repeatOne = false,
            ),
        )
    }

    @Test
    fun `repeat one moves past the failed row instead of seeking to itself`() {
        assertEquals(
            3,
            SkipTargetPolicy.targetAfterFailure(2, 2, ids("a", "b", "c", "d", "e"), emptySet(), repeatOne = true),
        )
    }

    @Test
    fun `repeat one wraps past the last row`() {
        assertEquals(
            0,
            SkipTargetPolicy.targetAfterFailure(4, 4, ids("a", "b", "c", "d", "e"), emptySet(), repeatOne = true),
        )
    }

    @Test
    fun `a single row under repeat one cannot be skipped`() {
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(0, 0, ids("a"), emptySet(), repeatOne = true),
        )
    }

    @Test
    fun `an invalid current index has no target`() {
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(-1, 0, ids("a", "b", "c", "d", "e"), emptySet(), repeatOne = false),
        )
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(5, 0, ids("a", "b", "c", "d", "e"), emptySet(), repeatOne = false),
        )
    }

    /**
     * The duplicate that the old index-only rule could not see: `nextIndex`
     * points at another row, but that row is the recording that just failed.
     * Skipping to it prepares the same media id and loops.
     */
    @Test
    fun `a duplicate of the failed recording is stepped over`() {
        assertEquals(
            3,
            SkipTargetPolicy.targetAfterFailure(
                1,
                2,
                ids("a", "failed", "failed", "c"),
                emptySet(),
                repeatOne = false,
            ),
        )
    }

    @Test
    fun `every row being the failed recording leaves nowhere to go`() {
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(
                1,
                2,
                ids("failed", "failed", "failed"),
                emptySet(),
                repeatOne = true,
            ),
        )
    }

    @Test
    fun `rows the resolver already refused are stepped over`() {
        assertEquals(
            3,
            SkipTargetPolicy.targetAfterFailure(
                1,
                2,
                ids("a", "failed", "b", "c"),
                setOf("b"),
                repeatOne = false,
            ),
        )
    }

    @Test
    fun `all other rows being refused leaves nowhere to go`() {
        assertEquals(
            SkipTargetPolicy.NO_TARGET,
            SkipTargetPolicy.targetAfterFailure(
                0,
                1,
                ids("failed", "b", "c"),
                setOf("b", "c"),
                repeatOne = true,
            ),
        )
    }

    @Test
    fun `repeat one still finds a later distinct row after duplicates`() {
        assertEquals(
            3,
            SkipTargetPolicy.targetAfterFailure(
                1,
                2,
                ids("a", "failed", "failed", "c"),
                emptySet(),
                repeatOne = true,
            ),
        )
    }
}
