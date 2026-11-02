package com.music.bitchord.ko

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 1.8 never drew YouTube's Shorts rail on Home; the 1.9 merge dropped the
 * filter, so the video rail came back between the music shelves.
 */
class KoHomeFeedShortsTest {

    private fun shelf(title: String) = HomeShelf(
        title = title,
        items = listOf(ShelfItem("A song", "An artist", null, "v1", null)),
    )

    @Test
    fun `the home shelves drop Shorts in every language the fork serves`() {
        val shelves = listOf(
            shelf("Shorts"),
            shelf("Shorts nổi bật"),
            shelf("숏폼"),
            shelf("ショート"),
            shelf("인기곡"),
            shelf("Recents"),
        )

        val shown = KoHomeFeed.withContentCountryChart(shelves, null)

        assertEquals(listOf("인기곡", "Recents"), shown.map { it.title })
    }

    @Test
    fun `a song title that merely starts with short is not a Shorts shelf`() {
        val shelves = listOf(shelf("Short stories"), shelf("Songs"))

        val shown = KoHomeFeed.withContentCountryChart(shelves, null)

        assertTrue(shown.any { it.title == "Short stories" })
    }
}
