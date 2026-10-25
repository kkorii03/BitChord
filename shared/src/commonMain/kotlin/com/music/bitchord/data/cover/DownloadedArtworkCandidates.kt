package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song

/** One place a downloaded track's picture can come from, in trying order. */
enum class ArtworkCandidateKind {
    /** The picture inside the downloaded audio file itself. */
    EMBEDDED,

    /** The sidecar this app saved beside the audio when it tagged the file. */
    SAVED_THUMBNAIL,

    /** YouTube's own still for the video id. */
    YOUTUBE,

    /** Nothing worked: draw the player's default glyph. */
    DEFAULT,
}

/** A candidate source and the value that names it, or null for [ArtworkCandidateKind.DEFAULT]. */
data class ArtworkCandidate(val kind: ArtworkCandidateKind, val value: String?)

/**
 * The pictures to try for a downloaded track, best first and each exactly once.
 *
 * A downloaded file's recorded thumbnail can be an album-art content URI that
 * the media scanner has since dropped, or a sidecar that was deleted by hand.
 * The player used to take that one URL as final, fail, and retry the same dead
 * request every second and a half. The file's own picture is the better first
 * answer anyway, and YouTube always has a still for a row that still carries
 * its id — so the order is embedded, saved sidecar, YouTube, then nothing.
 *
 * Only local (`file:`/`content:`) thumbnails count as the saved sidecar: a
 * YouTube URL is already the [ArtworkCandidateKind.YOUTUBE] rung and must not
 * be tried twice under two names.
 */
fun downloadedArtworkCandidates(song: Song): List<ArtworkCandidate> {
    val embedded = song.localUri?.takeIf(::isLocalArtwork)
    val saved = song.thumbnailUrl
        ?.takeIf { isLocalArtwork(it) && it != song.localUri }
    val youtube = videoThumbnailUrl(song.videoId)
    return buildList {
        embedded?.let { add(ArtworkCandidate(ArtworkCandidateKind.EMBEDDED, it)) }
        saved?.let { add(ArtworkCandidate(ArtworkCandidateKind.SAVED_THUMBNAIL, it)) }
        youtube?.let { add(ArtworkCandidate(ArtworkCandidateKind.YOUTUBE, it)) }
        add(ArtworkCandidate(ArtworkCandidateKind.DEFAULT, null))
    }
}

private fun isLocalArtwork(value: String): Boolean =
    value.startsWith("file:", ignoreCase = true) ||
        value.startsWith("content:", ignoreCase = true)
