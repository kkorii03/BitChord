package com.music.bitchord.data.net

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.ko.KoConvertedRows

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

    /**
     * [KO] SW8: the video a saved queue row must still be checked against
     * after a restart, or null. The map above is memory only while the
     * converted row survives in the saved queue, so the row carries its own
     * source. Only a row the first-sound check would act on carries it (the
     * same [SongVersionPolicy.standsInForVideo] test), so the same song
     * reached as its own row never does.
     */
    fun carriedBy(row: Song): Song? {
        val source = sourceOf(row.videoId) ?: return null
        val standsIn = SongVersionPolicy.standsInForVideo(
            rowIsVideoOrigin = row.isVideoOrigin,
            rememberedVideoId = YtMusicRepository.cachedVideoVersion(row.videoId)?.videoId,
            sourceVideoId = source.videoId,
            entryVideoId = KoConvertedRows.videoOf(row.queueEntryId),
        )
        return source.takeIf { standsIn }
    }

    /**
     * [KO] SW8: a row restored from the saved queue with the [source] it
     * carried is checked on its first sound again. Its queue entry is marked
     * as converted from that video, the mark a music-only row (not
     * video-origin) needs; entry ids are random, so the mark cannot match
     * another row.
     */
    fun restore(row: Song, source: Song) {
        note(source, row.videoId)
        KoConvertedRows.noteEntry(row.queueEntryId, source.videoId)
    }
}
