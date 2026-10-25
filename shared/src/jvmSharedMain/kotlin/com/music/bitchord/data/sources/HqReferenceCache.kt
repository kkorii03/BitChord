package com.music.bitchord.data.sources

import com.music.bitchord.data.lyrics.ChromaAlignment

/**
 * The YouTube reference profile for high-quality verification, kept in memory
 * for the next candidate of the same song.
 *
 * A verification decodes ninety seconds of the reference and ninety of the
 * candidate. The candidate differs per source, but the reference is the same
 * YouTube song version for every candidate of that track — and the read-ahead
 * pass and the upgrade path can each ask about a different candidate. Without
 * this, the same reference was decoded once per candidate even though nothing
 * about it had changed.
 *
 * Bounded by [MAX_ENTRIES] and access-ordered, so a long session cannot turn
 * this into a leak. Only a profile covering most of the compared window is
 * stored: a partial head is left to be re-read later rather than frozen in
 * place of the complete one that may still arrive.
 */
class HqReferenceCache(private val limit: Int = MAX_ENTRIES) {

    private val entries =
        object : LinkedHashMap<String, ChromaAlignment.Features>(limit, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ChromaAlignment.Features>,
            ): Boolean = size > limit
        }

    @Synchronized
    fun get(youtubeId: String): ChromaAlignment.Features? = entries[youtubeId]

    @Synchronized
    fun put(youtubeId: String, features: ChromaAlignment.Features) {
        entries[youtubeId] = features
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        /** A handful of tracks covers the current one and its read-ahead. */
        const val MAX_ENTRIES = 8

        /** Below this share of the comparison window the profile is not worth keeping. */
        const val COMPLETE_FRACTION = 0.9

        fun completeEnough(features: ChromaAlignment.Features, seconds: Double): Boolean =
            seconds > 0.0 && features.durationSeconds >= seconds * COMPLETE_FRACTION
    }
}
