package com.music.bitchord.ko

import com.music.bitchord.data.cover.YouTubeCoverResolver
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.firstArtworkOrStill

/** Library and detail-page artwork helpers kept out of the upstream file. */
internal object KoLibrary {

    /**
     * Swaps the first [limit] video frames in a page's rows for square album
     * covers, using [YouTubeCoverResolver]'s existing cache and concurrency
     * cap. Called after a detail page is already on screen, so a cover lookup
     * never holds the first paint.
     */
    suspend fun enrichDetailCovers(songs: List<Song>, limit: Int = 20): List<Song> =
        YouTubeCoverResolver.enrich(songs, limit)

    fun needsDetailCover(song: Song): Boolean =
        YouTubeCoverResolver.isVideoThumbnail(song.thumbnailUrl)
}

/**
 * A playlist header can arrive without the playlist's own cover even when
 * its first rows have theirs. The first track's still stands in rather
 * than leaving the page with the default playlist icon.
 */
internal fun InnertubeParser.BrowseHeader?.withArtworkFallback(
    songs: List<Song>,
): InnertubeParser.BrowseHeader? {
    val header = this ?: return null
    if (!header.thumbnailUrl.isNullOrBlank()) return header
    val fallback = songs.firstArtworkOrStill() ?: return header
    return header.copy(thumbnailUrl = fallback)
}
