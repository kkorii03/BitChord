package com.music.bitchord.ko

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
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
        assertEquals("Marigold", video.title)
        assertEquals("아이묭 · 오화요 커버", video.subtitle)
        assertEquals("Marigold", song.title)
        assertEquals("Aimyon", song.subtitle)
    }

    @Test
    fun `repainting the shelf does not change the title`() {
        val first = KoHomeFeed.recentsShelf(listOf(cover)).items.single()
        val again = KoHomeFeed.recentsShelf(
            listOf(cover.copy(title = first.title, artist = first.subtitle)),
        ).items.single()
        assertEquals("화조도 花鳥圖", first.title)
        assertEquals(first.title, again.title)
    }

    // Review F2a-2: every continuation page asked (and waited) for the chart.
    @Test
    fun `a page with no chart shelf never asks for the chart`() = runBlocking {
        val chart = HomeShelf(
            title = "chart",
            items = listOf(ShelfItem("Chart song", "Singer", null, "c1", null)),
        )
        var asks = 0
        val plain = HomeShelf(title = "새 앨범", items = listOf(ShelfItem("Song", "Artist", null, "v1", null)))
        val untouched = KoHomeFeed.contentCountryCharts(listOf(plain)) { asks++; chart }
        assertEquals(0, asks)
        assertEquals(listOf(plain), untouched)

        val trending = HomeShelf(title = "인기곡", items = listOf(ShelfItem("Old", "Artist", null, "v2", null)))
        val filled = KoHomeFeed.contentCountryCharts(listOf(plain, trending)) { asks++; chart }
        assertEquals(1, asks)
        assertEquals(chart.items, filled[1].items)
        assertEquals(plain, filled[0])
    }
}
