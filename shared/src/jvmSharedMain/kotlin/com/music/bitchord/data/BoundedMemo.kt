package com.music.bitchord.data

/**
 * A small, thread-safe, least-recently-used memo for pure calculations.
 *
 * The same question is asked by several parts of one playback: a version
 * search, a lyrics lookup, a cover read and a catalogue match all normalise the
 * same title and credit line. None of those answers can change while the input
 * is unchanged, so the work is done once and the answer is reused.
 *
 * Bounded so a long session cannot turn a cache into a leak. The value is
 * computed while the lock is held: the calculations kept here are short
 * (regex and string work), and letting two callers race the same input would
 * put back the duplicate work this exists to remove.
 */
class BoundedMemo<K : Any, V : Any>(private val limit: Int) {

    init {
        require(limit > 0) { "limit must be positive" }
    }

    private val entries = object : LinkedHashMap<K, V>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > limit
    }

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun getOrPut(key: K, value: () -> V): V {
        entries[key]?.let { return it }
        val computed = value()
        entries[key] = computed
        return computed
    }

    /**
     * Stores [value] for [key], replacing an earlier answer.
     *
     * A caller that initially failed and later succeeded must be able to
     * replace the remembered failure; [getOrPut] deliberately never overwrites
     * an existing entry.
     */
    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
    }

    /** Drop one answer so the next [get] or [getOrPut] reads it again. */
    @Synchronized
    fun remove(key: K) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
