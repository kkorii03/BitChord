package com.music.bitchord.data.lyrics

/**
 * Which recording a downloaded file's lyrics were timed for.
 *
 * A download writes the provider's times into the file as they came. Those
 * times belong to the recording the lookup named, and one file can be reached
 * by two ids (a music video and the catalogue track it was swapped to). The
 * file's own header now names the id the lyrics were fetched for, so a play of
 * a *different* id can tell that its timing is somebody else's and measure it
 * instead of showing it as-is.
 *
 * Pure, so the rule is testable without a file or a player.
 */
object FileLyricsOrigin {

    /** `file:<provider>@<videoId>` for the `lyrics-sync` line. */
    fun label(provider: String?, storedVideoId: String?): String {
        val name = provider?.takeIf { it.isNotBlank() } ?: "unknown"
        // An older file's header names no id. Say so rather than echoing the
        // id being played, which would claim a match nobody wrote down.
        val id = storedVideoId?.takeIf { it.isNotBlank() } ?: "unknown"
        return "file:$name@$id"
    }

    /**
     * Whether [storedVideoId]'s lyrics belong to another recording than
     * [playingVideoId]. Unknown ids are not evidence of a mismatch: an older
     * file simply says nothing, and its existing treatment stands.
     */
    fun timingBelongsToAnotherRecording(storedVideoId: String?, playingVideoId: String): Boolean {
        val stored = storedVideoId?.takeIf { it.isNotBlank() } ?: return false
        return playingVideoId.isNotBlank() && stored != playingVideoId
    }
}
