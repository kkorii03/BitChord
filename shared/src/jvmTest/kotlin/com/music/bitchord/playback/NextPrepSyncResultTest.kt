package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextPrepSyncResultTest {

    private fun facts(
        hasTiming: Boolean = false,
        failure: NextPrepPolicy.SyncFailure? = null,
        lyricsOff: Boolean = false,
        hasLyrics: Boolean = true,
        remainingMs: Long = 60_000L,
        currentPlayedMs: Long = NextPrepPolicy.AI_MIN_PLAYED_MS,
    ) = NextPrepPolicy.SyncFacts(
        lyricsOff = lyricsOff,
        hasLyrics = hasLyrics,
        currentPlayedMs = currentPlayedMs,
        remainingMs = remainingMs,
        hasTiming = hasTiming,
        failure = failure,
    )

    @Test
    fun aHealthyCheckWithNothingToCorrectIsNoAnswerAndIsStored() {
        val healthy = facts()
        assertEquals("no-answer", NextPrepPolicy.syncResult(healthy))
        assertEquals("none", NextPrepPolicy.syncResultReason(healthy))
        assertTrue(NextPrepPolicy.shouldStoreSyncResult(healthy))
    }

    @Test
    fun anAppliedTimingIsAnswered() {
        val answered = facts(hasTiming = true)
        assertEquals("answered", NextPrepPolicy.syncResult(answered))
        assertEquals("applied", NextPrepPolicy.syncResultReason(answered))
        assertTrue(NextPrepPolicy.shouldStoreSyncResult(answered))
    }

    @Test
    fun everyEngineFailureIsFailedAndIsNotStored() {
        val cases = listOf(
            NextPrepPolicy.SyncFailure.NO_AUDIO to "no-audio",
            NextPrepPolicy.SyncFailure.RATE_LIMIT to "rate-limit",
            NextPrepPolicy.SyncFailure.TOO_FEW_LINES to "too-few-lines",
            NextPrepPolicy.SyncFailure.ERROR to "error",
        )
        cases.forEach { (failure, token) ->
            val failed = facts(failure = failure)
            assertEquals("failed:$token", NextPrepPolicy.syncResult(failed))
            assertEquals(token, NextPrepPolicy.syncResultReason(failed))
            assertFalse(NextPrepPolicy.shouldStoreSyncResult(failed))
        }
    }

    @Test
    fun aPathThatNeverRanKeepsItsSkipReasonInsteadOfBecomingAFailure() {
        assertEquals("skipped:lyrics-off", NextPrepPolicy.syncResult(facts(lyricsOff = true)))
        assertEquals("skipped:no-lyrics", NextPrepPolicy.syncResult(facts(hasLyrics = false)))
        assertEquals("skipped:deadline", NextPrepPolicy.syncResult(facts(remainingMs = 0L)))
        assertTrue(NextPrepPolicy.shouldStoreSyncResult(facts(lyricsOff = true)))
    }

    @Test
    fun aFailureBehindAnEarlierGateDoesNotHideTheGate() {
        assertEquals(
            "skipped:no-lyrics",
            NextPrepPolicy.syncResult(
                facts(
                    hasLyrics = false,
                    failure = NextPrepPolicy.SyncFailure.NO_AUDIO,
                ),
            ),
        )
    }
}
