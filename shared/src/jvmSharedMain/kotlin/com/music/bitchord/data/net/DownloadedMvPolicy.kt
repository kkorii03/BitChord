package com.music.bitchord.data.net

import com.music.bitchord.data.CatalogSongMapper
import com.music.bitchord.data.model.Song

/**
 * Whether a downloaded file is a music video's sound rather than the song.
 *
 * The record written by an older version may carry no upload type at all, so
 * this decision must still work from what the file itself says: its title, its
 * length beside the catalogue release, and any provenance the record did keep.
 * A music video downloaded under its own id is exactly the case the old
 * `wasVideoRow` test could not see, because its recorded source is its own id.
 *
 * A video upload is not automatically an MV's sound: a cover or a live clip is
 * also a video, and the listener chose that performance. Only the official MV
 * may be replaced, so every verdict first rules a cover out.
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
     * Whether this download is a cover, live clip or other chosen performance.
     *
     * A cover's byline is enough even when the record lost its video flag: the
     * Korean "커버" suffix is written by [com.music.bitchord.data.cover.CoverTitles]
     * for exactly this case. Otherwise the same reader the rest of the app uses
     * decides, so a cover-looking title is not mistaken for the official MV.
     */
    fun isCover(
        rowVideoId: String,
        title: String,
        artist: String,
        isVideo: Boolean?,
    ): Boolean {
        if (artist.endsWith(" 커버")) return true
        return CatalogSongMapper.isCover(
            Song(
                videoId = rowVideoId,
                title = title,
                artist = artist,
                thumbnailUrl = null,
                isVideo = isVideo == true,
            ),
        )
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
     * A cover is never an MV, whatever its upload type says. Otherwise only
     * evidence that names the official MV counts: a video title marker, or
     * bytes recorded from a *different* upload that is itself recorded as a
     * video. A stored video/unknown type alone is not evidence — that is what
     * turned every downloaded cover and every unclassified song into a
     * suspect. When provenance names a different upload that is not a video,
     * that rule owns the answer, so title and length add nothing. Finally a
     * file more than [MV_DURATION_SLACK_MS] longer than a catalogue release
     * under another id identifies a legacy video download with no stored type.
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
        artist: String = "",
        sourceRecordedAsMv: Boolean = false,
    ): Boolean {
        if (isRecordedSongVersion(storedIsVideo, storedTypeUnknown, soundSourceVideoId, rowVideoId)) {
            return false
        }
        if (isCover(rowVideoId, title, artist, storedIsVideo)) return false
        if (soundSourceVideoId != null && soundSourceVideoId != rowVideoId) {
            return sourceRecordedAsMv
        }
        // Playing, a video row that is not a cover is the MV's sound: titles
        // are cleaned for display (ko-88), so a downloaded official video often
        // has no "(Official Video)" left to find, and the stored type is the
        // only sign. An unknown type is looked up rather than trusted. This is
        // a lookup before audio, never a re-download, so it cannot cost a file.
        if (storedIsVideo == true || storedTypeUnknown) return true
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
     * same rules as playback — a cover or an own-stream song version is left
     * alone, a video title or another upload's recorded video stream is
     * repaired, and only a legacy record is measured by length — while still
     * returning the length difference the banner and log want when there is
     * one.
     *
     * A stored `isVideo` or `typeUnknown` is not evidence on its own. A cover,
     * a live clip and an ordinary song can all be video rows; treating the
     * flag as the answer replaced the listener's chosen performance with the
     * original recording.
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
        artist: String = "",
        sourceRecordedAsMv: Boolean = false,
    ): Long? {
        if (isRecordedSongVersion(storedIsVideo, storedTypeUnknown, soundSourceVideoId, rowVideoId)) {
            return null
        }
        if (isCover(rowVideoId, title, artist, storedIsVideo)) return null
        val file = fileDurationMs?.takeIf { it > 0L }
        val catalog = catalogDurationMs?.takeIf { it > 0L }
        val delta = if (file != null && catalog != null) {
            (file - catalog).coerceAtLeast(0L)
        } else {
            null
        }
        val sourceIsMv = soundSourceVideoId != null &&
            soundSourceVideoId != rowVideoId &&
            sourceRecordedAsMv
        if (sourceIsMv || hasMvTitleMarker(title)) return delta ?: 0L
        // A legacy record has no catalogue id of its own to compare with; the
        // shortest length recorded against the file is the song's. The scan has
        // always measured against that, even when it belongs to the same id.
        if (file == null || catalog == null) return null
        return (file - catalog).takeIf { it >= legacySlackMs }
    }
}
