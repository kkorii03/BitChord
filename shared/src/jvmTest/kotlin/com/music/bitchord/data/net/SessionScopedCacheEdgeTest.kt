package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cache hits are scoped to the listener who asked. The window is half-open:
 * an entry is fresh strictly before its TTL and stale at the TTL itself.
 */
class SessionScopedCacheEdgeTest {

    private val now = 10_000L

    @Test
    fun `the same session at the exact TTL misses`() {
        assertFalse(sessionScopedCacheHit("u:A", "u:A", now, now + 60_000L, 60_000L))
    }

    @Test
    fun `the same session one millisecond before the TTL hits`() {
        assertTrue(sessionScopedCacheHit("u:A", "u:A", now, now + 59_999L, 60_000L))
    }

    @Test
    fun `a zero or negative TTL never hits`() {
        assertFalse(sessionScopedCacheHit("u:A", "u:A", now, now, 0L))
        assertFalse(sessionScopedCacheHit("u:A", "u:A", now, now, -1L))
    }

    @Test
    fun `a different session misses even at zero age`() {
        assertFalse(sessionScopedCacheHit("u:A", "u:B", now, now, Long.MAX_VALUE))
    }

    @Test
    fun `a missing stored key never hits`() {
        assertFalse(sessionScopedCacheHit(null, "u:A", now, now, Long.MAX_VALUE))
        assertFalse(sessionScopedCacheHit(null, "", now, now, Long.MAX_VALUE))
    }

    @Test
    fun `an entry from a future clock still reads as fresh`() {
        assertTrue(sessionScopedCacheHit("u:A", "u:A", now + 1_000L, now, 60_000L))
    }

    @Test
    fun `an empty key matches only an empty key`() {
        assertTrue(sessionScopedCacheHit("", "", now, now, 1L))
        assertFalse(sessionScopedCacheHit("", "u:A", now, now, 1L))
    }
}
