package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
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
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchBrowseLabelTest {

    @Test
    fun `a blank subtitle names the result kind in the app language`() {
        assertEquals(Res.string.album, browseFallbackLabel(BrowseType.ALBUM))
        assertEquals(Res.string.artist, browseFallbackLabel(BrowseType.ARTIST))
        assertEquals(Res.string.playlist, browseFallbackLabel(BrowseType.PLAYLIST))
        assertEquals(Res.string.song, browseFallbackLabel(BrowseType.OTHER))
    }

    @Test
    fun `result tabs use localized labels instead of the enum's Korean text`() {
        assertEquals(Res.string.search_filter_all, SearchFilter.ALL.labelResource())
        assertEquals(Res.string.songs, SearchFilter.SONGS.labelResource())
        assertEquals(Res.string.search_filter_videos, SearchFilter.VIDEOS.labelResource())
        assertEquals(Res.string.albums, SearchFilter.ALBUMS.labelResource())
        assertEquals(Res.string.artists, SearchFilter.ARTISTS.labelResource())
        assertEquals(Res.string.playlists, SearchFilter.PLAYLISTS.labelResource())
    }
}
