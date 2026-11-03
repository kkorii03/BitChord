package com.music.bitchord.data

import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Top Picks' "Fans might also like" shelf is identified by the parser's
 * artist flag, not only by the usual `UC…` channel prefix: YouTube serves
 * artist cards under other ids too (see ArtistPageVideoShelfTest).
 */
class TopPicksRelatedArtistsTest {

    private fun artist(title: String, browseId: String, isArtist: Boolean = true) =
        ShelfItem(
            title = title,
            subtitle = "Artist",
            thumbnailUrl = null,
            videoId = null,
            browseId = browseId,
            isArtist = isArtist,
        )

    private fun page(vararg sections: HomeShelf) = ArtistPage(
        songs = emptyList(),
        moreSongsBrowseId = null,
        sections = sections.toList(),
        name = "Main Artist",
    )

    @Test
    fun `an artist shelf with a non UC channel id still yields related artists`() {
        val card = TopPicks.ArtistCard(
            page(HomeShelf("Fans might also like", listOf(artist("Another Artist", "MPLAUC123")))),
            browseId = "UCmain",
        )

        assertEquals(listOf("Another Artist"), card.relatedNames)
        assertEquals("MPLAUC123", card.related["Another Artist"])
    }

    @Test
    fun `the usual UC channel id still counts without the flag`() {
        val card = TopPicks.ArtistCard(
            page(HomeShelf("Fans might also like", listOf(artist("Another Artist", "UCother", isArtist = false)))),
            browseId = "UCmain",
        )

        assertEquals(listOf("Another Artist"), card.relatedNames)
    }

    @Test
    fun `a shelf of videos is not read as related artists`() {
        val video = ShelfItem(
            title = "A Video",
            subtitle = "Uploader",
            thumbnailUrl = null,
            videoId = "video1",
            browseId = null,
            isVideo = true,
        )
        val card = TopPicks.ArtistCard(
            page(HomeShelf("Videos", listOf(video))),
            browseId = "UCmain",
        )

        assertTrue(card.relatedNames.isEmpty())
    }

    @Test
    fun `the artist themselves is not a related artist`() {
        val card = TopPicks.ArtistCard(
            page(
                HomeShelf(
                    "Fans might also like",
                    listOf(artist("Main Artist", "UCsecond"), artist("Another Artist", "UCother")),
                ),
            ),
            browseId = "UCmain",
        )

        assertEquals(listOf("Another Artist"), card.relatedNames)
    }
}
