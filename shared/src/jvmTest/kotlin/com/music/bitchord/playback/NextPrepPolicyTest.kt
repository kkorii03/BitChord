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

    @Test
    fun aRunningBundleForTheSameRecordingIsLeftAlone() {
        assertEquals(
            NextPrepPolicy.ScheduleAction.IGNORE,
            NextPrepPolicy.scheduleAction(
                running = true,
                startedRowId = "row-1",
                startedQueueKey = "row-1|old",
                completedRowId = null,
                completedQueueKey = null,
                nextRowId = "row-1",
                nextQueueKey = "row-1|old",
            ),
        )
    }

    @Test
    fun aRunningBundleRestartsWhenTheSameRowChangesRecording() {
        assertEquals(
            NextPrepPolicy.ScheduleAction.RESTART,
            NextPrepPolicy.scheduleAction(
                running = true,
                startedRowId = "row-1",
                startedQueueKey = "row-1|old",
                completedRowId = null,
                completedQueueKey = null,
                nextRowId = "row-1",
                nextQueueKey = "row-1|new",
            ),
        )
    }

    @Test
    fun aFinishedBundleForTheSameRecordingIsNotRepeated() {
        assertEquals(
            NextPrepPolicy.ScheduleAction.IGNORE,
            NextPrepPolicy.scheduleAction(
                running = false,
                startedRowId = "row-1",
                startedQueueKey = "row-1|old",
                completedRowId = "row-1",
                completedQueueKey = "row-1|old",
                nextRowId = "row-1",
                nextQueueKey = "row-1|old",
            ),
        )
    }

    @Test
    fun aFinishedBundleRestartsWhenTheRecordingChanged() {
        assertEquals(
            NextPrepPolicy.ScheduleAction.RESTART,
            NextPrepPolicy.scheduleAction(
                running = false,
                startedRowId = "row-1",
                startedQueueKey = "row-1|old",
                completedRowId = "row-1",
                completedQueueKey = "row-1|old",
                nextRowId = "row-1",
                nextQueueKey = "row-1|new",
            ),
        )
    }

    @Test
    fun aDifferentNextRowRestartsAndNoNextRowStops() {
        assertEquals(
            NextPrepPolicy.ScheduleAction.RESTART,
            NextPrepPolicy.scheduleAction(
                running = true,
                startedRowId = "row-1",
                startedQueueKey = "row-1|old",
                completedRowId = null,
                completedQueueKey = null,
                nextRowId = "row-2",
                nextQueueKey = "row-2|new",
            ),
        )
        assertEquals(
            NextPrepPolicy.ScheduleAction.STOP,
            NextPrepPolicy.scheduleAction(
                running = true,
                startedRowId = "row-1",
                startedQueueKey = "row-1|old",
                completedRowId = null,
                completedQueueKey = null,
                nextRowId = null,
                nextQueueKey = null,
            ),
        )
    }

    @Test
    fun theMemoKeyDistinguishesTwoRecordingsOfOneRow() {
        val old = NextPrepPolicy.recordingKey("row-1", "old-video")
        val new = NextPrepPolicy.recordingKey("row-1", "new-video")
        assertTrue(old != new)
        assertEquals(old, NextPrepPolicy.recordingKey("row-1", "old-video"))
    }

    @Test
    fun aSyncFlightIsKeptWhenItsRecordingIsStillCurrent() {
        assertFalse(
            NextPrepPolicy.abandonSyncFlight(
                rowId = "row-1",
                songVideoId = "song",
                currentRowId = "row-1",
                currentMediaId = "song",
            ),
        )
        // Removing a queued duplicate of the playing song changes the row but
        // not the recording; LyricsController is still awaiting this flight.
        assertFalse(
            NextPrepPolicy.abandonSyncFlight(
                rowId = "row-1",
                songVideoId = "song",
                currentRowId = "row-2",
                currentMediaId = "song",
            ),
        )
        assertTrue(
            NextPrepPolicy.abandonSyncFlight(
                rowId = "row-1",
                songVideoId = "song",
                currentRowId = "row-current",
                currentMediaId = "other",
            ),
        )
        // A version swap of the same row is a different recording.
        assertTrue(
            NextPrepPolicy.abandonSyncFlight(
                rowId = "row-1",
                songVideoId = "song",
                currentRowId = "row-1",
                currentMediaId = "song-version",
            ),
        )
    }
}
