package com.music.bitchord

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.ko.chooseAlbumSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Opening an album by name must not take whatever the search happened to put
 * first: a same-name release by another artist is a different album.
 *
 * The artist path already applies an exact-name rule; the album path used to
 * take `firstOrNull()` and could open a cover, a tribute or another act's
 * release with the same title.
 */
class AlbumResultSelectionTest {

    private fun album(title: String, subtitle: String, id: String = "MPREb$title") =
        SearchResult.Browse(
            BrowseItem(
                browseId = id,
                title = title,
                subtitle = subtitle,
                thumbnailUrl = null,
                type = BrowseType.ALBUM,
            ),
        )

    @Test
    fun `a same-title release by another artist is not opened`() {
        val wanted = album("Greatest Hits", "Album • IU • 2019", id = "MPREbIU")
        val results = listOf(
            album("Greatest Hits", "Album • Other Artist • 2020", id = "MPREbOther"),
            wanted,
        )

        assertEquals(wanted.item, chooseAlbumSearchHit(results, "Greatest Hits", "IU"))
    }

    @Test
    fun `a first result with a different title is not opened`() {
        val wanted = album("Love Poem", "Album • IU • 2019", id = "MPREbLovePoem")
        val results = listOf(
            album("Other Album", "Album • Other Artist • 2020", id = "MPREbOther"),
            wanted,
        )

        assertEquals(wanted.item, chooseAlbumSearchHit(results, "Love Poem", "IU"))
    }

    @Test
    fun `no matching artist means no page is opened`() {
        val results = listOf(
            album("Greatest Hits", "Album • Other Artist • 2020"),
            album("Greatest Hits", "Album • Third Artist • 2018"),
        )

        assertNull(chooseAlbumSearchHit(results, "Greatest Hits", "IU"))
    }

    @Test
    fun `an exact album without an artist line is accepted when it is the only one`() {
        val wanted = album("Love Poem", "IU", id = "MPREbLovePoem")

        assertEquals(wanted.item, chooseAlbumSearchHit(listOf(wanted), "Love Poem", null))
    }
}
