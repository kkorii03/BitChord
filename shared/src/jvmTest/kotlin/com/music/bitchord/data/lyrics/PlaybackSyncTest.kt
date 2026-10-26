package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One table for the whole sync rule: the copy playing (YouTube or HQ), an AI
 * shift or none, and a correction by hand or none. The HQ offset must be added
 * exactly once, after the YouTube-based answer and before the hand's shift.
 */
class PlaybackSyncTest {

    private val youtube = PlaybackSync.Source.YOUTUBE
    private val hq = PlaybackSync.Source.HQ

    private val aiShift = LyricsTimeMap.shift(5_000L)
    private val hqOffset = LyricsTimeMap.linear(rate = 1.0, offsetMs = 1_200L)
    private val line = 10_000L

    @Test
    fun youtubeSourceKeepsTheYoutubeClockAndIgnoresAnyHqMap() {
        assertEquals(10_000L, PlaybackSync.at(line, null, youtube, hqOffset, manualMs = 0L))
        assertEquals(15_000L, PlaybackSync.at(line, aiShift, youtube, hqOffset, manualMs = 0L))
        assertEquals(10_500L, PlaybackSync.at(line, null, youtube, hqOffset, manualMs = 500L))
        assertEquals(15_500L, PlaybackSync.at(line, aiShift, youtube, hqOffset, manualMs = 500L))
    }

    @Test
    fun hqSourceAddsTheHqOffsetOnceAfterTheYoutubeAnswer() {
        assertEquals(11_200L, PlaybackSync.at(line, null, hq, hqOffset, manualMs = 0L))
        assertEquals(16_200L, PlaybackSync.at(line, aiShift, hq, hqOffset, manualMs = 0L))
        assertEquals(11_700L, PlaybackSync.at(line, null, hq, hqOffset, manualMs = 500L))
        assertEquals(16_700L, PlaybackSync.at(line, aiShift, hq, hqOffset, manualMs = 500L))
    }

    @Test
    fun anAnswerAlreadyOnTheHqClockIsNotMovedAgain() {
        assertEquals(15_000L, PlaybackSync.at(line, aiShift, hq, hqOffset, manualMs = 0L, baseOnPlayingClock = true))
        assertEquals(15_500L, PlaybackSync.at(line, aiShift, hq, hqOffset, manualMs = 500L, baseOnPlayingClock = true))
        assertFalse(PlaybackSync.hqApplies(hq, baseOnPlayingClock = true))
    }

    @Test
    fun onlyAnHqCopyHasASecondClock() {
        assertTrue(PlaybackSync.hqApplies(hq, baseOnPlayingClock = false))
        assertFalse(PlaybackSync.hqApplies(youtube, baseOnPlayingClock = false))
        assertFalse(PlaybackSync.hqApplies(hq, baseOnPlayingClock = true))
    }

    @Test
    fun theHqRateStretchesTheYoutubeAnswerBeforeTheHandShift() {
        val map = LyricsTimeMap.linear(rate = 1.01, offsetMs = 1_200L)
        assertEquals(11_300L, PlaybackSync.at(line, null, hq, map, manualMs = 0L))
        assertEquals(16_400L, PlaybackSync.at(line, aiShift, hq, map, manualMs = 50L))
    }
}
