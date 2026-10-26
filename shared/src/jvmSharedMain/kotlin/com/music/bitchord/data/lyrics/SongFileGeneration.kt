package com.music.bitchord.data.lyrics

import java.util.concurrent.ConcurrentHashMap

/**
 * Which version of a song's audio a sync check was measured against.
 *
 * A download can replace a file under the same video id and path — an
 * automatic high-quality upgrade, or a re-download — while a song check is
 * still listening to the old bytes. The check must not write its answer back
 * after the replacement: the answer belongs to the old recording. Each
 * replacement bumps the song's generation; a check carries the generation it
 * started with and discards its result when it no longer matches.
 *
 * Pure on purpose, so the discard rule is tested without Android.
 */
class SongFileGeneration {

    private val generations = ConcurrentHashMap<String, Long>()

    /** The generation a check starting now must carry. */
    fun current(videoId: String): Long = generations[videoId] ?: 0L

    /** Marks [videoId]'s file as replaced; returns the new generation. */
    fun bump(videoId: String): Long =
        generations.compute(videoId) { _, old -> (old ?: 0L) + 1L } ?: 1L

    /** Whether a result measured under [captured] still belongs to the current file. */
    fun isCurrent(videoId: String, captured: Long): Boolean = current(videoId) == captured
}
