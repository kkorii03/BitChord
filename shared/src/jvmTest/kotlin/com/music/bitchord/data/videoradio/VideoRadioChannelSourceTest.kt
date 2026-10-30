package com.music.bitchord.data.videoradio

import com.music.bitchord.data.YouTubeArtistResolver
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRadioChannelSourceTest {

    @After
    fun reset() = VideoRadioSources.resetForTest()

    private fun rows(vararg ids: String): List<Song> = ids.map { id ->
        Song(videoId = id, title = "Song $id", artist = "Uploader", thumbnailUrl = null)
    }

    @Test
    fun `an explicit channel id is used without searching the uploader name`() = runBlocking {
        var searched = false
        var browsed: String? = null
        VideoRadioSources.mixLookup = { emptyList() }
        VideoRadioSources.videoChannelLookup = { null }
        VideoRadioSources.artistResolver = { searched = true; YouTubeArtistResolver.Hit("UCwrong", null) }
        VideoRadioSources.channelBrowse = { id, _ -> browsed = id; JsonObject(emptyMap()) }
        VideoRadioSources.channelParser = { rows("same-name-1") }

        val result = VideoRadioSources.collect("seed", "Same Name", "UCright")

        assertEquals("UCright", browsed)
        assertFalse(searched)
        assertEquals(listOf("same-name-1"), result.songs.map { it.videoId })
        assertEquals(VideoSource.CHANNEL, result.sources["same-name-1"])
    }

    @Test
    fun `the player response supplies the channel id when the song does not`() = runBlocking {
        var browsed: String? = null
        VideoRadioSources.mixLookup = { emptyList() }
        VideoRadioSources.videoChannelLookup = { "UCfromplayer" }
        VideoRadioSources.artistResolver = { error("name search must not run") }
        VideoRadioSources.channelBrowse = { id, _ -> browsed = id; JsonObject(emptyMap()) }
        VideoRadioSources.channelParser = { rows("player-1") }

        VideoRadioSources.collect("seed", "Same Name")

        assertEquals("UCfromplayer", browsed)
    }

    @Test
    fun `the name is searched only when neither the song nor the player names a channel`() = runBlocking {
        var searched: String? = null
        var browsed: String? = null
        VideoRadioSources.mixLookup = { emptyList() }
        VideoRadioSources.videoChannelLookup = { null }
        VideoRadioSources.artistResolver = { name ->
            searched = name
            YouTubeArtistResolver.Hit("UCbyname", null)
        }
        VideoRadioSources.channelBrowse = { id, _ -> browsed = id; JsonObject(emptyMap()) }
        VideoRadioSources.channelParser = { rows("name-1") }

        VideoRadioSources.collect("seed", "Uploader Name")

        assertEquals("Uploader Name", searched)
        assertEquals("UCbyname", browsed)
    }

    @Test
    fun `a failed RD mix still returns the channel rows`() = runBlocking {
        VideoRadioSources.mixLookup = { error("RD mix unavailable") }
        VideoRadioSources.videoChannelLookup = { "UCchannel" }
        VideoRadioSources.channelBrowse = { _, _ -> JsonObject(emptyMap()) }
        VideoRadioSources.channelParser = { rows("channel-1") }

        val result = VideoRadioSources.collect("seed", "Uploader", "UCchannel")

        assertEquals(listOf("channel-1"), result.songs.map { it.videoId })
        assertEquals(VideoSource.CHANNEL, result.sources["channel-1"])
        assertTrue(result.sources.isNotEmpty())
    }
}
