package com.music.bitchord.ui

/**
 * When reopening the lyrics panel should ask the providers once more.
 *
 * A miss is not a final answer: a source may have been down or the phone
 * offline. The lookup clears its claim so a later visit can retry, but the
 * player only calls [LyricsController.loadLyrics] when the track or a setting
 * changes. Without this decision a transient miss stayed on screen until the
 * next track, even though the panel had been closed and reopened.
 */
internal object LyricsRetry {

    fun shouldRetry(
        checked: Boolean,
        hasLyrics: Boolean,
        lookupClaimed: Boolean,
        hasRequest: Boolean,
    ): Boolean = hasRequest && checked && !hasLyrics && !lookupClaimed
}
