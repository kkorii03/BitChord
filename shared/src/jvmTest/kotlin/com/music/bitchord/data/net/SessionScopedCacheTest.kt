package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionScopedCacheTest {

    private val ttl = 60_000L
    private val now = 1_000_000L

    @Test
    fun `the same session inside the window hits`() {
        assertTrue(sessionScopedCacheHit("u:A", "u:A", now, now, ttl))
    }

    @Test
    fun `another session misses even while fresh`() {
        assertFalse(sessionScopedCacheHit("u:A", "u:B", now, now, ttl))
    }

    @Test
    fun `an entry with no session key never hits`() {
        assertFalse(sessionScopedCacheHit(null, "u:A", now, now, ttl))
    }

    @Test
    fun `an entry past the window misses`() {
        assertFalse(sessionScopedCacheHit("u:A", "u:A", now, now + ttl, ttl))
    }

    @Test
    fun `an entry just inside the window hits`() {
        assertTrue(sessionScopedCacheHit("u:A", "u:A", now, now + ttl - 1, ttl))
    }
}
