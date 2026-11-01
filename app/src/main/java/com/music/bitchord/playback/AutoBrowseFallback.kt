package com.music.bitchord.playback

/**
 * What a car should show for a playlist or browse request.
 *
 * A timed-out or failed fetch used to add no rows at all, so Android Auto drew
 * an empty folder that looked exactly like a playlist with no songs. A folder
 * that could not be loaded has to say so instead.
 *
 * A successful fetch is not the same as a failure, though. When the request
 * returns an empty list the folder really has no songs, so telling the listener
 * to reopen it would repeat a message that can never change.
 */
internal object AutoBrowseFallback {

    /**
     * [songs] is null only when the fetch failed or timed out; an empty list is
     * a successful, genuinely empty folder.
     */
    fun <T> items(
        songs: List<T>?,
        unavailable: () -> T,
        empty: () -> T,
    ): List<T> = when {
        songs == null -> listOf(unavailable())
        songs.isEmpty() -> listOf(empty())
        else -> songs
    }
}
