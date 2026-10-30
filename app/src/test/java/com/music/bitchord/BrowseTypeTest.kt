package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseTypeTest {

    @Test
    fun `resolves VLOLAK and OLAK album release IDs as BrowseType ALBUM`() {
        assertEquals(BrowseType.ALBUM, MainViewModel.browseTypeOf("VLOLAK5uy_kX123456789"))
        assertEquals(BrowseType.ALBUM, MainViewModel.browseTypeOf("OLAK5uy_kX123456789"))
    }

    @Test
    fun `resolves standard MPREb album IDs as BrowseType ALBUM`() {
        assertEquals(BrowseType.ALBUM, MainViewModel.browseTypeOf("MPREb_987654321"))
    }

    @Test
    fun `resolves UC artist IDs as BrowseType ARTIST`() {
        assertEquals(BrowseType.ARTIST, MainViewModel.browseTypeOf("UC1234567890abcdef"))
    }

    @Test
    fun `resolves VLPL and PL playlist IDs as BrowseType PLAYLIST`() {
        assertEquals(BrowseType.PLAYLIST, MainViewModel.browseTypeOf("VLPL1234567890"))
        assertEquals(BrowseType.PLAYLIST, MainViewModel.browseTypeOf("PL1234567890"))
        assertEquals(BrowseType.PLAYLIST, MainViewModel.browseTypeOf("${Downloads.PLAYLIST_PREFIX}local_downloads"))
    }

    @Test
    fun `preserves fallback when browse ID prefix is unknown`() {
        assertEquals(BrowseType.ALBUM, MainViewModel.browseTypeOf("unknown_id", fallback = BrowseType.ALBUM))
        assertEquals(BrowseType.ARTIST, MainViewModel.browseTypeOf("custom_source", fallback = BrowseType.ARTIST))
        assertEquals(BrowseType.OTHER, MainViewModel.browseTypeOf("unrecognized_prefix"))
    }

    @Test
    fun `an explicit artist hint wins over a release prefix`() {
        assertEquals(BrowseType.ARTIST, MainViewModel.detailTypeFor("MPREb123", BrowseType.ARTIST))
        assertEquals(BrowseType.ARTIST, MainViewModel.detailTypeFor("VLPL123", BrowseType.ARTIST))
        assertEquals(BrowseType.ARTIST, MainViewModel.detailTypeFor("PL123", BrowseType.ARTIST))
        assertEquals(BrowseType.ARTIST, MainViewModel.detailTypeFor("nonUcArtist", BrowseType.ARTIST))
    }

    @Test
    fun `a release hint still follows the browse prefix`() {
        assertEquals(BrowseType.ALBUM, MainViewModel.detailTypeFor("MPREb123", BrowseType.ALBUM))
        assertEquals(BrowseType.PLAYLIST, MainViewModel.detailTypeFor("VLPL123", BrowseType.PLAYLIST))
        assertEquals(BrowseType.ARTIST, MainViewModel.detailTypeFor("UC123", BrowseType.ALBUM))
    }
}
