package com.music.bitchord.data.lyrics

import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole lyrics search — every variant and catalogue retry — must end at
 * one deadline instead of waiting out each provider again. On expiry the
 * caller gets what was already found, or null, never an endless spinner. The
 * Gemini transcription after a miss has its own, longer limit: it needs tens
 * of seconds, and the search's deadline would end every AI sheet.
 */
class LyricsLookupDeadlineTest {

    private val startNs = 1_000_000_000L

    @Test
    fun theDeadlineCountsDownAndEndsAtZero() {
        val clock = AtomicLong(startNs)
        val deadline = LyricsRepository.LookupDeadline(totalMs = 12_000L) { clock.get() }
        assertEquals(12_000L, deadline.remainingMs())
        clock.set(startNs + 10_000_000_000L)
        assertEquals(2_000L, deadline.remainingMs())
        clock.set(startNs + 11_999_000_000L)
        assertFalse(deadline.expired())
        clock.set(startNs + 12_000_000_000L)
        assertTrue(deadline.expired())
        assertEquals(0L, deadline.remainingMs())
    }

    @Test
    fun aBackwardWallClockCannotExtendTheDeadline() {
        // System.nanoTime is monotonic; a wall clock that jumps backwards
        // must not inflate the remaining time and keep the spinner up.
        var nowNs = startNs
        val deadline = LyricsRepository.LookupDeadline(totalMs = 12_000L) { nowNs }
        nowNs -= 5_000_000_000L
        assertEquals(12_000L, deadline.remainingMs())
        assertFalse(deadline.expired())
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

    @Test
    fun aHangingTranscriptionEndsAtItsOwnLimit() = runBlocking {
        val previous = LyricsRepository.transcriber
        val previousLimit = LyricsRepository.transcribeLimitMs
        LyricsRepository.transcribeLimitMs = 500L
        LyricsRepository.transcriber = { _, _ ->
            delay(60_000)
            null
        }
        try {
            val elapsed = measureTimeMillis {
                val found = LyricsRepository.lyrics(
                    videoId = "deadline-transcribe-no-sheet",
                    title = "No Sheet Anywhere",
                    artist = "Nobody",
                    durationMs = 200_000L,
                    // A hidden source is never raced, so the search misses at
                    // once and leaves the transcription fallback as the only
                    // thing still running.
                    sources = setOf(LyricsSource.SIMP_MUSIC),
                    order = listOf(LyricsSource.SIMP_MUSIC),
                    transcribe = true,
                )
                assertNull(found)
            }
            assertTrue(
                elapsed < LyricsRepository.transcribeLimitMs + 5_000L,
                "the lookup ran ${elapsed}ms, past its ${LyricsRepository.transcribeLimitMs}ms transcription limit",
            )
        } finally {
            LyricsRepository.transcriber = previous
            LyricsRepository.transcribeLimitMs = previousLimit
        }
    }

    /**
     * Gemini writing a sheet takes tens of seconds. Held to the 12 s search
     * deadline (lyrics-missing-fix r3 as delivered), every AI sheet was lost.
     */
    @Test
    fun aTranscriptionSlowerThanTheSearchDeadlineStillAnswers() = runBlocking {
        val previous = LyricsRepository.transcriber
        val previousDeadline = LyricsRepository.lookupDeadlineMs
        LyricsRepository.lookupDeadlineMs = 200L
        LyricsRepository.transcriber = { _, _ ->
            delay(1_000)
            listOf(LyricLine(timeMs = 1_000L, text = "written down by ear"))
        }
        try {
            val found = LyricsRepository.lyrics(
                videoId = "deadline-transcribe-slow",
                title = "Only Heard",
                artist = "Nobody",
                durationMs = 200_000L,
                sources = setOf(LyricsSource.SIMP_MUSIC),
                order = listOf(LyricsSource.SIMP_MUSIC),
                transcribe = true,
            )
            assertEquals(LyricsSource.GEMINI_TRANSCRIPT, found?.source)
        } finally {
            LyricsRepository.transcriber = previous
            LyricsRepository.lookupDeadlineMs = previousDeadline
        }
    }
}
