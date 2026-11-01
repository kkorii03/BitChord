package com.music.bitchord.ko.ui

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.net.TYPEAHEAD_CACHE_ENTRIES

/**
 * Answers already fetched for an input, so typing the same word again —
 * or backing up over a letter and retyping it — costs no request. Both
 * pipelines keep their own because one answers text and the other rows.
 * Bounded like every other cache here: the Search tab is long-lived.
 */
internal class KoTypeaheadCache {
    private val suggestionCache =
        object : LinkedHashMap<String, List<String>>(TYPEAHEAD_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) =
                size > TYPEAHEAD_CACHE_ENTRIES
        }

    private val mediaCache =
        object : LinkedHashMap<String, List<SearchResult>>(TYPEAHEAD_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchResult>>) =
                size > TYPEAHEAD_CACHE_ENTRIES
        }

    fun suggestions(key: String): List<String>? = synchronized(suggestionCache) {
        suggestionCache[key]
    }

    fun putSuggestions(key: String, rows: List<String>) = synchronized(suggestionCache) {
        suggestionCache[key] = rows
    }

    fun media(key: String): List<SearchResult>? = synchronized(mediaCache) {
        mediaCache[key]
    }

    fun putMedia(key: String, rows: List<SearchResult>) = synchronized(mediaCache) {
        mediaCache[key] = rows
    }

    fun clear() {
        synchronized(suggestionCache) { suggestionCache.clear() }
        synchronized(mediaCache) { mediaCache.clear() }
    }
}
