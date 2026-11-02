package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.settings.LibraryViewType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The 1.9 merge kept only the lead index for Recents and ignored Quick picks
 * when YouTube sent it as cards, so a reordered feed drew the Recents pager as
 * an ordinary carousel and Quick picks as a hero. These are the 1.8 rules.
 */
class HomeShelfRowKindTest {

    private fun shelf(title: String, layout: ShelfLayout = ShelfLayout.CARDS) = HomeShelf(
        title = title,
        items = listOf(ShelfItem("A song", "An artist", null, "v1", null)),
        layout = layout,
    )

    @Test
    fun `Recents is the row pager wherever the feed puts it`() {
        assertEquals(HomeShelfKind.RECENTS, homeShelfKind(shelf("Recents"), index = 3, firstIsHero = true))
    }

    @Test
    fun `Quick picks stays a compact list when the feed omits its layout`() {
        assertEquals(HomeShelfKind.QUICK_PICKS, homeShelfKind(shelf("빠른 선곡"), index = 1, firstIsHero = true))
    }

    @Test
    fun `Listen again keeps the list layout from the feed`() {
        assertEquals(
            HomeShelfKind.LIST,
            homeShelfKind(shelf("Listen again", ShelfLayout.LIST), index = 1, firstIsHero = true),
        )
    }

    @Test
    fun `only the lead card shelf is the hero`() {
        assertEquals(HomeShelfKind.HERO, homeShelfKind(shelf("For you"), index = 0, firstIsHero = true))
        assertEquals(HomeShelfKind.SHELF, homeShelfKind(shelf("For you"), index = 1, firstIsHero = true))
    }

    @Test
    fun `the Recents switch flips both ways`() {
        assertEquals(LibraryViewType.GRID, toggledRecentsViewType(LibraryViewType.LIST))
        assertEquals(LibraryViewType.LIST, toggledRecentsViewType(LibraryViewType.GRID))
    }

    // The 1.9 merge dropped the skeleton's layout flag, so grid view loaded as
    // list rows; the skeleton now reserves the same shape the shelf draws.
    @Test
    fun `the Recents skeleton reserves the shelf's own shape`() {
        assertTrue(recentsShowsRows(LibraryViewType.LIST))
        assertFalse(recentsShowsRows(LibraryViewType.GRID))
    }

    @Test
    fun `HomeScreen hands the saved layout to the Recents skeleton`() {
        val source = File("src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt").readText()
        assertTrue(
            source.contains("recentlyPlayedSkeleton(listLayout = recentsShowsRows(recentsViewType))"),
        )
    }
}
