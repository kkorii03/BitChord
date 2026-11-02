package com.music.bitchord.ko

/**
 * [KO] crossfade-lyrics
 *
 * A fixed-size lyric cache for a session-long process. A track heard hours ago
 * is cheap to re-read when it comes back; keeping every lyric page of the
 * session is not. `asked` lives beside the value so an eviction never leaves a
 * stale "already asked" behind for a track whose spans are gone.
 */
internal class KoCrossfadeLyricCache<V>(private val maxEntries: Int = MAX_ENTRIES) {
    private class Entry<V>(var value: V? = null, var asked: Boolean = false)

    private val entries = object : LinkedHashMap<String, Entry<V>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>): Boolean =
            size > maxEntries
    }

    /** True the first time [id] is asked, false while that ask is still remembered. */
    @Synchronized
    fun markAsked(id: String): Boolean {
        val existing = entries[id]
        if (existing?.asked == true) return false
        val entry = existing ?: Entry<V>().also { entries[id] = it }
        entry.asked = true
        return true
    }

    @Synchronized
    operator fun get(id: String): V? = entries[id]?.value

    @Synchronized
    fun put(id: String, value: V) {
        val entry = entries[id] ?: Entry<V>().also { entries[id] = it }
        entry.value = value
        entry.asked = true
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        const val MAX_ENTRIES = 16
    }
}
