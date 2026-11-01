package com.music.bitchord.playback

import com.music.bitchord.playback.NextPrepPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mix analysis is the last step and the one most likely to be cut off by
 * the end-of-track deadline. A timeout is not a refusal: the row is still next,
 * and the resume/queue events schedule the bundle again. Keeping the attempt
 * mark after a timeout made that second run skip the mix permanently.
 */
class NextPrepMixTimeoutTest {

    private fun planFor(attempted: Set<Step>) = NextPrepPolicy.plan(
        NextPrepPolicy.Request(
            rowId = "row",
            queueKey = "row|video",
            wanted = setOf(Step.MIX),
            attempted = attempted,
        ),
    )

    @Test
    fun `a timed-out mix is retried by the next scheduled run`() {
        assertFalse(NextPrepPolicy.keepMixAttemptAfter(completed = null))
        assertTrue(planFor(emptySet()).steps.contains(Step.MIX))
    }

    @Test
    fun `a refused mix stays remembered so events do not repeat the request`() {
        assertTrue(NextPrepPolicy.keepMixAttemptAfter(completed = false))
        assertTrue(NextPrepPolicy.keepMixAttemptAfter(completed = true))
        assertEquals(emptyList<Step>(), planFor(setOf(Step.MIX)).steps)
    }
}
