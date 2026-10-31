package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextPrepSkipReasonTest {

    private fun facts(
        lyricsOff: Boolean = false,
        syncedLyrics: Boolean = true,
        hasLyrics: Boolean = true,
        hasTimedLines: Boolean = true,
        fileTimed: Boolean = false,
        recordTimed: Boolean = true,
        timedToVideo: Boolean = false,
        heavyWorkAllowed: Boolean = true,
        aiTimingAhead: Boolean = true,
        aiUsable: Boolean = true,
        currentPlayedMs: Long = NextPrepPolicy.AI_MIN_PLAYED_MS,
        remainingMs: Long = 60_000L,
        hasTiming: Boolean = false,
    ) = NextPrepPolicy.SyncFacts(
        lyricsOff = lyricsOff,
        syncedLyrics = syncedLyrics,
        hasLyrics = hasLyrics,
        hasTimedLines = hasTimedLines,
        fileTimed = fileTimed,
        recordTimed = recordTimed,
        timedToVideo = timedToVideo,
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
            "skipped(no-timed-lines)",
            NextPrepPolicy.syncStatus(facts(hasTimedLines = false)),
        )
        assertEquals(
            "skipped(deadline)",
            NextPrepPolicy.syncStatus(facts(remainingMs = 0L)),
        )
    }

    @Test
    fun theReasonNamesTheFirstThingThatStoppedThePath() {
        assertEquals("lyrics-off", NextPrepPolicy.syncSkipReason(facts(lyricsOff = true, hasLyrics = false)))
        assertEquals("synced-off", NextPrepPolicy.syncSkipReason(facts(syncedLyrics = false, hasLyrics = false)))
        assertEquals("no-lyrics", NextPrepPolicy.syncSkipReason(facts(hasLyrics = false, fileTimed = true)))
        assertEquals("no-timed-lines", NextPrepPolicy.syncSkipReason(facts(hasTimedLines = false)))
        assertEquals("timed-to-video", NextPrepPolicy.syncSkipReason(facts(timedToVideo = true)))
        assertEquals("file-timed", NextPrepPolicy.syncSkipReason(facts(fileTimed = true)))
        assertEquals("file-timed", NextPrepPolicy.syncSkipReason(facts(recordTimed = false)))
        assertEquals("heavy-off", NextPrepPolicy.syncSkipReason(facts(heavyWorkAllowed = false)))
        assertEquals("deadline", NextPrepPolicy.syncSkipReason(facts(remainingMs = -1L)))
        assertEquals("ai-ahead-off", NextPrepPolicy.syncSkipReason(facts(aiTimingAhead = false)))
        assertEquals("ai-unavailable", NextPrepPolicy.syncSkipReason(facts(aiUsable = false)))
        assertEquals("ai-too-early", NextPrepPolicy.syncSkipReason(facts(currentPlayedMs = 0L)))
        assertEquals("no-answer", NextPrepPolicy.syncSkipReason(facts()))
    }

    @Test
    fun aVideoWhoseTimesAreItsOwnSaysTimedToVideoNotAnAiGate() {
        assertEquals(
            "skipped(timed-to-video)",
            NextPrepPolicy.syncStatus(facts(timedToVideo = true, aiTimingAhead = false)),
        )
    }

    @Test
    fun syncedLyricsOffIsNamedBeforeTheMissingLyrics() {
        assertEquals(
            "skipped(synced-off)",
            NextPrepPolicy.syncStatus(facts(syncedLyrics = false, hasLyrics = false)),
        )
    }

    @Test
    fun everySkipReasonUsesTheSkippedParenthesesFormat() {
        val cases = listOf(
            facts(lyricsOff = true, hasLyrics = false) to "lyrics-off",
            facts(syncedLyrics = false, hasLyrics = false) to "synced-off",
            facts(hasLyrics = false) to "no-lyrics",
            facts(hasTimedLines = false) to "no-timed-lines",
            facts(timedToVideo = true) to "timed-to-video",
            facts(fileTimed = true) to "file-timed",
            facts(heavyWorkAllowed = false) to "heavy-off",
            facts(remainingMs = 0L) to "deadline",
            facts(aiTimingAhead = false) to "ai-ahead-off",
            facts(aiUsable = false) to "ai-unavailable",
            facts(currentPlayedMs = 0L) to "ai-too-early",
            facts() to "no-answer",
        )
        cases.forEach { (facts, reason) ->
            assertEquals("skipped($reason)", NextPrepPolicy.syncStatus(facts))
        }
    }

    @Test
    fun aMissingAnswerIsLabelledWithItsReason() {
        assertEquals("none(deadline)", NextPrepPolicy.noneWithReason("deadline"))
        assertEquals("none(none)", NextPrepPolicy.noneWithReason(null))
    }
}
