package com.music.bitchord.playback

import com.music.bitchord.playback.NextPrepPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheduling, not just planning: the next-prep bundle's completed marker is
 * what [NextPrepPolicy.alreadyHandled] reads before it starts a run. The marker
 * must be absent for every unanswered mix, including one that never started
 * because an earlier step used the remaining time.
 */
class NextPrepScheduleAfterTimeoutTest {

    private val rowId = "row"
    private val queueKey = "row|video"

    private fun scheduleAfterRun(
        deadlineHit: Boolean,
        mixTimedOut: Boolean,
        mixPlanned: Boolean = true,
        wanted: Set<Step> = setOf(Step.MIX),
        attempted: Set<Step>,
    ): Pair<Boolean, List<Step>> {
        val completedQueueKey = NextPrepPolicy.completedQueueKeyAfterRun(
            queueKey = queueKey,
            mixTimedOut = mixTimedOut,
            deadlineHit = deadlineHit,
            mixPlanned = mixPlanned,
        )
        val handled = NextPrepPolicy.alreadyHandled(
            startedRowId = rowId,
            startedQueueKey = queueKey,
            rowId = rowId,
            queueKey = queueKey,
            running = false,
            completedQueueKey = completedQueueKey,
        )
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = rowId,
                queueKey = queueKey,
                wanted = wanted,
                attempted = attempted,
            ),
        )
        return handled to plan.steps
    }

    @Test
    fun `a mix skipped by an earlier step deadline lets the same row be scheduled again`() {
        // The real post-timeout set is not empty: the sync step was attempted
        // before the deadline, and MIX was never added because it never ran.
        val (handled, steps) = scheduleAfterRun(
            deadlineHit = true,
            mixTimedOut = false,
            attempted = setOf(Step.SYNC),
        )

        assertFalse(handled)
        assertEquals(listOf(Step.MIX), steps)
    }

    @Test
    fun `a mix cut off in its own wait is retried`() {
        val (handled, steps) = scheduleAfterRun(
            deadlineHit = true,
            mixTimedOut = true,
            attempted = setOf(Step.SYNC),
        )

        assertFalse(handled)
        assertEquals(listOf(Step.MIX), steps)
    }

    @Test
    fun `a finished run still skips the same row on the next event`() {
        val (handled, _) = scheduleAfterRun(
            deadlineHit = false,
            mixTimedOut = false,
            attempted = setOf(Step.MIX),
        )

        assertTrue(handled)
    }

    @Test
    fun `a deadline without a planned mix still completes the row`() {
        val (handled, _) = scheduleAfterRun(
            deadlineHit = true,
            mixTimedOut = false,
            mixPlanned = false,
            wanted = setOf(Step.SYNC),
            attempted = setOf(Step.SYNC),
        )

        assertTrue(handled)
    }
}
