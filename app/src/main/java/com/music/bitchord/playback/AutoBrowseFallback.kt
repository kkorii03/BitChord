package com.music.bitchord.playback

/**
 * What a car should show when a playlist or browse request produced nothing.
 *
 * A timed-out or failed fetch used to add no rows at all, so Android Auto drew
 * an empty folder that looked exactly like a playlist with no songs. A folder
 * that could not be loaded has to say so instead.
 */
internal object AutoBrowseFallback {

    fun <T> items(songs: List<T>, unavailable: () -> T): List<T> =
        if (songs.isNotEmpty()) songs else listOf(unavailable())
}
