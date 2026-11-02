package com.music.bitchord.data.lyrics

import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole lyrics lookup — every variant and catalogue retry — must end at
 * one deadline instead of waiting out each provider again. On expiry the caller
 * gets what was already found, or null, never an endless spinner.
 */
class LyricsLookupDeadlineTest {

    @Test
    fun theDeadlineCountsDownAndEndsAtZero() {
        val deadline = LyricsRepository.LookupDeadline(totalMs = 12_000L, startedAtMs = 1_000L)
        assertEquals(12_000L, deadline.remainingMs(1_000L))
        assertEquals(2_000L, deadline.remainingMs(11_000L))
        assertFalse(deadline.expired(12_999L))
        assertTrue(deadline.expired(13_000L))
        assertEquals(0L, deadline.remainingMs(20_000L))
    }

    @Test
    fun anAnswerInTimeIsReturnedUnchanged() = runBlocking {
        val deadline = LyricsRepository.LookupDeadline(totalMs = 5_000L)
        val answer = LyricsRepository.beforeLookupDeadline(deadline) {
            delay(10)
            "found"
        }
        assertEquals("found", answer)
    }

    @Test
    fun aRetryPastTheDeadlineIsCutOffInsteadOfWaitingForEveryProvider() = runBlocking {
        val deadline = LyricsRepository.LookupDeadline(totalMs = 50L)
        var answer: String? = "ran"
        val elapsed = measureTimeMillis {
            answer = LyricsRepository.beforeLookupDeadline(deadline) {
                delay(10_000)
                "too late"
            }
        }
        assertNull(answer)
        assertTrue(elapsed < 5_000, "the retry must not wait for its full provider budget")
    }

    @Test
    fun anExpiredDeadlineStartsNothing() = runBlocking {
        val deadline = LyricsRepository.LookupDeadline(totalMs = 0L)
        var ran = false
        val answer = LyricsRepository.beforeLookupDeadline(deadline) {
            ran = true
            "found"
        }
        assertNull(answer)
        assertFalse(ran)
    }
}
