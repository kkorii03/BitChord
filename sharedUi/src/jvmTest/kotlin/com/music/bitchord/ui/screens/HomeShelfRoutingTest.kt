package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.HomeShelf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home's own shelf names: the Shorts rail never draws, Quick picks keeps its
 * compact pager, and Recents is recognised wherever the feed puts it.
 */
class HomeShelfRoutingTest {

    private fun shelf(title: String) = HomeShelf(title = title, items = emptyList())

    @Test
    fun `the shorts rail is never drawn`() {
        val shelves = listOf(
            shelf("Recents"),
            shelf("Shorts"),
            shelf("숏츠"),
            shelf("ショート"),
            shelf("Quick picks"),
        )

        assertEquals(
            listOf("Recents", "Quick picks"),
            visibleHomeShelves(shelves).map { it.title },
        )
    }

    @Test
    fun `quick picks is recognised in the fork's languages`() {
        assertTrue(isQuickPicksShelf(shelf("Quick picks")))
        assertTrue(isQuickPicksShelf(shelf("빠른 선곡")))
        assertTrue(isQuickPicksShelf(shelf("クイック ミックス")))
    }

    @Test
    fun `recents is recognised at any index`() {
        assertTrue(isRecentsShelf(shelf("Recents")))
        assertTrue(isRecentsShelf(shelf("  recents  ")))
    }

    @Test
    fun `listen again is recognised in the fork's languages`() {
        assertTrue(isListenAgainShelf("Listen again"))
        assertTrue(isListenAgainShelf("다시 듣기"))
        assertTrue(isListenAgainShelf("もう一度聴く"))
        assertTrue(isListenAgainShelf("nghe lại"))
    }
}
