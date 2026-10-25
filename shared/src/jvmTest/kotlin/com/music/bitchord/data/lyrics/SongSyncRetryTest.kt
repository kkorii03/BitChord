package com.music.bitchord.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 429 rules for a song check: the wait is the server's when it said one,
 * doubles otherwise, and a long wait is deferred instead of retried inline.
 */
class SongSyncRetryTest {

    @Test
    fun statedRetryAfterIsBelieved() {
        assertEquals(5_000L, SongSyncRetry.retryDelayMs(5_000L, attempt = 0))
        assertEquals(5_000L, SongSyncRetry.retryDelayMs(5_000L, attempt = 1))
    }

    @Test
    fun missingRetryAfterDoubles() {
        assertEquals(2_000L, SongSyncRetry.retryDelayMs(null, attempt = 0))
        assertEquals(4_000L, SongSyncRetry.retryDelayMs(null, attempt = 1))
        assertEquals(8_000L, SongSyncRetry.retryDelayMs(null, attempt = 2))
    }

    @Test
    fun zeroOrNegativeRetryAfterFallsBackToBackoff() {
        assertEquals(2_000L, SongSyncRetry.retryDelayMs(0L, attempt = 0))
        assertEquals(4_000L, SongSyncRetry.retryDelayMs(-1L, attempt = 1))
    }

    @Test
    fun aLongStatedDelayIsBelievedRatherThanShortenedToTheBackoffCap() {
        assertEquals(120_000L, SongSyncRetry.retryDelayMs(120_000L, attempt = 0))
        assertEquals(
            SongSyncRetry.MAX_STATED_DELAY_MS,
            SongSyncRetry.retryDelayMs(SongSyncRetry.MAX_STATED_DELAY_MS + 1, attempt = 0),
        )
    }

    @Test
    fun only429IsRetriedAndOnlyTwice() {
        assertTrue(SongSyncRetry.shouldRetry(429, attempt = 0))
        assertTrue(SongSyncRetry.shouldRetry(429, attempt = 1))
        assertFalse(SongSyncRetry.shouldRetry(429, attempt = 2))
        assertFalse(SongSyncRetry.shouldRetry(500, attempt = 0))
        assertFalse(SongSyncRetry.shouldRetry(null, attempt = 0))
    }

    @Test
    fun inlineWaitHasABoundary() {
        assertTrue(SongSyncRetry.canWaitInline(SongSyncRetry.MAX_INLINE_WAIT_MS))
        assertFalse(SongSyncRetry.canWaitInline(SongSyncRetry.MAX_INLINE_WAIT_MS + 1))
    }
}
