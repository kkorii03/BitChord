package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song

/** Where a chosen artwork URL points, and therefore who can read it. */
enum class ArtworkSource {
    /** A `file:` or `content:` URI on this device. */
    LOCAL,

    /** An `http(s):` (or other remote) URL. */
    REMOTE,

    /** Nothing to draw. */
    NONE,
}

/** The artwork to try for a row, with the value that names it. */
data class ArtworkSelection(val source: ArtworkSource, val value: String?) {
    companion object {
        val None = ArtworkSelection(ArtworkSource.NONE, null)
    }
}

/**
 * Choose the artwork URL for one row, given whether the local file it records
 * is actually still there.
 *
 * The caller owns the one thing this cannot ask without touching the disk:
 * [localArtworkExists]. A recorded local sidecar that has gone (`ENOENT`) is
 * never chosen again — YouTube's still for the video id is used instead, which
 * both the app and another process can read. A remote thumbnail, including a
 * streaming cover's `https` still, is returned exactly as recorded.
 *
 * A [ArtworkSource.LOCAL] answer is still the right answer for the media
 * session, but the caller must hand it over as bytes or a `content://` URI:
 * a private `file://` path in `MediaMetadata.artworkUri` is unreadable by
 * System UI. See `MediaSessionArtwork` in the app.
 */
fun artworkSelection(
    thumbnailUrl: String?,
    videoId: String?,
    localArtworkExists: Boolean,
): ArtworkSelection {
    val recorded = thumbnailUrl?.trim()?.takeIf { it.isNotEmpty() }
    if (recorded != null) {
        if (isLocalArtworkUri(recorded)) {
            if (localArtworkExists) return ArtworkSelection(ArtworkSource.LOCAL, recorded)
        } else {
            return ArtworkSelection(ArtworkSource.REMOTE, recorded)
        }
    }
    val still = videoThumbnailUrl(videoId)
    return still?.let { ArtworkSelection(ArtworkSource.REMOTE, it) } ?: ArtworkSelection.None
}

/** As [artworkSelection], for a track. */
fun Song.artworkSelection(localArtworkExists: Boolean): ArtworkSelection =
    artworkSelection(thumbnailUrl, videoId, localArtworkExists)

/** Whether [value] names a picture this app reads from its own storage. */
fun isLocalArtworkUri(value: String): Boolean =
    value.startsWith("file:", ignoreCase = true) ||
        value.startsWith("content:", ignoreCase = true)
