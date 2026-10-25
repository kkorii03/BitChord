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
     * lookup is pointless and the search fallback is used directly.
     */
    const val COUNTERPART_EMPTY_LIMIT = 5

    /**
     * How long a proven-empty counterpart endpoint stays switched off.
     *
     * The verdict is a property of the signed-out endpoint, not of one launch,
     * so a restart must not spend the five empty answers (0.8–1.1 s each)
     * again. A week later it is tried five more times in case the account or
     * the endpoint changed.
     */
    const val COUNTERPART_OFF_MS = 7L * 24L * 60L * 60L * 1000L

    /** True once the counterpart endpoint has proven it answers nothing. */
    fun counterpartDisabled(consecutiveEmptyResponses: Int): Boolean =
        consecutiveEmptyResponses >= COUNTERPART_EMPTY_LIMIT

    /** The deadline recorded when the endpoint proves empty at [now]. */
    fun counterpartOffUntil(now: Long): Long = now + COUNTERPART_OFF_MS

    /**
     * Whether the counterpart lookup is still switched off at [now].
     *
     * A missing or elapsed deadline means it may be tried again, and the run
     * of empty answers starts from zero; five of them switch it off anew.
     */
    fun counterpartSuppressed(offUntil: Long?, now: Long): Boolean =
        offUntil != null && now < offUntil

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

    /**
     * Whether this row is worth a song-version lookup. A confirmed catalogue
     * track is not; a music video is, and so is a preview row whose type is not
     * known yet — it may well be a video, and assuming it is not is what left
     * such a row playing its MV.
     */
    fun wantsSongVersion(isVideo: Boolean, typeUnknown: Boolean): Boolean =
        isVideo || typeUnknown

    /**
     * Whether the row [mediaId] has made its first sound. The only evidence is
     * this row's own audible id: while a *previous* track is still sounding,
     * the player is playing, but the new row has not been heard yet — a jump
     * made in that moment must still be allowed to install its song version.
     */
    fun hasMadeFirstSound(mediaId: String?, audibleMediaId: String?): Boolean =
        mediaId != null && audibleMediaId == mediaId
}
