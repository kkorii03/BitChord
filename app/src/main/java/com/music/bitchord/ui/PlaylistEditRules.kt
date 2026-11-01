package com.music.bitchord.ui

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song

/**
 * The part of a playlist import that arrives after the first request.
 *
 * [add] is the repository call for one batch; the return value is how many
 * songs did not make it. The playlist itself is kept either way — the caller
 * reports the shortfall instead of pretending the import was whole.
 */
internal suspend fun addPlaylistRemainderChunks(
    videoIds: List<String>,
    add: suspend (List<String>) -> Result<*>,
): Int {
    var failedSongs = 0
    videoIds.drop(50).chunked(50).forEach { chunk ->
        if (add(chunk).isFailure) failedSongs += chunk.size
    }
    return failedSongs
}

/**
 * The entry a playlist removal names, or null when this row cannot carry one.
 *
 * A row with no set-video-id is one YouTube never placed in this playlist; the
 * caller gets [onFailure] so the tap does not disappear without a word.
 */
internal fun playlistRemovalEntry(
    song: Song,
    signedIn: Boolean,
    onFailure: () -> Unit,
): Pair<String, String>? {
    if (!signedIn) return null
    val setVideoId = song.setVideoId ?: run {
        TrackLog.w(
            "BitChord",
            "playlist remove skipped: no setVideoId for ${song.videoId}",
        )
        onFailure()
        return null
    }
    return setVideoId to song.videoId
}
