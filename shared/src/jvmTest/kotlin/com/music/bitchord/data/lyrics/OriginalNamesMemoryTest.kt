package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L8: a remembered "no original name" used to stand for good and for every
 * length, so a music video asking first left the audio track of the same song
 * without its original-name lyric rounds. Found names keep standing.
 */
class OriginalNamesMemoryTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aFoundNameAlwaysStands() {
        assertTrue(OriginalNames.answerStands(true, 0L, 0, 215, now))
    }

    @Test
    fun aNoneFoundForTheVideoLengthDoesNotAnswerTheTrack() {
        assertFalse(OriginalNames.answerStands(false, now - day, missSeconds = 280, seconds = 215, nowMs = now))
    }

    @Test
    fun aNoneForTheSameLengthStandsWhileFresh() {
        assertTrue(OriginalNames.answerStands(false, now - day, missSeconds = 280, seconds = 281, nowMs = now))
    }

    @Test
    fun aNoneIsAskedAgainAfterAWeek() {
        assertFalse(OriginalNames.answerStands(false, now - 8 * day, missSeconds = 280, seconds = 280, nowMs = now))
    }

    @Test
    fun aNoneWrittenBeforeTheStampIsAskedOnceMore() {
        assertFalse(OriginalNames.answerStands(false, 0L, missSeconds = 0, seconds = 280, nowMs = now))
    }

    @Test
    fun aNoneFoundWithNoLengthAnswersEveryLength() {
        assertTrue(OriginalNames.answerStands(false, now - day, missSeconds = 0, seconds = 215, nowMs = now))
    }

    @Test
    fun aNoneFoundWithALengthDoesNotAnswerAnAskWithout() {
        assertFalse(OriginalNames.answerStands(false, now - day, missSeconds = 280, seconds = 0, nowMs = now))
    }
}
