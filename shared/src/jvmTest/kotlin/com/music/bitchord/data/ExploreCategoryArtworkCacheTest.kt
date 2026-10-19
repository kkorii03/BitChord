package com.music.bitchord.data

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A curated tile's settled cover is reused for exactly 24 hours and then
 * treated as missing, so the next visible tile can pick a fresh playlist.
 */
class ExploreCategoryArtworkCacheTest {

    private var now = 1_000_000L

    private val cache = ExploreCategoryArtworkCache(
        storeKey = "explore_artwork_cache_test",
        clock = { now },
    )

    @BeforeTest
    fun setUp() {
        now = 1_000_000L
        cache.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        cache.resetForTest()
    }

    @Test
    fun `a fresh cover is returned`() {
        cache.put("ytq:game", "https://i.ytimg.com/vi/abc/hq720.jpg")
        assertEquals(
            "https://i.ytimg.com/vi/abc/hq720.jpg",
            cache.get("ytq:game"),
        )
    }

    @Test
    fun `a cover expires at the 24 hour boundary`() {
        val url = "https://i.ytimg.com/vi/abc/hq720.jpg"
        cache.put("ytq:game", url)

        now += ExploreCategoryArtworkCache.TTL_MS - 1L
        assertEquals(url, cache.get("ytq:game"))

        now += 1L
        assertNull(cache.get("ytq:game"))
    }

    @Test
    fun `the expiry rule treats the boundary as expired`() {
        assertTrue(ExploreCategoryArtworkCache.isFresh(1_000L, 1_000L + 10L, ttlMs = 11L))
        assertFalse(ExploreCategoryArtworkCache.isFresh(1_000L, 1_011L, ttlMs = 11L))
    }
}
