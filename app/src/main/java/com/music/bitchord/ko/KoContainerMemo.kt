package com.music.bitchord.ko

import java.util.concurrent.ConcurrentHashMap

/**
 * [KO] hq-lossless
 *
 * Remembers the container read from a complete cache entry, so choosing the
 * rendition for a play does not open the same file on every key resolution.
 *
 * The answer is tied to the entry's content length: removing and refilling a
 * key changes that length, and a different length means the old container no
 * longer describes the bytes. A failed probe is not remembered, so a transient
 * read error does not hide a lossless copy for the rest of the session.
 */
internal class KoContainerMemo(
    private val probe: (String) -> Pair<String, String>?,
    private val contentLengthOf: (String) -> Long,
) {
    private class Entry(val contentLength: Long, val container: Pair<String, String>)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun containerOf(key: String): Pair<String, String>? {
        val contentLength = contentLengthOf(key)
        if (contentLength <= 0L) return null
        entries[key]?.let { entry ->
            if (entry.contentLength == contentLength) return entry.container
        }
        val container = probe(key) ?: return null
        if (entries.size >= MAX_ENTRIES) entries.clear()
        entries[key] = Entry(contentLength, container)
        return container
    }

    /** Forgets every rendition of [mediaId] after its keys are dropped or replaced. */
    fun forget(mediaId: String) {
        entries.keys.forEach { key ->
            if (key == mediaId || key.startsWith("$mediaId#")) entries.remove(key)
        }
    }

    private companion object {
        /** A track contributes one or two entries; the bound only stops a long session growing forever. */
        private const val MAX_ENTRIES = 512
    }
}
