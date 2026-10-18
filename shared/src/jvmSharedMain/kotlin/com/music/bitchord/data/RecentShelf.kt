package com.music.bitchord.data

import com.music.bitchord.data.model.Song

/**
 * The rules for the local-first Recently played shelf and history page.
 *
 * `youtube.com` history is every video the account watched, including things
 * watched in the YouTube app, so it is only a filler here. The device's own
 * record is what the listener played as music, in the order they played it.
 *
 * Pure and separate from the stores so the ordering and merge can be tested
 * without a player, a network, or either platform's preferences file.
 */
internal object RecentShelf {

    /** The shelf and the local part of the history page both stop here. */
    const val LIMIT = 20

    /**
     * Below this many local tracks the shelf is filled from youtube.com; at or
     * above it the local record alone is the answer, so no request is made.
     */
    const val LOCAL_ONLY_MINIMUM = 8

    /** Most recent first, one row per video id, at most [limit]. */
    fun localSongs(plays: List<LocalPlay>, limit: Int = LIMIT): List<Song> =
        plays.asSequence()
            .filter { it.song.videoId.isNotBlank() }
            .sortedByDescending { it.playedAtMs }
            .distinctBy { it.song.videoId }
            .take(limit)
            .map { it.song }
            .toList()

    /**
     * [local] first, then [remote] rows that are not already there, up to
     * [limit]. Remote rows are never used to replace a local row: its cover is
     * the one the listener saw.
     *
     * [remote] is only consulted while [local] is short of [localOnlyMinimum];
     * a caller that already has enough local tracks should not fetch it at all.
     */
    fun merge(
        local: List<Song>,
        remote: List<Song>,
        limit: Int = LIMIT,
        localOnlyMinimum: Int = LOCAL_ONLY_MINIMUM,
    ): List<Song> {
        val own = local.asSequence()
            .filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }
            .take(limit)
            .toList()
        if (own.size >= localOnlyMinimum) return own

        val seen = own.mapTo(HashSet()) { it.videoId }
        val merged = own.toMutableList()
        for (song in remote) {
            if (merged.size >= limit) break
            if (song.videoId.isBlank() || !seen.add(song.videoId)) continue
            merged += song
        }
        return merged
    }
}
