package com.music.bitchord.ko

import com.music.bitchord.data.model.Song

/**
 * [KO] RG1-3: which queue entries were converted from which video.
 *
 * A music-only conversion keeps the video row's queue entry id and is not
 * video-origin, so its only other mark is the process-wide "switch back to
 * this video" pairing keyed by the audio id. The first refutation of a pair
 * forgets that pairing (so the same song reached as its own row is left
 * alone), which also hid every other row converted from the same video, and
 * the wrong song kept playing there. The entry id belongs to the row itself,
 * so a row the listener picked as its own song never carries it.
 *
 * Memory only and bounded; a lost entry falls back to the old marks.
 */
object KoConvertedRows {

    private const val MAX_ENTRIES = 512

    private val byEntry = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) =
            size > MAX_ENTRIES
    }

    /** [video]'s queue entry now plays a converted row. */
    @Synchronized
    fun note(video: Song) {
        val entry = video.queueEntryId ?: return
        byEntry[entry] = video.videoId
    }

    /** [KO] SW8: a restored row in queue entry [entryId] was converted from [videoId]. */
    @Synchronized
    fun noteEntry(entryId: String?, videoId: String) {
        if (entryId == null) return
        byEntry[entryId] = videoId
    }

    /** The video the row in queue entry [entryId] was converted from, if known. */
    @Synchronized
    fun videoOf(entryId: String?): String? = entryId?.let { byEntry[it] }

    /** Tests reset the singleton between cases. */
    @Synchronized
    fun clearForTest() {
        byEntry.clear()
    }
}
