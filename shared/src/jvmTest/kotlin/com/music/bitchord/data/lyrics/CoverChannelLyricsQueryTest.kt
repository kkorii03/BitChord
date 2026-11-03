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
        // Which side is the song is not written down: the primary query and
        // the alternatives together try both orientations.
        val pairs = listOf(query.title to query.artist) + query.alternatives
        assertTrue(pairs.contains("Some Song" to "Original Artist"), "$pairs")
        assertTrue(pairs.contains("Original Artist" to "Some Song"), "$pairs")
    }

    @Test
    fun anOrdinaryChannelKeepsTheTitleAsWritten() {
        CoverTitles.designatedChannels = { setOf("another-channel") }
        val query = CoverTitles.lyricsQuery(song("Some Song - Original Artist"))
        assertFalse(query.alternatives.contains("Some Song" to "Original Artist"))
    }
}
