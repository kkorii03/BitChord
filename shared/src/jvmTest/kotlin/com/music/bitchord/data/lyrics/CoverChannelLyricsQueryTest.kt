package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A cover channel's upload whose title carries no "Cover" word. */
class CoverChannelLyricsQueryTest {

    private val channels = CoverTitles.designatedChannels

    @AfterTest
    fun restore() {
        CoverTitles.designatedChannels = channels
    }

    private fun song(title: String, channelId: String = "cover-channel") = Song(
        videoId = "video",
        title = title,
        artist = "Known Cover Channel",
        artistId = channelId,
        thumbnailUrl = null,
        isVideo = true,
    )

    @Test
    fun aCoverChannelTitleNamesTheOriginalArtistForLyrics() {
        CoverTitles.designatedChannels = { setOf("cover-channel") }
        val query = CoverTitles.lyricsQuery(song("Some Song - Original Artist"))
        assertTrue(query.alternatives.contains("Some Song" to "Original Artist"), "${query.alternatives}")
        assertTrue(query.alternatives.contains("Original Artist" to "Some Song"), "${query.alternatives}")
    }

    @Test
    fun anOrdinaryChannelKeepsTheTitleAsWritten() {
        CoverTitles.designatedChannels = { setOf("another-channel") }
        val query = CoverTitles.lyricsQuery(song("Some Song - Original Artist"))
        assertFalse(query.alternatives.contains("Some Song" to "Original Artist"))
    }
}
