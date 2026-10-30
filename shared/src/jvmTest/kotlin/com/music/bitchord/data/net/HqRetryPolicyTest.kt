package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How long a 429/503 may hold a loader thread, and when the answer is instead
 * "switch routes now". The arithmetic is pure so it runs without a socket.
 */
class HqRetryPolicyTest {

    @Test
    fun `retry-after is read as seconds`() {
        assertEquals(5_000L, HqRetryPolicy.retryAfterMs("5", nowMs = 1_000L))
        assertEquals(0L, HqRetryPolicy.retryAfterMs("0", nowMs = 1_000L))
    }

    @Test
    fun `retry-after is read as an http date`() {
        val now = 1_767_225_600_000L // Thu, 01 Jan 2026 00:00:00 GMT
        assertEquals(
            8_000L,
            HqRetryPolicy.retryAfterMs("Thu, 01 Jan 2026 00:00:08 GMT", nowMs = now),
        )
    }

    @Test
    fun `a malformed retry-after falls through to the backoff`() {
        assertNull(HqRetryPolicy.retryAfterMs("soon", nowMs = 0L))
        assertEquals(1_000L, HqRetryPolicy.decide(null, attempt = 0, nowMs = 0L).waitMs)
        assertEquals(2_000L, HqRetryPolicy.decide(null, attempt = 1, nowMs = 0L).waitMs)
        assertEquals(4_000L, HqRetryPolicy.decide(null, attempt = 2, nowMs = 0L).waitMs)
        assertFalse(HqRetryPolicy.decide(null, attempt = 3, nowMs = 0L).retry)
    }

    @Test
    fun `consecutive refusals double the rest up to ten minutes`() {
        assertEquals(60_000L, HqRetryPolicy.escalatingRestMs(1))
        assertEquals(120_000L, HqRetryPolicy.escalatingRestMs(2))
        assertEquals(240_000L, HqRetryPolicy.escalatingRestMs(3))
        assertEquals(480_000L, HqRetryPolicy.escalatingRestMs(4))
        assertEquals(600_000L, HqRetryPolicy.escalatingRestMs(5))
        assertEquals(600_000L, HqRetryPolicy.escalatingRestMs(50))
    }

    @Test
    fun `a server retry-after longer than the escalation is kept`() {
        assertEquals(3_600_000L, HqRetryPolicy.escalatingRestMs(1, statedRestMs = 3_600_000L))
        assertEquals(120_000L, HqRetryPolicy.escalatingRestMs(2, statedRestMs = 5_000L))
    }

    @Test
    fun `a short retry-after is waited out`() {
        val decision = HqRetryPolicy.decide("5", attempt = 0, nowMs = 0L)
        assertTrue(decision.retry)
        assertFalse(decision.retryAfterTooLong)
        assertEquals(5_000L, decision.waitMs)
    }

    @Test
    fun `the cap itself is still waited out`() {
        val decision = HqRetryPolicy.decide(
            (HqRetryPolicy.MAX_WAIT_MS / 1000L).toString(),
            attempt = 0,
            nowMs = 0L,
        )
        assertTrue(decision.retry)
        assertEquals(HqRetryPolicy.MAX_WAIT_MS, decision.waitMs)
    }

    @Test
    fun `a retry-after over the cap fails at once and rests the host for the stated time`() {
        val decision = HqRetryPolicy.decide("3600", attempt = 0, nowMs = 0L)
        assertFalse(decision.retry)
        assertTrue(decision.retryAfterTooLong)
        assertEquals(0L, decision.waitMs)
        assertEquals(3_600_000L, decision.restMs)
    }

    @Test
    fun `a sleeping gateway is woken once and never retried in the same call`() {
        for (code in listOf(502, 503, 504)) {
            val decision = HqRetryPolicy.decideAddon(code, null, attempt = 0, nowMs = 0L)
            assertFalse(decision.retry, "HTTP $code must not be asked again")
            assertEquals(0L, decision.waitMs)
            assertEquals(HqRetryPolicy.REST_MS, decision.restMs)
        }
    }

    @Test
    fun `a rate limit on an awake server still gets the first retry`() {
        val decision = HqRetryPolicy.decideAddon(429, null, attempt = 0, nowMs = 0L)
        assertTrue(decision.retry)
        assertEquals(1_000L, decision.waitMs)
    }
}
