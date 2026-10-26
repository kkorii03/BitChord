package com.music.bitchord.data.net

/**
 * Whether a downloaded file is a music video's sound rather than the song.
 *
 * The record written by an older version may carry no upload type at all, so
 * this decision must still work from what the file itself says: its title, its
 * length beside the catalogue release, and any provenance the record did keep.
 * A music video downloaded under its own id is exactly the case the old
 * `wasVideoRow` test could not see, because its recorded source is its own id.
 */
object DownloadedMvPolicy {

    /**
     * A file longer than the catalogue song by more than this is the video's
     * sound. Three seconds clears the container's rounding and the shortest
     * video-only intro observed on a real report.
     */
    const val MV_DURATION_SLACK_MS = 3_000L

    /** Packaging that only a video upload carries, matched case-insensitively. */
    private val MV_TITLE_MARKERS = listOf(
        "(official video)",
        "official music video",
        "(official mv)",
        "m/v",
        "[mv]",
        "music video",
    )

    /** The standalone word "MV", not the letters inside another word. */
    private val MV_WORD = Regex("""(?i)(?<![a-z0-9])mv(?![a-z0-9])""")

    /** Whether [title] carries a music-video marker. */
    fun hasMvTitleMarker(title: String): Boolean {
        if (title.isBlank()) return false
        val lower = title.lowercase()
        return MV_TITLE_MARKERS.any { lower.contains(it) } ||
            MV_WORD.containsMatchIn(title)
    }

    /**
     * Whether a re-download must keep the upload type already on the record.
     *
     * A repair is queued as an unknown row so its lookup asks for the song
     * version, but the file that lands is still the same recording the record
     * had already classified. Writing that placeholder's `typeUnknown` over a
     * definite stored type made the next play re-open a question that had
     * already been answered. An incoming definite type (a real video or audio
     * row) still wins, because that is new evidence about the new bytes.
     */
    fun keepsStoredUploadType(
        storedIsVideo: Boolean?,
        storedTypeUnknown: Boolean,
        incomingIsVideo: Boolean?,
        incomingTypeUnknown: Boolean,
    ): Boolean = incomingTypeUnknown && incomingIsVideo != true && storedIsVideo != null

    /**
     * Whether this record already names its own stream as a song version.
     *
     * The pair of a definite audio type and its own provenance is what this
     * version writes after a download; it is stronger evidence than a title or
     * a length measured later, so the Downloads scan must not re-open it.
     */
    fun isRecordedSongVersion(
        storedIsVideo: Boolean?,
        storedTypeUnknown: Boolean,
        soundSourceVideoId: String?,
        rowVideoId: String,
    ): Boolean = storedIsVideo == false &&
        !storedTypeUnknown &&
        soundSourceVideoId == rowVideoId

    /**
     * Whether the file is the music video's sound.
     *
     * A recorded video/unknown type is conclusive. When the file was fetched
     * from a *different* upload, the provenance rule already owns the answer,
     * so title and length add nothing. Otherwise the title's own packaging and
     * a file more than [MV_DURATION_SLACK_MS] longer than a catalogue release
     * under another id identify a legacy video download with no stored type.
     *
     * A cover has no catalogue release to compare against, so it stays the
     * listener's chosen performance rather than being called an MV.
     */
    fun isMvSound(
        rowVideoId: String,
        title: String,
        fileDurationMs: Long?,
        storedIsVideo: Boolean?,
        storedTypeUnknown: Boolean,
        soundSourceVideoId: String?,
        catalogVideoId: String?,
        catalogDurationMs: Long?,
    ): Boolean {
        if (storedIsVideo == true || storedTypeUnknown) return true
        if (soundSourceVideoId != null && soundSourceVideoId != rowVideoId) return false
        if (hasMvTitleMarker(title)) return true
        val catalogId = catalogVideoId?.takeIf { it.isNotBlank() } ?: return false
        if (catalogId == rowVideoId) return false
        val file = fileDurationMs?.takeIf { it > 0L } ?: return false
        val catalog = catalogDurationMs?.takeIf { it > 0L } ?: return false
        return file - catalog > MV_DURATION_SLACK_MS
    }

    /**
     * The verdict the Downloads page should keep for one saved file, or null
     * when the file is already known to be the song version.
     *
     * The scan used to look only at records with no provenance, so it walked
     * past the recognition those records already carried. This applies the
     * same rules as playback — an own-stream song version is left alone, a
     * recorded video/unknown type or another upload's stream is repaired, and
     * only a legacy record is measured by length — while still returning the
     * length difference the banner and log want when there is one.
     *
     * The legacy length rule keeps [SongVersionPolicy.MV_SOUND_SLACK_MS], not
     * [MV_DURATION_SLACK_MS]: the Downloads scan has always used the shorter
     * threshold, and it must not lose a file that only just exceeds it.
     */
    fun mvSoundSuspectDeltaMs(
        rowVideoId: String,
        title: String,
        fileDurationMs: Long?,
        storedIsVideo: Boolean?,
        storedTypeUnknown: Boolean,
        soundSourceVideoId: String?,
        catalogDurationMs: Long?,
        legacySlackMs: Long = SongVersionPolicy.MV_SOUND_SLACK_MS,
    ): Long? {
        if (isRecordedSongVersion(storedIsVideo, storedTypeUnknown, soundSourceVideoId, rowVideoId)) {
            return null
        }
        val file = fileDurationMs?.takeIf { it > 0L }
        val catalog = catalogDurationMs?.takeIf { it > 0L }
        val delta = if (file != null && catalog != null) {
            (file - catalog).coerceAtLeast(0L)
        } else {
            null
        }
        val knownMv = storedIsVideo == true ||
            storedTypeUnknown ||
            (soundSourceVideoId != null && soundSourceVideoId != rowVideoId)
        if (knownMv || hasMvTitleMarker(title)) return delta ?: 0L
        // A legacy record has no catalogue id of its own to compare with; the
        // shortest length recorded against the file is the song's. The scan has
        // always measured against that, even when it belongs to the same id.
        if (file == null || catalog == null) return null
        return (file - catalog).takeIf { it >= legacySlackMs }
    }
}
