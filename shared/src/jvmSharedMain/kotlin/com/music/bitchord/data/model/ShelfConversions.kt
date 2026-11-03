package com.music.bitchord.data.model

import com.music.bitchord.data.innertube.InnertubeParser

/** The playable row a track card stands for, keeping the upload type it carried. */
fun ShelfItem.toSong(): Song? {
    val videoId = videoId?.takeIf { it.isNotBlank() } ?: return null
    return Song(
        videoId = videoId,
        title = title,
        artist = InnertubeParser.artistFromSubtitle(subtitle),
        thumbnailUrl = thumbnailUrl,
        isVideo = isVideo,
    )
}

/** The track card a row is drawn as, keeping the upload type it carried. */
fun Song.toShelfItem(): ShelfItem = ShelfItem(
    title = title,
    subtitle = artist,
    thumbnailUrl = thumbnailUrl,
    videoId = videoId,
    browseId = null,
    isVideo = isVideo,
)
