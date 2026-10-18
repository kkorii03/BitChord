package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gated Home's cache decision: a stable signed-in session inside the
 * ten-minute window is reused, while a pull-to-refresh, a different session
 * or an expired entry forces a rebuild.
 */
class YouTubeFallbackHomeCacheTest {

    private val ttl = 10L * 60L * 1000L
    private val now = 1_000_000L

    private fun usable(
        force: Boolean = false,
        cachedKey: String? = "session",
        sessionKey: String = "session",
        cachedAtMs: Long = now - 1_000L,
    ): Boolean = YouTubeFallbackFeed.shouldUseCachedHome(
        force = force,
        cachedKey = cachedKey,
        sessionKey = sessionKey,
        cachedAtMs = cachedAtMs,
        nowMs = now,
        maxAgeMs = ttl,
    )

    @Test
    fun `a fresh feed from the same session is reused`() {
        assertTrue(usable())
    }

    @Test
    fun `force bypasses even a fresh feed`() {
        assertFalse(usable(force = true, cachedAtMs = now))
    }

    @Test
    fun `an entry older than the window is rebuilt`() {
        assertFalse(usable(cachedAtMs = now - ttl))
        assertFalse(usable(cachedAtMs = now - ttl - 1L))
        assertTrue(usable(cachedAtMs = now - ttl + 1L))
    }

    @Test
    fun `a different or missing session key is rebuilt`() {
        assertFalse(usable(sessionKey = "other"))
        assertFalse(usable(cachedKey = null))
    }
}
