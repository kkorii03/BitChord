package com.music.bitchord.data

import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection

/**
 * A tile's cover is borrowed from the first playlist inside its category. It
 * is fetched only for a tile the UI has put on screen, and then lands on that
 * exact tile — the same browse id with different params is a different grid.
 */
object ExploreArtwork {

    fun hasCover(item: MoodGenre): Boolean = !item.thumbnailUrl.isNullOrBlank()

    /** The same grid with [artwork] given to the tile behind [browseId] and [params]. */
    fun withCover(
        sections: List<MoodGenreSection>,
        browseId: String,
        params: String?,
        artwork: String,
    ): List<MoodGenreSection> = sections.map { section ->
        section.copy(
            items = section.items.map { item ->
                if (item.browseId == browseId && item.params == params) {
                    item.copy(thumbnailUrl = artwork)
                } else {
                    item
                }
            },
        )
    }
}
