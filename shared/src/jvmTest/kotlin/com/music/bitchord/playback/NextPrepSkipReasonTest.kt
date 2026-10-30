package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextPrepSkipReasonTest {

    private fun facts(
        lyricsOff: Boolean = false,
        hasLyrics: Boolean = true,
        fileTimed: Boolean = false,
        heavyWorkAllowed: Boolean = true,
        aiTimingAhead: Boolean = true,
        aiUsable: Boolean = true,
        currentPlayedMs: Long = NextPrepPolicy.AI_MIN_PLAYED_MS,
        remainingMs: Long = 60_000L,
        hasTiming: Boolean = false,
    ) = NextPrepPolicy.SyncFacts(
        lyricsOff = lyricsOff,
        hasLyrics = hasLyrics,
        fileTimed = fileTimed,
        heavyWorkAllowed = heavyWorkAllowed,
        aiTimingAhead = aiTimingAhead,
        aiUsable = aiUsable,
        currentPlayedMs = currentPlayedMs,
        remainingMs = remainingMs,
        hasTiming = hasTiming,
    )

    @Test
    fun remainingTimeStopsTwentySecondsBeforeTheTrackEnds() {
        assertEquals(20_000L, NextPrepPolicy.remainingMs(durationMs = 100_000L, positionMs = 60_000L))
        assertEquals(0L, NextPrepPolicy.remainingMs(durationMs = 100_000L, positionMs = 80_000L))
        assertFalse(NextPrepPolicy.canStartStep(0L))
        assertFalse(NextPrepPolicy.canStartStep(-1L))
        assertTrue(NextPrepPolicy.canStartStep(1L))
    }

    @Test
    fun thePaidEnginesWaitForThirtySecondsOfRealPlayback() {
        assertFalse(NextPrepPolicy.aiGateOpen(29_999L))
        assertTrue(NextPrepPolicy.aiGateOpen(30_000L))
        assertTrue(NextPrepPolicy.aiGateOpen(31_000L))
    }

    @Test
    fun aStoredTimingIsAppliedAndHasNoSkipReason() {
        assertNull(NextPrepPolicy.syncSkipReason(facts(hasTiming = true)))
        assertEquals("applied", NextPrepPolicy.syncStatus(facts(hasTiming = true)))
    }

    @Test
    fun syncSkippedUsesTheReasonTokenInParentheses() {
        assertEquals(
            "skipped(ai-too-early)",
            NextPrepPolicy.syncStatus(facts(currentPlayedMs = 5_000L)),
        )
        assertEquals(
            "skipped(no-lyrics)",
            NextPrepPolicy.syncStatus(facts(hasLyrics = false)),
        )
        assertEquals(
            "skipped(deadline)",
            NextPrepPolicy.syncStatus(facts(remainingMs = 0L)),
        )
    }

    @Test
    fun theReasonNamesTheFirstThingThatStoppedThePath() {
        assertEquals("lyrics-off", NextPrepPolicy.syncSkipReason(facts(lyricsOff = true, hasLyrics = false)))
        assertEquals("no-lyrics", NextPrepPolicy.syncSkipReason(facts(hasLyrics = false, fileTimed = true)))
        assertEquals("file-timed", NextPrepPolicy.syncSkipReason(facts(fileTimed = true)))
        assertEquals("heavy-off", NextPrepPolicy.syncSkipReason(facts(heavyWorkAllowed = false)))
        assertEquals("deadline", NextPrepPolicy.syncSkipReason(facts(remainingMs = -1L)))
        assertEquals("ai-ahead-off", NextPrepPolicy.syncSkipReason(facts(aiTimingAhead = false)))
        assertEquals("ai-unavailable", NextPrepPolicy.syncSkipReason(facts(aiUsable = false)))
        assertEquals("ai-too-early", NextPrepPolicy.syncSkipReason(facts(currentPlayedMs = 0L)))
        assertEquals("no-answer", NextPrepPolicy.syncSkipReason(facts()))
    }

    @Test
    fun aMissingAnswerIsLabelledWithItsReason() {
        assertEquals("none(deadline)", NextPrepPolicy.noneWithReason("deadline"))
        assertEquals("none(none)", NextPrepPolicy.noneWithReason(null))
    }
}
