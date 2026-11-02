package com.music.bitchord.ko

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phone 11-03: a cover video tapped from Recents played as a plain song — no
 * song version, and a music radio that picked nothing — because the shelf
 * dropped the row's video flag. Its title also grew "( )" on every repaint.
 */
class KoHomeFeedRecentsTest {

    private val cover = Song(
        videoId = "VlKfF_ct59c",
        title = "화조도 花鳥圖 (cover)",
        artist = "오화요",
        thumbnailUrl = null,
        isVideo = true,
    )

    @Test
    fun `a video row stays a video on the recents shelf`() {
        assertTrue(KoHomeFeed.recentsShelf(listOf(cover)).items.single().isVideo)
    }

    // Phone 11-03: "커버 및 리믹스" showed raw upload titles and channel names.
    @Test
    fun `a home video row reads as its song and singer, a song row is untouched`() {
        val shelf = HomeShelf(
            title = "커버 및 리믹스",
            items = listOf(
                ShelfItem("아이묭 - Marigold / cover by 오화요", "Ohwayo", null, "v1", null, isVideo = true),
                ShelfItem("Marigold", "Aimyon", null, "v2", null),
            ),
        )
        val (video, song) = KoHomeFeed.withContentCountryChart(listOf(shelf), null).single().items
        assertEquals("Marigold (cover)", video.title)
        assertEquals("오화요", video.subtitle)
        assertEquals("Marigold", song.title)
        assertEquals("Aimyon", song.subtitle)
    }

    // The same flag loss that hit Recents: a cover row without isVideo was
    // skipped and kept its upload title and channel on Home.
    @Test
    fun `a home cover row without its video flag still reads as its song and singer`() {
        val shelf = HomeShelf(
            title = "커버 및 리믹스",
            items = listOf(
                ShelfItem("아이묭 - Marigold / cover by 오화요", "Ohwayo", null, "v3", null),
            ),
        )
        val item = KoHomeFeed.withContentCountryChart(listOf(shelf), null).single().items.single()
        assertEquals("Marigold (cover)", item.title)
        assertEquals("오화요", item.subtitle)
    }

    @Test
    fun `repainting the shelf does not change the title`() {
        val first = KoHomeFeed.recentsShelf(listOf(cover)).items.single()
        val again = KoHomeFeed.recentsShelf(
            listOf(cover.copy(title = first.title, artist = first.subtitle)),
        ).items.single()
        assertEquals("화조도 花鳥圖 (cover)", first.title)
        assertEquals(first.title, again.title)
    }
}
