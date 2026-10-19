package com.music.bitchord.data

import com.music.bitchord.data.innertube.MusicGateFallback

/**
 * The cover picked for one curated Explore tile, kept across launches.
 *
 * Picking a cover costs one anonymous playlist search, so the answer is stored
 * with the same bounded store as the fallback's other derived facts. Entries
 * expire after [TTL_MS] so a tile can pick up a different first playlist later,
 * and the store's own cap keeps the file small forever.
 */
internal class ExploreCategoryArtworkCache(
    storeKey: String = STORE_KEY,
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val cache = StoredVerdictCache(storeKey, MAX_ENTRIES)

    /** A cover settled less than [ttlMs] ago, or null when there is none. */
    fun get(key: String): String? {
        cache.ensureLoaded(MusicGateFallback.sharedStore())
        val entry = cache.get(key) ?: return null
        return entry.value.takeIf { isFresh(entry.at, clock(), ttlMs) }
    }

    fun put(key: String, url: String) {
        if (key.isBlank() || url.isBlank()) return
        cache.ensureLoaded(MusicGateFallback.sharedStore())
        cache.put(key, url, clock())
    }

    internal fun resetForTest() = cache.resetForTest()

    companion object {
        const val TTL_MS = 24L * 60L * 60L * 1000L

        const val MAX_ENTRIES = 100

        private const val STORE_KEY = "explore_category_artwork_v1"

        /** Whether an entry settled at [atMs] is still inside the window at [nowMs]. */
        fun isFresh(atMs: Long, nowMs: Long, ttlMs: Long = TTL_MS): Boolean =
            nowMs - atMs < ttlMs
    }
}
