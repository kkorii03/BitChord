package com.music.bitchord.playback

import com.music.bitchord.playback.NextPrepPolicy.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextPrepPolicyTest {

    @Test
    fun stepsRunInOrderAndSkipWhatIsAlreadyStored() {
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = "row-1",
                queueKey = "current|row-1",
                wanted = Step.entries.toSet(),
                prepared = setOf(Step.VERSION, Step.LYRICS),
            ),
        )
        assertEquals(listOf(Step.HQ, Step.SYNC, Step.MIX), plan.steps)
    }

    @Test
    fun aStepAlreadyTriedThisSessionIsNotRetried() {
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = "row-1",
                queueKey = "current|row-1",
                wanted = setOf(Step.LYRICS, Step.SYNC),
                attempted = setOf(Step.LYRICS),
            ),
        )
        assertEquals(listOf(Step.SYNC), plan.steps)
    }

    @Test
    fun replayedSongWithEverythingStoredHasNothingToDo() {
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = "row-1",
                queueKey = "current|row-1",
                wanted = Step.entries.toSet(),
                prepared = Step.entries.toSet(),
            ),
        )
        assertTrue(plan.isEmpty)
        assertEquals(emptyList(), plan.steps)
    }

    @Test
    fun noNextRowMeansNoWork() {
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = null,
                queueKey = null,
                wanted = Step.entries.toSet(),
            ),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun sameRowSurvivesASeekOrAVersionSwap() {
        assertFalse(
            NextPrepPolicy.shouldCancel(
                startedRowId = "row-1",
                startedQueueKey = "current|row-1",
                currentRowId = "row-1",
                currentQueueKey = "current|row-1",
            ),
        )
    }

    @Test
    fun replacingTheNextRowCancelsTheBundle() {
        assertTrue(
            NextPrepPolicy.shouldCancel(
                startedRowId = "row-1",
                startedQueueKey = "current|row-1",
                currentRowId = "row-2",
                currentQueueKey = "current|row-2",
            ),
        )
    }

    @Test
    fun aChangedQueuePairCancelsEvenWhenTheRowIdIsTheSame() {
        assertTrue(
            NextPrepPolicy.shouldCancel(
                startedRowId = "row-1",
                startedQueueKey = "current|row-1",
                currentRowId = "row-1",
                currentQueueKey = "next-current|row-1",
            ),
        )
    }

    @Test
    fun nothingRunningIsNeverCancelled() {
        assertFalse(
            NextPrepPolicy.shouldCancel(
                startedRowId = null,
                startedQueueKey = null,
                currentRowId = "row-1",
                currentQueueKey = "current|row-1",
            ),
        )
    }
}
