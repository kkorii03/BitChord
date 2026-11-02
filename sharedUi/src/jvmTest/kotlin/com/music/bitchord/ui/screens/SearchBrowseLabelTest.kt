package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.ko.ui.koBrowseFallbackLabel
import com.music.bitchord.ko.ui.koSearchFilterLabel
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.album
import com.music.bitchord.sharedui.resources.albums
import com.music.bitchord.sharedui.resources.artist
import com.music.bitchord.sharedui.resources.artists
import com.music.bitchord.sharedui.resources.playlist
import com.music.bitchord.sharedui.resources.playlists
import com.music.bitchord.sharedui.resources.search_filter_all
import com.music.bitchord.sharedui.resources.search_filter_episodes
import com.music.bitchord.sharedui.resources.search_filter_videos
import com.music.bitchord.sharedui.resources.song
import com.music.bitchord.sharedui.resources.songs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBrowseLabelTest {

    @Test
    fun `a blank subtitle names the result kind in the app language`() {
        assertEquals(Res.string.album, koBrowseFallbackLabel(BrowseType.ALBUM))
        assertEquals(Res.string.artist, koBrowseFallbackLabel(BrowseType.ARTIST))
        assertEquals(Res.string.playlist, koBrowseFallbackLabel(BrowseType.PLAYLIST))
        assertEquals(Res.string.song, koBrowseFallbackLabel(BrowseType.OTHER))
    }

    @Test
    fun `result tabs use localized labels instead of the enum's Korean text`() {
        assertEquals(Res.string.search_filter_all, SearchFilter.ALL.koSearchFilterLabel())
        assertEquals(Res.string.songs, SearchFilter.SONGS.koSearchFilterLabel())
        assertEquals(Res.string.search_filter_videos, SearchFilter.VIDEOS.koSearchFilterLabel())
        assertEquals(Res.string.search_filter_episodes, SearchFilter.EPISODES.koSearchFilterLabel())
        assertEquals(Res.string.albums, SearchFilter.ALBUMS.koSearchFilterLabel())
        assertEquals(Res.string.artists, SearchFilter.ARTISTS.koSearchFilterLabel())
        assertEquals(Res.string.playlists, SearchFilter.PLAYLISTS.koSearchFilterLabel())
    }

    @Test
    fun `the filter pills are wired to the localized label helper`() {
        val source = searchScreenSource().readText()
        assertTrue(
            "SearchScreen must render its filter pills from koSearchFilterLabel()",
            source.contains("koSearchFilterLabel"),
        )
        assertFalse(
            "SearchScreen must not fall back to the enum's hardcoded labels",
            source.contains("SearchFilter.entries.map { it.label }"),
        )
    }

    private fun searchScreenSource(): File =
        listOf(
            "src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt",
            "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt",
            "../sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt",
        ).map(::File).firstOrNull(File::exists)
            .also { assertTrue("SearchScreen.kt must be reachable from the test working directory", it != null) }!!
}
