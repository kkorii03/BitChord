package com.music.bitchord.ui

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
