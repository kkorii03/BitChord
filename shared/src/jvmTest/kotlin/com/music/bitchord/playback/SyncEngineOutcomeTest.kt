package com.music.bitchord.playback

import com.music.bitchord.playback.NextPrepPolicy.SyncFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine stage of one next-prep sync. A Deepgram transcript that did not
 * fit still answered (the old code called that `failed:error` when Gemini was
 * already spent), a 429 is its own reason, and a failed attempt is never kept
 * as confirmed while a healthy no-answer is.
 */
class SyncEngineOutcomeTest {

    private fun facts(
        hasTiming: Boolean = false,
        failure: SyncFailure? = null,
        remainingMs: Long = 60_000L,
        currentPlayedMs: Long = NextPrepPolicy.AI_MIN_PLAYED_MS,
    ) = NextPrepPolicy.SyncFacts(
        hasLyrics = true,
        remainingMs = remainingMs,
        currentPlayedMs = currentPlayedMs,
        hasTiming = hasTiming,
        failure = failure,
    )

    @Test
    fun aTranscriptThatDidNotFitIsAnAnswerEvenWhenGeminiIsSpent() {
        assertNull(
            NextPrepPolicy.engineFailure(
                anyEngineAnswered = true,
                rateLimited = false,
                audioPresent = true,
            ),
        )
        // An answer outranks a refusal from another engine.
        assertNull(
            NextPrepPolicy.engineFailure(
                anyEngineAnswered = true,
                rateLimited = true,
                audioPresent = true,
            ),
        )
        assertTrue(NextPrepPolicy.shouldStoreSyncResult(null as SyncFailure?))
    }

    @Test
    fun a429WithNoAnswerIsRateLimitedNotAGenericError() {
        assertEquals(
            SyncFailure.RATE_LIMIT,
            NextPrepPolicy.engineFailure(
                anyEngineAnswered = false,
                rateLimited = true,
                audioPresent = true,
            ),
        )
        assertEquals(
            "failed:rate-limit",
            NextPrepPolicy.syncResult(facts(failure = SyncFailure.RATE_LIMIT)),
        )
        assertFalse(NextPrepPolicy.shouldStoreSyncResult(SyncFailure.RATE_LIMIT))
    }

    @Test
    fun noAudioAndNoAnswerKeepTheirOwnReasons() {
        assertEquals(
            SyncFailure.NO_AUDIO,
            NextPrepPolicy.engineFailure(false, rateLimited = false, audioPresent = false),
        )
        assertEquals(
            SyncFailure.ERROR,
            NextPrepPolicy.engineFailure(false, rateLimited = false, audioPresent = true),
        )
    }

    @Test
    fun everyFailureIsNotStoredAndAHealthyNoAnswerIsStored() {
        SyncFailure.values().forEach { failure ->
            assertFalse(NextPrepPolicy.shouldStoreSyncResult(failure))
            assertTrue(NextPrepPolicy.syncResult(facts(failure = failure)).startsWith("failed:"))
        }
        assertTrue(NextPrepPolicy.shouldStoreSyncResult(null as SyncFailure?))
        assertEquals("no-answer", NextPrepPolicy.syncResult(facts()))
        assertTrue(NextPrepPolicy.shouldStoreSyncResult(facts()))
    }
}
