package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.download.SavedSongMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * [KO] LIBRARY-4 (review 11-20): the YouTube-to-file alignment a verified download
 * carries in its own record, for when the verdict store no longer holds the pair.
 *
 * The record keeps the offset and rate the pair was proved with, and nothing read
 * them: once the pair left [com.music.bitchord.playback.smart.HqVerificationStore]
 * (a source address edit, a source-list import, the 2000-pair eviction) every
 * YouTube-clock answer for the saved file was dropped on every later play. The
 * bytes on disk did not change, so the alignment measured for them still holds.
 */
internal object KoRecordedClock {

    private val logged = ConcurrentHashMap.newKeySet<String>()

    /**
     * The (offset, rate) [meta] recorded for the pair [sourceConfigId]/[trackId],
     * or null when the record is not that pair's file, was proved the wrong
     * recording, or carries no usable alignment.
     */
    fun alignment(meta: SavedSongMetadata?, sourceConfigId: String, trackId: String): Pair<Long, Double>? {
        if (meta == null || meta.wrongRecording) return null
        if (meta.hqSourceConfigId != sourceConfigId || meta.hqTrackId != trackId) return null
        val offset = meta.hqOffsetMs ?: return null
        val rate = meta.hqRate ?: return null
        if (!rate.isFinite() || rate <= 0.0) return null
        return offset to rate
    }

    /** One line per song and pair, so the phone log shows the fallback without repeating it. */
    fun noteUsed(videoId: String, sourceConfigId: String, trackId: String, offsetMs: Long, rate: Double) {
        if (!logged.add("$videoId\u0001$sourceConfigId\u0001$trackId")) return
        TrackLog.i(
            "LyricsTiming",
            "lyrics-clock: $videoId pair alt:$sourceConfigId/$trackId not in the verdict store; " +
                "using the download record's offset=${offsetMs}ms rate=$rate",
            videoId,
        )
    }
}
