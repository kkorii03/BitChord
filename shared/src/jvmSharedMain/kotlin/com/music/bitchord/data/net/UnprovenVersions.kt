package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song

/**
 * Song versions adopted on credit and runtime alone, with the row each replaced.
 *
 * The before-audio lookup has a second and a half, so a title filed under
 * another script's name cannot be compared by sound there. The answer plays
 * first and its first sound starts the comparison; this is what the comparison
 * needs to know which row to compare it with, and to put back when the two turn
 * out not to be one song. Kept for the run and bounded: a long session adopts
 * only a handful of these.
 */
object UnprovenVersions {

    private const val REMEMBERED = 32

    private val sources = object : LinkedHashMap<String, Song>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Song>) = size > REMEMBERED
    }

    fun note(source: Song, adoptedId: String) {
        synchronized(sources) { sources[adoptedId] = source }
    }

    /** The row [adoptedId] replaced, while the sound has not proved the two are one song. */
    fun sourceOf(adoptedId: String): Song? = synchronized(sources) { sources[adoptedId] }

    /** The sound agreed, so there is nothing left to check. */
    fun settle(adoptedId: String) {
        synchronized(sources) { sources.remove(adoptedId) }
    }
}
