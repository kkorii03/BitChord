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
    fun only429IsRetriedAndOnlyFiveTimes() {
        for (attempt in 0 until 5) assertTrue(SongSyncRetry.shouldRetry(429, attempt))
        assertFalse(SongSyncRetry.shouldRetry(429, attempt = 5))
        assertFalse(SongSyncRetry.shouldRetry(500, attempt = 0))
        assertFalse(SongSyncRetry.shouldRetry(null, attempt = 0))
    }

    /**
     * The limit is 3 requests at once with no `Retry-After`, so a slot opens when
     * someone else's request ends: 5-15 s usually, up to 93 s (11-06, 50 answers).
     * The old 2 s + 4 s gave up inside one such request and left the song without
     * a check for the rest of its playback (phone 10:43, 10:53: no result at all).
     */
    @Test
    fun theRetriesOutlastAnOrdinaryRequestAndEveryWaitIsHeldInline() {
        var total = 0L
        var attempt = 0
        while (SongSyncRetry.shouldRetry(429, attempt)) {
            val wait = SongSyncRetry.retryDelayMs(null, attempt)
            assertTrue(SongSyncRetry.canWaitInline(wait))
            total += wait
            attempt++
        }
        assertEquals(62_000L, total)
        // The hold left by the last 429 is still one another song may wait out, not give up on.
        assertTrue(SongSyncRetry.canWaitInline(SongSyncRetry.retryDelayMs(null, attempt)))
    }

    @Test
    fun inlineWaitHasABoundary() {
        assertTrue(SongSyncRetry.canWaitInline(SongSyncRetry.MAX_INLINE_WAIT_MS))
        assertFalse(SongSyncRetry.canWaitInline(SongSyncRetry.MAX_INLINE_WAIT_MS + 1))
    }
}
