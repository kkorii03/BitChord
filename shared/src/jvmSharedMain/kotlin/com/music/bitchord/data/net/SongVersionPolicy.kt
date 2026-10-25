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
     * Whether a row with a file on the device is already the recording to play.
     *
     * A downloaded song, and a file from the listener's own library, are audio
     * rows and must be left alone. A downloaded *video* is not: it is still a
     * music video, so the same lookup that converts a streamed video to its
     * catalogue release applies to it. `typeUnknown` counts for the same reason
     * as [wantsSongVersion] — a preview row may turn out to be a video.
     *
     * This is the one place the old blanket "has a local file, so skip" rule
     * lives now; every version path asks this question rather than testing
     * `localUri` on its own.
     */
    fun keepsLocalFile(
        localFile: Boolean,
        isVideo: Boolean,
        typeUnknown: Boolean,
    ): Boolean = localFile && !wantsSongVersion(isVideo, typeUnknown)

    /**
     * Whether choosing a download for this row has to fetch the song version.
     *
     * A music video's own stream is its video's sound, never what the app plays
     * as the song. A row already decided to have no catalogue release keeps its
     * own id rather than paying for a lookup that cannot change the answer.
     */
    fun downloadIsSongVersion(
        isVideo: Boolean,
        typeUnknown: Boolean,
        knownMissing: Boolean,
    ): Boolean = wantsSongVersion(isVideo, typeUnknown) && !knownMissing

    /** What a downloaded file may be used for, once its sound source is known. */
    enum class DownloadedFileUse {
        /** The file's recorded source is the recording being played. */
        PLAY_FILE,

        /** The file is the video's sound under a song id; stream the song. */
        STREAM_SONG,

        /** No provenance was recorded (a file from an older version); keep the file. */
        UNKNOWN_PLAY_FILE,
    }

    /**
     * How a saved file may be used for [playId].
     *
     * [soundSourceId] is the id whose stream produced the bytes, recorded when
     * the download was made. A mismatch means the row is a song id but the file
     * is the music video's sound; online, the song is streamed and the file is
     * kept only as the offline fallback. A null source is an older record whose
     * provenance is unknown — never guessed at, so it keeps playing as before.
     */
    fun downloadedFileUse(
        playId: String,
        soundSourceId: String?,
        online: Boolean,
    ): DownloadedFileUse = when {
        soundSourceId == null -> DownloadedFileUse.UNKNOWN_PLAY_FILE
        soundSourceId == playId -> DownloadedFileUse.PLAY_FILE
        !online -> DownloadedFileUse.PLAY_FILE
        else -> DownloadedFileUse.STREAM_SONG
    }

    /**
     * Whether an existing file may be adopted as [targetId]'s download.
     *
     * Only a file this app recorded as having been fetched from [targetId]'s
     * own stream qualifies. A legacy record (no provenance) is not assumed to
     * be the right recording, because adopting the music video's sound under the
     * song id is exactly the mix-up this field exists to prevent.
     */
    fun mayAdoptDownloadedFile(targetId: String, soundSourceId: String?): Boolean =
        soundSourceId == targetId

    /**
     * Whether the row [mediaId] has made its first sound. The only evidence is
     * this row's own audible id: while a *previous* track is still sounding,
     * the player is playing, but the new row has not been heard yet — a jump
     * made in that moment must still be allowed to install its song version.
     */
    fun hasMadeFirstSound(mediaId: String?, audibleMediaId: String?): Boolean =
        mediaId != null && audibleMediaId == mediaId

    /**
     * What the row after the current one is, as far as its pre-playback version
     * lookup goes.
     *
     * Read-ahead needs this answer before it mints anything for that row. An
     * undecided music video's own id is not what will play, so resolving it
     * spends a client walk — and, on a gated connection, a proxy race — on an
     * id the answer replaces moments later. That was a 13 s proxy race in front
     * of an unrelated first sound (report 60).
     */
    enum class AheadSongVersion {
        /** Not a video, so the row's own id is the recording to warm. */
        NOT_WANTED,

        /** A song version is known; warm that id instead. */
        FOUND,

        /** Already established that this row has none; its own id is right. */
        NONE,

        /** The lookup has not answered yet; warm neither id until it does. */
        UNDECIDED,
    }

    /** The prefetch decision for the row after the current one. */
    fun aheadSongVersion(
        isVideo: Boolean,
        typeUnknown: Boolean,
        hasVersion: Boolean,
        knownMissing: Boolean,
    ): AheadSongVersion = when {
        !wantsSongVersion(isVideo, typeUnknown) -> AheadSongVersion.NOT_WANTED
        hasVersion -> AheadSongVersion.FOUND
        knownMissing -> AheadSongVersion.NONE
        else -> AheadSongVersion.UNDECIDED
    }
}
