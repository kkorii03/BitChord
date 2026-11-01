package com.music.bitchord.playback

import com.music.bitchord.playback.NextPrepPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheduling, not just planning: the next-prep bundle's completed marker is
 * what [NextPrepPolicy.alreadyHandled] reads before it starts a run. Clearing
 * only the mix attempt on a deadline therefore was not enough — the completed
 * marker still told the next event that the row was done.
 */
class NextPrepScheduleAfterTimeoutTest {

    private val rowId = "row"
    private val queueKey = "row|video"

    private fun scheduleAfterRun(
        mixTimedOut: Boolean,
        attempted: Set<Step>,
    ): Pair<Boolean, List<Step>> {
        val completedQueueKey = queueKey.takeIf {
            NextPrepPolicy.keepCompletedAfter(mixTimedOut)
        }
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
                wanted = setOf(Step.MIX),
                attempted = attempted,
            ),
        )
        return handled to plan.steps
    }

    @Test
    fun `a timed-out mix lets the same row be scheduled again`() {
        val (handled, steps) = scheduleAfterRun(mixTimedOut = true, attempted = emptySet())

        assertFalse(handled)
        assertEquals(listOf(Step.MIX), steps)
    }

    @Test
    fun `a finished run still skips the same row on the next event`() {
        val (handled, _) = scheduleAfterRun(mixTimedOut = false, attempted = setOf(Step.MIX))

        assertTrue(handled)
    }
}
