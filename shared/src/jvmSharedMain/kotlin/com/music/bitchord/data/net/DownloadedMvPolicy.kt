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
}
