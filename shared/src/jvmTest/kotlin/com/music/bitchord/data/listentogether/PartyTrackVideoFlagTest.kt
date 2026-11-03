package com.music.bitchord.data.listentogether

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video row stays a video across the party wire format. */
class PartyTrackVideoFlagTest {

    @Test
    fun `a video row stays a video after the party round trip`() {
        val track = Json.decodeFromString<PartyTrack>(
            """{"videoId":"video-1","title":"MV","artist":"Artist","isVideo":true}""",
        )

        assertTrue(track.isVideo)
        assertTrue(Json.decodeFromString<PartyTrack>(Json.encodeToString(track)).isVideo)
    }
}
