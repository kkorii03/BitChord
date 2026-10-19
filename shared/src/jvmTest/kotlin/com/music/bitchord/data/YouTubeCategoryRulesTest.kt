package com.music.bitchord.data

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The curated category rules: three distinct searches, playlist de-duplication
 * across their shelves, and the single-song filter that keeps compilations out.
 */
class YouTubeCategoryRulesTest {

    private fun song(title: String, duration: String?) =
        Song(videoId = "v", title = title, artist = "Artist", thumbnailUrl = null, durationText = duration)

    private fun playlist(id: String) = ShelfItem(
        title = "Playlist $id",
        subtitle = "Channel",
        thumbnailUrl = null,
        videoId = null,
        browseId = "VL$id",
    )

    @Test
    fun `a category asks three different ways`() {
        val category = ExploreCategories.find("ytq:kpop")!!

        assertEquals(
            listOf("K-pop 플레이리스트", "K-pop 노래 모음", "K-pop songs playlist"),
            YouTubeCategoryRules.playlistQueries(category),
        )
        assertEquals("K-pop", YouTubeCategoryRules.songQuery(category))
    }

    @Test
    fun `playlist shelves drop an item an earlier shelf already showed`() {
        val shelves = YouTubeCategoryRules.playlistShelves(
            groups = listOf(
                listOf(playlist("a"), playlist("b")),
                listOf(playlist("b"), playlist("c")),
                listOf(playlist("a"), playlist("d")),
            ),
            limitPerShelf = 10,
        )

        assertEquals(
            listOf("추천 재생목록", "인기 재생목록", "더 보기"),
            shelves.map { it.title },
        )
        assertEquals(
            listOf("VLa", "VLb"),
            shelves[0].items.map { it.browseId },
        )
        assertEquals(listOf("VLc"), shelves[1].items.map { it.browseId })
        assertEquals(listOf("VLd"), shelves[2].items.map { it.browseId })
    }

    @Test
    fun `a shelf that is entirely duplicate is omitted`() {
        val shelves = YouTubeCategoryRules.playlistShelves(
            groups = listOf(
                listOf(playlist("a")),
                listOf(playlist("a")),
            ),
            limitPerShelf = 10,
        )

        assertEquals(listOf("추천 재생목록"), shelves.map { it.title })
    }

    @Test
    fun `only single songs survive the song shelf filter`() {
        assertTrue(YouTubeCategoryRules.isSingleSong(song("밤편지", "4:20")))
        assertTrue(YouTubeCategoryRules.isSingleSong(song("밤편지", "1:30")))
        assertTrue(YouTubeCategoryRules.isSingleSong(song("밤편지", "8:00")))
        assertTrue(YouTubeCategoryRules.isSingleSong(song("밤편지", null)))

        assertFalse(YouTubeCategoryRules.isSingleSong(song("밤편지", "1:29")))
        assertFalse(YouTubeCategoryRules.isSingleSong(song("밤편지", "8:01")))
        assertFalse(YouTubeCategoryRules.isSingleSong(song("1시간 발라드 모음", "3:30")))
        assertFalse(YouTubeCategoryRules.isSingleSong(song("[PLAYLIST] Best Ballads", "3:30")))
        assertFalse(YouTubeCategoryRules.isSingleSong(song("발라드 노래 모음", "3:30")))
        assertFalse(YouTubeCategoryRules.isSingleSong(song("Ballad Mix", "3:30")))
        assertFalse(YouTubeCategoryRules.isSingleSong(song("Best of 2024 compilation", "3:30")))
    }
}
