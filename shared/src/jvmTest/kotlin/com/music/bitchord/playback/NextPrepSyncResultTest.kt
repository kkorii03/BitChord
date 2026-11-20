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
        baseNone: Boolean = false,
    ) = NextPrepPolicy.SyncFacts(
        lyricsOff = lyricsOff,
        hasLyrics = hasLyrics,
        currentPlayedMs = currentPlayedMs,
        remainingMs = remainingMs,
        hasTiming = hasTiming,
        failure = failure,
        baseNone = baseNone,
    )

    /** N2: 11-20 Memories/Fansa logged `sync=applied` / `result=answered` after `base-apply: base=none`. */
    @Test
    fun aTimingWithBaseNoneIsANoAnswerNotApplied() {
        val none = facts(baseNone = true)
        assertEquals("skipped(base-none)", NextPrepPolicy.syncStatus(none))
        assertEquals("no-answer", NextPrepPolicy.syncResult(none))
        assertEquals("base-none", NextPrepPolicy.syncResultReason(none))
        assertTrue(NextPrepPolicy.shouldStoreSyncResult(none))
        // An engine that failed behind the none base now shows instead of hiding as `answered`.
        val failed = facts(baseNone = true, failure = NextPrepPolicy.SyncFailure.RATE_LIMIT)
        assertEquals("failed:rate-limit", NextPrepPolicy.syncResult(failed))
        assertFalse(NextPrepPolicy.shouldStoreSyncResult(failed))
        // A gate that stopped the paid half keeps naming itself.
        assertEquals("skipped:ai-too-early", NextPrepPolicy.syncResult(facts(baseNone = true, currentPlayedMs = 0L)))
        assertEquals("skipped:deadline", NextPrepPolicy.syncResult(facts(baseNone = true, remainingMs = 0L)))
    }

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
