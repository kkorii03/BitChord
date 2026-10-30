package com.music.bitchord.data.net

import com.music.bitchord.data.CatalogSongMapper
import com.music.bitchord.data.model.Song

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
     * Whether a downloaded file with no recorded provenance must still be
     * checked against the catalogue before it is trusted.
     *
     * A download saved before provenance was recorded cannot say which stream
     * produced its bytes. When that row also reads as a song (`isVideo` and
     * `typeUnknown` both false), the old blanket "has a file, so skip" rule
     * played the file — and a music video downloaded under its own id is
     * exactly the case it cannot see. [isVideo] is therefore tri-state: null
     * means the record predates the field, so even a record whose source is
     * its own id is verified. Online, such a file is verified like a video
     * candidate: its catalogue release is streamed while the file stays as the
     * offline fallback. Offline the file is the only sound there is, so it is
     * kept without a request.
     *
     * [provenanceIsOwnId] separates a legacy own-id record from one whose
     * source names a different upload: the latter's bytes are already known
     * to come from the catalogue release, so it needs no verification. The
     * listener's own files, downloads whose provenance is known, and rows
     * already known to be videos are left to their existing paths.
     */
    fun verifiesUnknownDownload(
        localFile: Boolean,
        downloaded: Boolean,
        provenanceKnown: Boolean,
        isVideo: Boolean?,
        typeUnknown: Boolean,
        offline: Boolean,
        provenanceIsOwnId: Boolean = false,
    ): Boolean {
        if (!localFile || !downloaded || offline) return false
        if (wantsSongVersion(isVideo == true, typeUnknown)) return false
        if (!provenanceKnown) return true
        return isVideo == null && provenanceIsOwnId
    }

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

    /**
     * Whether an automatic song version may replace [row].
     *
     * A cover stays the performance the listener chose: its arrangement
     * marker, its singer, or the original artist its byline names are not the
     * catalogue release, however exactly the title reads.
     *
     * Nothing else is compared. A music video and the catalogue release it
     * promotes are the same song even when the release's title carries a
     * feature or bonus-track tag the video's title does not
     * ("Dancing With A Stranger (with Normani) [Bonus Track]"). Re-matching
     * titles or runtimes here is what left such a video playing instead of its
     * song version, so only the cover verdict is asked.
     */
    fun mayInstallSongVersion(row: Song): Boolean =
        !CatalogSongMapper.isCover(row)

    /**
     * Whether a song-version lookup has reached a verdict that may be cached
     * for the rest of the run.
     *
     * A found recording is always definitive. "No catalogue release" is only
     * definitive when the search actually answered and recorded that answer;
     * a request that failed outright must stay retryable. The audio switch
     * returns the original row for both shapes, so the caller cannot tell them
     * apart from the return value alone — [answered] is the cache marker that
     * separates "searched and found nothing" from "could not search".
     */
    fun songVersionVerdictIsDefinitive(
        searchSucceeded: Boolean,
        answered: Boolean,
        found: Boolean,
    ): Boolean = found || (searchSucceeded && answered)

    /**
     * Whether an audio-switch pass is complete enough to cache "no catalogue
     * release" for the rest of the run.
     *
     * The pass runs several queries. One that succeeded does not make the
     * verdict final when a later query failed: the failed query may have been
     * the one that would have found the song. Caching the original row then
     * would turn a transient network error into a permanent kept(none) for
     * this launch. Only a pass where every query answered may be cached.
     */
    fun audioSwitchSearchIsConclusive(queriesRun: Int, failedQueries: Int): Boolean =
        queriesRun > 0 && failedQueries == 0

    /**
     * How much longer than the catalogue track a downloaded song's file may be
     * before it is taken to be the music video's sound.
     *
     * A song and its video are the same recording, but the video carries an
     * intro, an outro, or both. Two and a half seconds is far beyond the
     * container's own rounding and well inside the shortest video-only intro
     * seen on a real report (a 4 s difference on 'That's What I Like').
     */
    const val MV_SOUND_SLACK_MS = 2_500L

    /**
     * How much longer [fileDurationMs] is than the catalogue track, when that
     * difference is enough to suspect the file is a music video's sound.
     *
     * Only files whose provenance was never recorded are considered: a record
     * that names its sound source is already answered by [downloadedFileUse].
     * A video row is not a song version question at all, and missing either
     * length is not evidence — null, never a guess.
     */
    fun mvSoundDeltaMs(
        fileDurationMs: Long?,
        catalogDurationMs: Long?,
        isVideo: Boolean,
        localFile: Boolean,
        soundSourceId: String?,
    ): Long? {
        if (isVideo || !localFile || soundSourceId != null) return null
        val file = fileDurationMs?.takeIf { it > 0L } ?: return null
        val catalog = catalogDurationMs?.takeIf { it > 0L } ?: return null
        return (file - catalog).takeIf { it >= MV_SOUND_SLACK_MS }
    }

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
     * provenance is unknown — it is not guessed at here; [mvSoundDeltaMs] is
     * the length evidence that may still identify it.
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
