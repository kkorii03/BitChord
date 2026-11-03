package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.PartyTrack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video row shared through a party must come back as a video. */
class PartyVideoRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a party video row round-trips through the wire format`() {
        val track = json.decodeFromString(
            PartyTrack.serializer(),
            """{"videoId":"mv1","title":"Some MV","artist":"Channel","isVideo":true}""",
        )

        assertTrue(track.toSong().isVideo)
    }

    @Test
    fun `an ordinary party row stays a song`() {
        val track = PartyTrack(videoId = "s1", title = "Song", artist = "Artist")

        assertFalse(track.toSong().isVideo)
    }
}
