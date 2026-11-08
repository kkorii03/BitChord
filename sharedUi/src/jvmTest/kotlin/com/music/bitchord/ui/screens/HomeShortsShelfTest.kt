package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.HomeShelf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Home never draws YouTube's Shorts rail (a video feed Apple Music has no
 * counterpart for). The v1.9 merge kept the matcher but dropped the filter,
 * so the rail came back on the phone.
 */
class HomeShortsShelfTest {

    @Test
    fun `the Shorts rail is left out and every other shelf kept in order`() {
        val shelves = listOf("Quick picks", "Shorts", "숏츠", "Listen again", "ショート", "Short stories")
            .map { HomeShelf(title = it, items = emptyList()) }

        assertEquals(
            listOf("Quick picks", "Listen again", "Short stories"),
            visibleHomeShelves(shelves).map { it.title },
        )
    }
}
