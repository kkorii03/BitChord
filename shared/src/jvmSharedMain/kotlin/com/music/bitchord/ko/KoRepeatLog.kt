package com.music.bitchord.ko

/**
 * Drops a log line that repeats word for word for the same key within a
 * short window. LOG-1117 G7: `song version: X kept (none)` was written twice
 * about 3 s apart for every upcoming row, because the row is checked when it
 * becomes next and again when it is prepared. Only the line is dropped; the
 * caller's work is unchanged.
 *
 * The window is fixed from the last line actually written, so a repeat that
 * never stops still shows once a window, and a later replay of the same row
 * logs again: a per-round log dump keeps its `kept (none)` line.
 */
object KoRepeatLog {

    const val WINDOW_MS = 60_000L
    private const val MAX_KEYS = 256

    private class Written(val line: String, val atMs: Long)

    private val written = object : LinkedHashMap<String, Written>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Written>) =
            size > MAX_KEYS
    }

    /** True when [line] should be written now for [key]; false for a repeat inside the window. */
    fun shouldLog(key: String, line: String, nowMs: Long = System.nanoTime() / 1_000_000L): Boolean =
        synchronized(written) {
            val last = written[key]
            if (last != null && last.line == line && nowMs - last.atMs in 0 until WINDOW_MS) {
                false
            } else {
                written[key] = Written(line, nowMs)
                true
            }
        }
}
