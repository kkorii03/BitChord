package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeShelfMergeTest {

    private fun shelf(title: String, vararg ids: String) = HomeShelf(
        title = title,
        items = ids.map { id ->
            ShelfItem(title = id, subtitle = "", thumbnailUrl = null, videoId = id, browseId = null)
        },
    )

    @Test
    fun `a failed or empty fetch keeps the whole previous feed`() {
        val previous = listOf(
            shelf("나를 위한 추천", "a"),
            shelf("최근 재생", "b"),
            shelf("Liked music", "c"),
        )
        val merge = mergeHomeShelves(previous, emptyList())
        assertEquals(previous, merge.shelves)
        assertEquals(listOf("나를 위한 추천", "최근 재생", "Liked music"), merge.kept)
    }

    @Test
    fun `shelves the fetch did not return are kept after the fresh ones`() {
        val previous = listOf(
            shelf("나를 위한 추천", "a"),
            shelf("최근 재생", "b"),
            shelf("Liked music", "c"),
        )
        val fresh = listOf(shelf("나를 위한 추천", "a", "b"), shelf("Liked music", "c"))
        val merge = mergeHomeShelves(previous, fresh)
        assertEquals(listOf("나를 위한 추천", "Liked music", "최근 재생"), merge.shelves.map { it.title })
        assertEquals(listOf("최근 재생"), merge.kept)
        assertEquals(fresh[0], merge.shelves[0])
    }

    @Test
    fun `an empty answer does not replace a populated shelf with the same title`() {
        val previous = listOf(shelf("나를 위한 추천", "a", "b"))
        val merge = mergeHomeShelves(previous, listOf(shelf("나를 위한 추천")))
        assertEquals(previous, merge.shelves)
        assertEquals(listOf("나를 위한 추천"), merge.kept)
    }

    @Test
    fun `titles match ignoring case and surrounding whitespace`() {
        val previous = listOf(shelf("Liked music", "a"))
        val merge = mergeHomeShelves(previous, listOf(shelf("  LIKED MUSIC ", "b")))
        assertEquals(listOf("  LIKED MUSIC "), merge.shelves.map { it.title })
        assertEquals(emptyList<String>(), merge.kept)
    }

    @Test
    fun `fresh order is kept and a repeated fresh title appears once`() {
        val previous = listOf(shelf("Recents", "a"))
        val fresh = listOf(shelf("Second", "b"), shelf("First", "c"), shelf("Second", "d"))
        val merge = mergeHomeShelves(previous, fresh)
        assertEquals(listOf("Second", "First", "Recents"), merge.shelves.map { it.title })
        assertEquals(listOf("Recents"), merge.kept)
    }
}
