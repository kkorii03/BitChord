package com.music.bitchord.playback

import com.music.bitchord.playback.NextPrepPolicy.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A planned MIX that never ran because an earlier step (or the lossless
 * download) used the track's remaining time is still unanswered, so its row
 * must remain schedulable on the next resume/queue event.
 */
class NextPrepDeadlineCompletionTest {

    private val rowId = "row"
    private val queueKey = "row|video"

    private fun completedAfterRun(
        deadlineHit: Boolean,
        mixTimedOut: Boolean,
        mixPlanned: Boolean,
    ): String? = NextPrepPolicy.completedQueueKeyAfterRun(
        queueKey = queueKey,
        mixTimedOut = mixTimedOut,
        deadlineHit = deadlineHit,
        mixPlanned = mixPlanned,
    )

    private fun handled(completedQueueKey: String?): Boolean =
        NextPrepPolicy.alreadyHandled(
            startedRowId = rowId,
            startedQueueKey = queueKey,
            rowId = rowId,
            queueKey = queueKey,
            running = false,
            completedQueueKey = completedQueueKey,
        )

    @Test
    fun anEarlierStepUsingTheDeadlineLeavesAPlannedMixSchedulable() {
        val completed = completedAfterRun(
            deadlineHit = true,
            mixTimedOut = false,
            mixPlanned = true,
        )

        assertNull(completed)
        assertFalse(handled(completed))
        assertEquals(
            listOf(Step.MIX),
            NextPrepPolicy.plan(
                NextPrepPolicy.Request(
                    rowId = rowId,
                    queueKey = queueKey,
                    wanted = setOf(Step.MIX),
                    attempted = setOf(Step.SYNC),
                ),
            ).steps,
        )
    }

    @Test
    fun aMixCutOffInItsOwnWaitLeavesTheRowSchedulable() {
        val completed = completedAfterRun(
            deadlineHit = true,
            mixTimedOut = true,
            mixPlanned = true,
        )

        assertNull(completed)
        assertFalse(handled(completed))
    }

    @Test
    fun aCompletedRunMarksTheRowHandled() {
        val completed = completedAfterRun(
            deadlineHit = false,
            mixTimedOut = false,
            mixPlanned = true,
        )

        assertEquals(queueKey, completed)
        assertTrue(handled(completed))
    }
}
