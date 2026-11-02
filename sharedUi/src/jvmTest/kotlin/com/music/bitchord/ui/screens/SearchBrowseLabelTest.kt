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
import org.junit.Assert.assertEquals
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
        assertEquals(Res.string.search_filter_all, koSearchFilterLabel(SearchFilter.ALL))
        assertEquals(Res.string.songs, koSearchFilterLabel(SearchFilter.SONGS))
        assertEquals(Res.string.search_filter_videos, koSearchFilterLabel(SearchFilter.VIDEOS))
        assertEquals(Res.string.search_filter_episodes, koSearchFilterLabel(SearchFilter.EPISODES))
        assertEquals(Res.string.albums, koSearchFilterLabel(SearchFilter.ALBUMS))
        assertEquals(Res.string.artists, koSearchFilterLabel(SearchFilter.ARTISTS))
        assertEquals(Res.string.playlists, koSearchFilterLabel(SearchFilter.PLAYLISTS))
    }
}
