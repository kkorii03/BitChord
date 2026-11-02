package com.music.bitchord.data

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The curated category rules: YouTube Music's own editorial playlists, the
 * top playlist's songs, and the community shelf that follows them.
 */
class YouTubeCategoryRulesTest {

    private fun song(title: String, duration: String?) =
        Song(videoId = "v", title = title, artist = "Artist", thumbnailUrl = null, durationText = duration)

    private fun playlist(id: String, owner: String = "YouTube Music") = ShelfItem(
        title = "Playlist $id",
        subtitle = owner,
        thumbnailUrl = null,
        videoId = null,
        browseId = "VL$id",
    )

    private fun songItem(id: String) = ShelfItem(
        title = "Song $id",
        subtitle = "Artist",
        thumbnailUrl = null,
        videoId = id,
        browseId = null,
    )

    @Test
    fun `a category searches its own Korean term`() {
        assertEquals("K-pop", YouTubeCategoryRules.searchTerm(ExploreCategories.find("ytq:kpop")!!))
        assertEquals("운동", YouTubeCategoryRules.searchTerm(ExploreCategories.find("ytq:workout")!!))
        assertEquals("편안한", YouTubeCategoryRules.searchTerm(ExploreCategories.find("ytq:chill")!!))
        assertEquals("잠잘 때", YouTubeCategoryRules.searchTerm(ExploreCategories.find("ytq:sleep")!!))
    }

    @Test
    fun `only YouTube Music RDCLAK playlists are official`() {
        assertTrue(
            YouTubeCategoryRules.isOfficialPlaylist(
                playlist("RDCLAK5uy_official", owner = "YouTube Music"),
            ),
        )
        assertTrue(
            YouTubeCategoryRules.isOfficialPlaylist(
                playlist("RDCLAK5uy_mixed", owner = "Playlist • YouTube Music"),
            ),
        )
        assertFalse(
            YouTubeCategoryRules.isOfficialPlaylist(
                playlist("PLcommunity", owner = "YouTube Music"),
            ),
        )
        assertFalse(
            YouTubeCategoryRules.isOfficialPlaylist(
                playlist("RDCLAK5uy_uploader", owner = "Some Channel"),
            ),
        )
        assertEquals(
            listOf("VLRDCLAK5uy_official"),
            YouTubeCategoryRules.officialPlaylists(
                listOf(
                    playlist("RDCLAK5uy_official"),
                    playlist("PLcommunity", owner = "Some Channel"),
                ),
            ).map { it.browseId },
        )
    }

    @Test
    fun `compilations over ten minutes are dropped from the songs shelf`() {
        assertTrue(YouTubeCategoryRules.isOfficialTrack(song("밤편지", "4:20")))
        assertTrue(YouTubeCategoryRules.isOfficialTrack(song("밤편지", "10:00")))
        assertTrue(YouTubeCategoryRules.isOfficialTrack(song("밤편지", null)))

        assertFalse(YouTubeCategoryRules.isOfficialTrack(song("밤편지", "10:01")))
        assertFalse(YouTubeCategoryRules.isOfficialTrack(song("밤편지", "1:05:00")))
        assertFalse(YouTubeCategoryRules.isOfficialTrack(song("1시간 발라드 모음", "3:30")))
        assertFalse(YouTubeCategoryRules.isOfficialTrack(song("[PLAYLIST] Best Ballads", "3:30")))
        assertFalse(YouTubeCategoryRules.isOfficialTrack(song("Ballad Mix", "3:30")))
    }

    @Test
    fun `shelves keep YouTube Music's playlist then songs then community order`() {
        val shelves = YouTubeCategoryRules.categoryShelves(
            official = listOf(playlist("RDCLAK5uy_a")),
            songs = listOf(songItem("v1"), songItem("v2")),
            community = listOf(playlist("PLc1", owner = "Channel"), playlist("PLc2", owner = "Channel")),
            limitPerShelf = 10,
        )

        assertEquals(
            listOf(
                YouTubeCategoryRules.OFFICIAL_SHELF_TITLE,
                YouTubeCategoryRules.SONGS_SHELF_TITLE,
                YouTubeCategoryRules.COMMUNITY_SHELF_TITLE,
            ),
            shelves.map { it.title },
        )
        assertEquals(listOf("VLRDCLAK5uy_a"), shelves[0].items.map { it.browseId })
        assertEquals(listOf("v1", "v2"), shelves[1].items.map { it.videoId })
        assertEquals(listOf("VLPLc1", "VLPLc2"), shelves[2].items.map { it.browseId })
    }

    @Test
    fun `a playlist already in the official shelf is not repeated in community`() {
        val shared = playlist("RDCLAK5uy_a")
        val shelves = YouTubeCategoryRules.categoryShelves(
            official = listOf(shared),
            songs = emptyList(),
            community = listOf(shared, playlist("PLc1", owner = "Channel")),
            limitPerShelf = 10,
        )

        assertEquals(
            listOf(
                YouTubeCategoryRules.OFFICIAL_SHELF_TITLE,
                YouTubeCategoryRules.COMMUNITY_SHELF_TITLE,
            ),
            shelves.map { it.title },
        )
        assertEquals(listOf("VLPLc1"), shelves[1].items.map { it.browseId })
    }

    @Test
    fun `empty shelves are omitted rather than drawn as headings`() {
        val shelves = YouTubeCategoryRules.categoryShelves(
            official = emptyList(),
            songs = listOf(songItem("v1")),
            community = emptyList(),
            limitPerShelf = 10,
        )

        assertEquals(listOf(YouTubeCategoryRules.SONGS_SHELF_TITLE), shelves.map { it.title })
    }
}
