package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

/**
 * Starts a station the moment its first answer arrives and fills the rest in
 * behind it.
 *
 * Only [FIRST_PLAY_PREFIX] tracks of that first answer are committed before the
 * player is touched, so a long first playlist does not delay the first song
 * while it is mapped. The rest of that answer, and then the other answers, are
 * appended once they are ready. A newer request that arrived meanwhile wins:
 * [isCurrent] is checked before every commit, so no stale tail lands on a queue
 * the listener has since replaced.
 *
 * Returns whether playback was started.
 */
internal suspend fun playStationIncrementally(
    load: suspend (onFirstBatch: suspend (List<Song>) -> Unit) -> Result<List<Song>>,
    isCurrent: () -> Boolean,
    begin: suspend () -> Unit,
    playFirst: suspend (List<Song>) -> Unit,
    append: suspend (List<Song>) -> Unit,
    onEmpty: () -> Unit,
): Boolean {
    var firstIds: Set<String> = emptySet()
    var started = false
    val songs = load { first ->
        if (!isCurrent()) return@load
        val prefix = first.take(FIRST_PLAY_PREFIX)
        if (prefix.isEmpty()) return@load
        begin()
        playFirst(prefix)
        firstIds = first.mapTo(HashSet()) { it.videoId }
        started = true
        val remainder = first.drop(prefix.size)
        if (remainder.isNotEmpty() && isCurrent()) append(remainder)
    }.getOrNull().orEmpty()
    if (!isCurrent()) return started
    if (songs.isEmpty()) {
        if (!started) onEmpty()
        return started
    }
    if (!started) {
        begin()
        playFirst(songs)
    } else {
        val rest = songs.filterNot { it.videoId in firstIds }
        if (rest.isNotEmpty()) append(rest)
    }
    return true
}

/** One track is enough to start; the answer it came from is appended next. */
internal const val FIRST_PLAY_PREFIX = 1
