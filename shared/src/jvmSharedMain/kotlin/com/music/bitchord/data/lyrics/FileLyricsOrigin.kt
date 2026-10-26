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
        val id = knownVideoId(storedVideoId) ?: "unknown"
        return "file:$name@$id"
    }

    /**
     * The recording id a file header really names, or null when it names none.
     *
     * `@unknown` is the sentinel for "no id is known" (and what [label]
     * prints for a missing id). Treating it as a literal id made an
     * unverified file look like another recording, which sent it down the
     * foreign-recording path instead of the one-check path. Blank and the
     * sentinel mean the same thing: nothing was written down.
     */
    fun knownVideoId(storedVideoId: String?): String? =
        storedVideoId
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(UNKNOWN, ignoreCase = true) }

    private const val UNKNOWN = "unknown"

    /**
     * Whether [storedVideoId]'s lyrics belong to another recording than
     * [playingVideoId]. Unknown ids are not evidence of a mismatch: an older
     * file simply says nothing, and its existing treatment stands.
     */
    fun timingBelongsToAnotherRecording(storedVideoId: String?, playingVideoId: String): Boolean {
        val stored = knownVideoId(storedVideoId) ?: return false
        return playingVideoId.isNotBlank() && stored != playingVideoId
    }

    /**
     * Whether these lyrics' times have never been checked against what is
     * playing. Wider than [timingBelongsToAnotherRecording] on purpose: a file
     * whose header names no id (written before the id was recorded) has never
     * been verified at all, so it is worth one check — the song path spends at
     * most one per lyrics text and refuses shifts past the song floor.
     */
    fun timingIsUnverified(storedVideoId: String?, playingVideoId: String): Boolean {
        val stored = knownVideoId(storedVideoId)
        return playingVideoId.isNotBlank() && (stored == null || stored != playingVideoId)
    }
}
