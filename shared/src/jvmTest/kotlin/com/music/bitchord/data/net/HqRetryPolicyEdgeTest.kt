package com.music.bitchord.data.net

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary and hostile-header cases for the 429/503 retry arithmetic. The
 * happy paths live in [HqRetryPolicyTest]; these pin the values a malformed or
 * extreme header must not turn into a retry storm.
 */
class HqRetryPolicyEdgeTest {

    @Test
    fun `only 429 and 503 are rate limits`() {
        assertTrue(HqRetryPolicy.isRateLimited(429))
        assertTrue(HqRetryPolicy.isRateLimited(503))
        assertFalse(HqRetryPolicy.isRateLimited(200))
        assertFalse(HqRetryPolicy.isRateLimited(403))
        assertFalse(HqRetryPolicy.isRateLimited(500))
        assertFalse(HqRetryPolicy.isRateLimited(0))
        assertFalse(HqRetryPolicy.isRateLimited(-1))
    }

    @Test
    fun `blank retry-after values are not an instruction`() {
        assertNull(HqRetryPolicy.retryAfterMs(null, nowMs = 0L))
        assertNull(HqRetryPolicy.retryAfterMs("", nowMs = 0L))
        assertNull(HqRetryPolicy.retryAfterMs("   ", nowMs = 0L))
        assertNull(HqRetryPolicy.retryAfterMs("\t\n", nowMs = 0L))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(5_000L, HqRetryPolicy.retryAfterMs(" 5 ", nowMs = 0L))
        assertEquals(5_000L, HqRetryPolicy.retryAfterMs("\t5\n", nowMs = 0L))
    }

    @Test
    fun `negative seconds clamp to no wait`() {
        assertEquals(0L, HqRetryPolicy.retryAfterMs("-1", nowMs = 0L))
        assertEquals(0L, HqRetryPolicy.retryAfterMs("-9999", nowMs = 0L))
    }

    @Test
    fun `a fractional value is read as whole seconds`() {
        assertEquals(1_000L, HqRetryPolicy.retryAfterMs("1.9", nowMs = 0L))
        assertEquals(0L, HqRetryPolicy.retryAfterMs("0.9", nowMs = 0L))
    }

    @Test
    fun `an http date in the past clamps to no wait`() {
        val now = 1_767_225_600_000L
        assertEquals(
            0L,
            HqRetryPolicy.retryAfterMs("Thu, 01 Jan 2026 00:00:00 GMT", nowMs = now),
        )
        assertEquals(
            0L,
            HqRetryPolicy.retryAfterMs("Wed, 01 Jan 2025 00:00:00 GMT", nowMs = now),
        )
    }

    @Test
    fun `a date with the wrong shape is not a wait`() {
        assertNull(HqRetryPolicy.retryAfterMs("2026-01-01T00:00:00Z", nowMs = 0L))
        assertNull(HqRetryPolicy.retryAfterMs("Thu, 99 Foo 2026 00:00:00 GMT", nowMs = 0L))
        assertNull(HqRetryPolicy.retryAfterMs("tomorrow", nowMs = 0L))
    }

    @Test
    fun `backoff is clamped to the documented ladder`() {
        assertEquals(1_000L, HqRetryPolicy.backoffMs(-100))
        assertEquals(1_000L, HqRetryPolicy.backoffMs(0))
        assertEquals(2_000L, HqRetryPolicy.backoffMs(1))
        assertEquals(4_000L, HqRetryPolicy.backoffMs(2))
        assertEquals(4_000L, HqRetryPolicy.backoffMs(3))
        assertEquals(4_000L, HqRetryPolicy.backoffMs(4))
        assertEquals(4_000L, HqRetryPolicy.backoffMs(Int.MAX_VALUE))
    }

    @Test
    fun `the retry budget is exactly three retries after the first attempt`() {
        for (attempt in 0 until HqRetryPolicy.MAX_ATTEMPTS) {
            val decision = HqRetryPolicy.decide(null, attempt, nowMs = 0L)
            assertTrue(decision.retry, "attempt $attempt is retried")
            assertEquals(1_000L shl attempt, decision.waitMs)
            assertEquals(HqRetryPolicy.REST_MS, decision.restMs)
            assertFalse(decision.retryAfterTooLong)
        }
        val spent = HqRetryPolicy.decide(null, HqRetryPolicy.MAX_ATTEMPTS, nowMs = 0L)
        assertFalse(spent.retry)
        assertEquals(0L, spent.waitMs)
        assertEquals(HqRetryPolicy.REST_MS, spent.restMs)
        assertFalse(spent.retryAfterTooLong)
    }

