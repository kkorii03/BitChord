package com.music.bitchord.data

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.HomeSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // ---- N4 / N5: what the real caches store --------------------------------

    private fun <T> withCleanCaches(block: suspend () -> T): T {
        val previous = Innertube.cookie
        YouTubeFallback.resetHomeCachesForTest()
        try {
            return runBlocking { block() }
        } finally {
            YouTubeFallback.resetHomeCachesForTest()
            Innertube.cookie = previous
        }
    }

    private fun feed(title: String) = HomeFeed(
        shelves = listOf(HomeShelf(title = title, items = emptyList())),
        continuation = null,
        source = HomeSource.FALLBACK,
    )

    @Test
    fun `N4 a home built across an account switch is not cached for the new account`() = withCleanCaches {
        Innertube.cookie = "SAPISID=first-test-only"
        val first = YouTubeFallback.homeWith(force = false) {
            // The listener switches account while the slow build is running.
            Innertube.cookie = "SAPISID=second-test-only"
            feed("first account")
        }
        assertEquals("first account", first.shelves.single().title)

        var builds = 0
        val second = YouTubeFallback.homeWith(force = false) {
            builds++
            feed("second account")
        }
        assertEquals(1, builds, "the new account must get its own build")
        assertEquals("second account", second.shelves.single().title)
    }

    @Test
    fun `N4 a home built without a switch is still reused`() = withCleanCaches {
        Innertube.cookie = "SAPISID=stable-test-only"
        YouTubeFallback.homeWith(force = false) { feed("stable") }
        var builds = 0
        val again = YouTubeFallback.homeWith(force = false) { builds++; feed("rebuilt") }
        assertEquals(0, builds)
        assertEquals("stable", again.shelves.single().title)
    }

    @Test
    fun `N5 failed liked lookups are not cached as no likes`() = withCleanCaches {
        Innertube.cookie = "SAPISID=liked-test-only"
        var asks = 0
        assertNull(YouTubeFallback.likedFirstPageWith { asks++; error("timeout") })
        assertEquals(2, asks)

        // Nothing answered, so the next build asks again.
        assertNull(YouTubeFallback.likedFirstPageWith { asks++; JsonObject(emptyMap()) })
        assertEquals(4, asks)

        // An empty answer is a real "no likes" and is cached for the window.
        assertNull(YouTubeFallback.likedFirstPageWith { asks++; JsonObject(emptyMap()) })
        assertEquals(4, asks)
    }

    @Test
    fun `F2a-4 a failed Music channel browse is not cached as no shelves`() = withCleanCaches {
        var asks = 0
        assertTrue(YouTubeFallback.channelShelvesWith { asks++; error("timeout") }.isEmpty())

        // The failure was not stored, so the next caller asks again and gets the shelf.
        val shelf = HomeShelf(title = "channel", items = emptyList())
        assertEquals(listOf(shelf), YouTubeFallback.channelShelvesWith { asks++; listOf(shelf) })
        assertEquals(2, asks)

        // A real answer is kept for the window.
        assertEquals(listOf(shelf), YouTubeFallback.channelShelvesWith { asks++; emptyList() })
        assertEquals(2, asks)
    }
}
