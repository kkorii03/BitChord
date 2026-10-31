package com.music.bitchord.playback

import com.music.bitchord.playback.NextPrepPolicy.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun aVideoRadioRowIsNotAReplaceableLosslessCandidate() {
        assertFalse(
            NextPrepPolicy.hqStepWanted(
                isVideo = false,
                videoRadio = true,
                localFile = false,
                sourceKey = false,
                canSubstitute = true,
            ),
        )
        assertTrue(
            NextPrepPolicy.hqStepWanted(
                isVideo = false,
                videoRadio = false,
                localFile = false,
                sourceKey = false,
                canSubstitute = true,
            ),
        )
        assertFalse(
            NextPrepPolicy.hqStepWanted(
                isVideo = true,
                videoRadio = false,
                localFile = false,
                sourceKey = false,
                canSubstitute = true,
            ),
        )
    }

    @Test
    fun aBlankRowIdMeansNoWorkAndNoRow() {
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = "   ",
                queueKey = "current|row-1",
                wanted = Step.entries.toSet(),
            ),
        )
        assertEquals(null, plan.rowId)
        assertEquals("current|row-1", plan.queueKey)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun anEmptyWantedSetPlansNothing() {
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = "row-1",
                queueKey = "current|row-1",
                wanted = emptySet(),
            ),
        )
        assertEquals("row-1", plan.rowId)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun stepsAlwaysComeOutInTheirFixedOrder() {
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = "row-1",
                queueKey = null,
                wanted = setOf(Step.MIX, Step.VERSION, Step.SYNC, Step.LYRICS, Step.HQ),
            ),
        )
        assertEquals(Step.entries, plan.steps)
    }

    @Test
    fun preparedAndAttemptedBothRemoveAStep() {
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = "row-1",
                queueKey = null,
                wanted = Step.entries.toSet(),
                prepared = setOf(Step.HQ),
                attempted = setOf(Step.HQ, Step.SYNC),
            ),
        )
        assertEquals(listOf(Step.VERSION, Step.LYRICS, Step.MIX), plan.steps)
    }

    @Test
    fun aStepOutsideTheWantedSetIsNeverPlanned() {
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = "row-1",
                queueKey = null,
                wanted = setOf(Step.LYRICS),
                prepared = Step.entries.toSet(),
            ),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun aBlankStartedRowStillBelongsToTheRunningBundle() {
        assertFalse(
            NextPrepPolicy.shouldCancel(
                startedRowId = "",
                startedQueueKey = null,
                currentRowId = "",
                currentQueueKey = null,
            ),
        )
        assertTrue(
            NextPrepPolicy.shouldCancel(
                startedRowId = "",
                startedQueueKey = null,
                currentRowId = "row-1",
                currentQueueKey = null,
            ),
        )
    }

    @Test
    fun aBundleIsCancelledWhenTheNextRowDisappears() {
        assertTrue(
            NextPrepPolicy.shouldCancel(
                startedRowId = "row-1",
                startedQueueKey = "current|row-1",
                currentRowId = null,
                currentQueueKey = null,
            ),
        )
    }

    @Test
    fun aNullQueueKeyOnOneSideOnlyCountsAsAChangedPair() {
        assertTrue(
            NextPrepPolicy.shouldCancel(
                startedRowId = "row-1",
                startedQueueKey = null,
                currentRowId = "row-1",
                currentQueueKey = "current|row-1",
            ),
        )
    }

    @Test
    fun theSameRowAndNoQueueKeysSurvives() {
        assertFalse(
            NextPrepPolicy.shouldCancel(
                startedRowId = "row-1",
                startedQueueKey = null,
                currentRowId = "row-1",
                currentQueueKey = null,
            ),
        )
    }

    @Test
    fun aSingleItemQueueWrappedByRepeatAllIsNotANextRow() {
        assertFalse(NextPrepPolicy.hasDistinctNextRow(nextIndex = 0, currentIndex = 0))
    }

    @Test
    fun aRealNextRowIsDistinct() {
        assertTrue(NextPrepPolicy.hasDistinctNextRow(nextIndex = 1, currentIndex = 0))
    }

    @Test
    fun noNextRowIsNeverPrepared() {
        assertFalse(NextPrepPolicy.hasDistinctNextRow(nextIndex = null, currentIndex = 0))
    }

    @Test
    fun aNextRowWithNoCurrentRowIsStillPrepared() {
        assertTrue(NextPrepPolicy.hasDistinctNextRow(nextIndex = 0, currentIndex = null))
    }

    @Test
    fun changingTheLyricsSettingsMakesItANewSound() {
        val before = NextPrepPolicy.soundKey("row-1", "video", localFile = false, lyricsSettings = "lrclib")
        val after = NextPrepPolicy.soundKey("row-1", "video", localFile = false, lyricsSettings = "kugou|lrclib")
        assertNotEquals(before, after)
        assertFalse(
            NextPrepPolicy.alreadyHandled(
                startedRowId = "row-1",
                startedQueueKey = before,
                rowId = "row-1",
                queueKey = after,
                running = false,
                completedQueueKey = before,
            ),
        )
        assertTrue(NextPrepPolicy.shouldCancel("row-1", before, "row-1", after))
    }

    @Test
    fun theSameSoundWithTheSameSettingsIsStillLeftAlone() {
        val sound = NextPrepPolicy.soundKey("row-1", "video", localFile = true, lyricsSettings = "lrclib")
        assertTrue(
            NextPrepPolicy.alreadyHandled(
                startedRowId = "row-1",
                startedQueueKey = sound,
                rowId = "row-1",
                queueKey = sound,
                running = false,
                completedQueueKey = sound,
            ),
        )
        assertFalse(NextPrepPolicy.shouldCancel("row-1", sound, "row-1", sound))
    }

    @Test
    fun theSameRowWithANewSoundIsPreparedAgainEvenWhileTheOldJobIsActive() {
        assertFalse(
            NextPrepPolicy.alreadyHandled(
                startedRowId = "row-1",
                startedQueueKey = "row-1|video|false",
                rowId = "row-1",
                queueKey = "row-1|video|true",
                running = true,
                completedQueueKey = null,
            ),
        )
    }

    @Test
    fun theSameRowAndSoundIsLeftAlone() {
        assertTrue(
            NextPrepPolicy.alreadyHandled(
                startedRowId = "row-1",
                startedQueueKey = "row-1|video|false",
                rowId = "row-1",
                queueKey = "row-1|video|false",
                running = true,
                completedQueueKey = null,
            ),
        )
        assertTrue(
            NextPrepPolicy.alreadyHandled(
                startedRowId = "row-1",
                startedQueueKey = "row-1|video|false",
                rowId = "row-1",
                queueKey = "row-1|video|false",
                running = false,
                completedQueueKey = "row-1|video|false",
            ),
        )
    }

    @Test
    fun aCompletedBundleDoesNotCoverTheSameRowsNewSound() {
        assertFalse(
            NextPrepPolicy.alreadyHandled(
                startedRowId = "row-1",
                startedQueueKey = "row-1|video|false",
                rowId = "row-1",
                queueKey = "row-1|video|true",
                running = false,
                completedQueueKey = "row-1|video|false",
            ),
        )
    }

    @Test
    fun aResolvedSongVersionIsADifferentStateFromTheVideoRow() {
        assertFalse(
            NextPrepPolicy.stateKey("row-1", "video-id") ==
                NextPrepPolicy.stateKey("row-1", "song-version-id"),
        )
        assertEquals(
            NextPrepPolicy.stateKey("row-1", "video-id"),
            NextPrepPolicy.stateKey("row-1", "video-id"),
        )
    }

    @Test
    fun aVideoOrSubstituteNeverRunsTheSongSyncStep() {
        assertTrue(NextPrepPolicy.syncMayRun(isVideo = false, substituted = false))
        assertFalse(NextPrepPolicy.syncMayRun(isVideo = true, substituted = false))
        assertFalse(NextPrepPolicy.syncMayRun(isVideo = false, substituted = true))
    }

    @Test
    fun aFreshNextRowWithNoStoredTimingEntryStillRunsItsSongCheck() {
        // The next row is usually a track that has never played, so it has no
        // timing entry until the check itself writes one. Treating that as
        // "nothing to do" produced sync=skipped(no-answer) for every first-time
        // next row without ever asking the engine.
        assertTrue(
            NextPrepPolicy.songCheckMayStart(
                hasEntry = false,
                retryHeld = false,
                engineAllowed = true,
            ),
        )
        assertTrue(
            NextPrepPolicy.songCheckMayStart(
                hasEntry = true,
                retryHeld = false,
                engineAllowed = true,
            ),
        )
    }

    @Test
    fun aSongCheckStillWaitsForItsRetryOrAMissingEngine() {
        assertFalse(
            NextPrepPolicy.songCheckMayStart(
                hasEntry = false,
                retryHeld = true,
                engineAllowed = true,
            ),
        )
        assertFalse(
            NextPrepPolicy.songCheckMayStart(
                hasEntry = true,
                retryHeld = false,
                engineAllowed = false,
            ),
        )
    }
}
