package com.music.bitchord.data.net

/**
 * The two decisions behind the pre-playback song-version lookup, kept free of
 * Android types so a plain JVM test can exercise them.
 *
 * BitChord plays the catalogue release (ATV) of a track, never the music video,
 * and it decides that *before* sound starts. Once a track is audible it is the
 * listener's track: no automatic swap may replace it mid-song.
 */
object SongVersionPolicy {
    /**
     * How long a queue build or a track transition may hold the first sound
     * while the song version is resolved. Long enough for one `next` plus one
     * search on a normal connection, short enough not to feel like a stall.
     */
    const val BEFORE_AUDIO_TIMEOUT_MS = 1_500L

    /**
     * Signed-out `next` responses carried no `playlistPanelVideoWrapperRenderer`
     * at all in the observed sessions (the pairing appears to be a premium
     * feature), so after this many empty answers in a row the counterpart
     * lookup is pointless for the rest of the session and the search fallback
     * is used directly.
     */
    const val COUNTERPART_EMPTY_LIMIT = 5

    /** True once the counterpart endpoint has proven it answers nothing. */
    fun counterpartDisabled(consecutiveEmptyResponses: Int): Boolean =
        consecutiveEmptyResponses >= COUNTERPART_EMPTY_LIMIT

    /**
     * Whether an automatic song-version swap may still happen: only before the
     * track has made a sound. A position past zero, a playing player, or a
     * track already marked audible all mean the listener is hearing it, and
     * swapping then would change the recording under them.
     */
    fun mayAutoSwapBeforeAudio(
        positionMs: Long,
        isPlaying: Boolean,
        alreadyAudible: Boolean,
    ): Boolean = !alreadyAudible && !isPlaying && positionMs <= 0L
}
