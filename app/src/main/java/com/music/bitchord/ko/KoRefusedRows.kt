package com.music.bitchord.ko

import java.util.concurrent.ConcurrentHashMap

/**
 * [KO] refused-rows
 *
 * The rows a source offered and the stream probe refused because the file was
 * not what the row claimed — a FLAC header over lossy bytes, a runtime that
 * does not match the row's own listing.
 *
 * Kept by **row**, never by source. A catalogue serves one recording as
 * several rows, and routinely files a broken copy next to a good one; treating
 * that as the whole source being down is what ended a walk at YouTube with the
 * other rows of that source never asked (phone 11-04: qobuz refused for its
 * runtime, jsdelivr/Aurix refused for a lossy file under a FLAC claim, and
 * neither was asked again).
 *
 * A genuine failure — a 403, a timeout, a dead transport — is still the
 * source's, and is counted by [KoSourceCooldown]; nothing is recorded here for
 * it. This is only the "wrong file, try the next row" answer.
 */
internal object KoRefusedRows {

    private class Entry(val at: Long, val rows: MutableSet<String> = mutableSetOf())

    private val bySource = ConcurrentHashMap<String, Entry>()

    /** Remembers that [trackId] from [sourceConfigId] was refused for being the wrong file. */
    fun note(sourceConfigId: String, trackId: String?, now: Long = System.currentTimeMillis()) {
        val row = trackId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (sourceConfigId.isBlank()) return
        val entry = synchronized(bySource) {
            if (bySource.size >= MAX_SOURCES) bySource.clear()
            bySource[sourceConfigId]?.takeIf { now - it.at <= TTL_MS }
                ?: Entry(now).also { bySource[sourceConfigId] = it }
        }
        synchronized(entry) { entry.rows += row }
    }

    /** Whether this row already answered with the wrong file. */
    fun refused(
        sourceConfigId: String,
        trackId: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val row = trackId ?: return false
        val entry = bySource[sourceConfigId] ?: return false
        if (now - entry.at > TTL_MS) {
            bySource.remove(sourceConfigId, entry)
            return false
        }
        return synchronized(entry) { row in entry.rows }
    }

    internal fun resetForTest() {
        bySource.clear()
    }

    /** Long enough to cover the walk in hand and the second look that follows it. */
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_SOURCES = 64
}