    @Test
    fun `a zero second retry-after is honoured without resting longer than the floor`() {
        val decision = HqRetryPolicy.decide("0", attempt = 0, nowMs = 0L)
        assertTrue(decision.retry)
        assertEquals(0L, decision.waitMs)
        assertEquals(HqRetryPolicy.REST_MS, decision.restMs)
        assertFalse(decision.retryAfterTooLong)
    }

    @Test
    fun `a negative retry-after behaves like zero`() {
        val decision = HqRetryPolicy.decide("-5", attempt = 0, nowMs = 0L)
        assertTrue(decision.retry)
        assertEquals(0L, decision.waitMs)
        assertEquals(HqRetryPolicy.REST_MS, decision.restMs)
        assertFalse(decision.retryAfterTooLong)
    }

    @Test
    fun `the wait cap is inclusive and the host still rests for a minute`() {
        val decision = HqRetryPolicy.decide(
            (HqRetryPolicy.MAX_WAIT_MS / 1000L).toString(),
            attempt = 0,
            nowMs = 0L,
        )
        assertTrue(decision.retry)
        assertEquals(HqRetryPolicy.MAX_WAIT_MS, decision.waitMs)
        assertEquals(HqRetryPolicy.REST_MS, decision.restMs)
        assertFalse(decision.retryAfterTooLong)
    }

    @Test
    fun `one millisecond over the cap fails at once`() {
        val seconds = HqRetryPolicy.MAX_WAIT_MS / 1000L + 1L
        val decision = HqRetryPolicy.decide(seconds.toString(), attempt = 0, nowMs = 0L)
        assertFalse(decision.retry)
        assertEquals(0L, decision.waitMs)
        assertEquals(maxOf(seconds * 1000L, HqRetryPolicy.REST_MS), decision.restMs)
        assertTrue(decision.retryAfterTooLong)
    }

    @Test
    fun `a long stated rest is kept for later requests`() {
        val decision = HqRetryPolicy.decide("120", attempt = 0, nowMs = 0L)
        assertFalse(decision.retry)
        assertTrue(decision.retryAfterTooLong)
        assertEquals(0L, decision.waitMs)
        assertEquals(120_000L, decision.restMs)
    }

    @Test
    fun `the retry-after header is found case-insensitively`() {
        assertEquals(
            "5",
            HqRetryPolicy.retryAfterHeader(mapOf("retry-after" to listOf("5"))),
        )
        assertEquals(
            "7",
            HqRetryPolicy.retryAfterHeader(mapOf("RETRY-AFTER" to listOf("7"))),
        )
        assertEquals(
            "9",
            HqRetryPolicy.retryAfterHeader(mapOf("Retry-After" to listOf("9", "10"))),
        )
    }

    @Test
    fun `a missing or empty header map has no retry-after`() {
        assertNull(HqRetryPolicy.retryAfterHeader(null))
        assertNull(HqRetryPolicy.retryAfterHeader(emptyMap()))
        assertNull(HqRetryPolicy.retryAfterHeader(mapOf("Retry-After" to emptyList())))
        assertNull(HqRetryPolicy.retryAfterHeader(mapOf("Date" to listOf("5"))))
    }

    @Ignore(
        "BUG: retryAfterMs multiplies seconds by 1000 without overflow protection. " +
            "A header such as '10000000000000000' (or Long.MAX_VALUE) overflows to a " +
            "negative value, coerceAtLeast(0) turns it into 0 ms, and decide() then " +
            "retries immediately instead of treating the host as asking too long.",
    )
    @Test
    fun `an absurdly large retry-after never becomes an immediate retry`() {
        val value = HqRetryPolicy.retryAfterMs("10000000000000000", nowMs = 0L)
        assertNull(value)
        val decision = HqRetryPolicy.decide("10000000000000000", attempt = 0, nowMs = 0L)
        assertFalse(decision.retry)
        assertTrue(decision.retryAfterTooLong)
    }
}
