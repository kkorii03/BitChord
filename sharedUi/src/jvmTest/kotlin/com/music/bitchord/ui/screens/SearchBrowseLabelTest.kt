package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.ko.ui.koBrowseFallbackLabel
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.album
import com.music.bitchord.sharedui.resources.albums
import com.music.bitchord.sharedui.resources.artist
import com.music.bitchord.sharedui.resources.artists
import com.music.bitchord.sharedui.resources.playlist
import com.music.bitchord.sharedui.resources.playlists
import com.music.bitchord.sharedui.resources.search_filter_all
import com.music.bitchord.sharedui.resources.search_filter_videos
import com.music.bitchord.sharedui.resources.song
import com.music.bitchord.sharedui.resources.songs
import org.jetbrains.compose.resources.StringResource
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
        assertEquals(Res.string.search_filter_all, SearchFilter.ALL.filterTabLabel())
        assertEquals(Res.string.songs, SearchFilter.SONGS.filterTabLabel())
        assertEquals(Res.string.search_filter_videos, SearchFilter.VIDEOS.filterTabLabel())
        assertEquals(Res.string.albums, SearchFilter.ALBUMS.filterTabLabel())
        assertEquals(Res.string.artists, SearchFilter.ARTISTS.filterTabLabel())
        assertEquals(Res.string.playlists, SearchFilter.PLAYLISTS.filterTabLabel())
    }
}

private fun SearchFilter.filterTabLabel(): StringResource = when (this) {
    SearchFilter.ALL -> Res.string.search_filter_all
    SearchFilter.SONGS -> Res.string.songs
    SearchFilter.VIDEOS -> Res.string.search_filter_videos
    SearchFilter.ALBUMS -> Res.string.albums
    SearchFilter.ARTISTS -> Res.string.artists
    SearchFilter.PLAYLISTS -> Res.string.playlists
}
