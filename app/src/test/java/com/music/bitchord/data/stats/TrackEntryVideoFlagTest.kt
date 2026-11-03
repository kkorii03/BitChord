package com.music.bitchord.data.stats

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video row stays a video through the listening store and back to Recents. */
class TrackEntryVideoFlagTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a video row stays a video after the listening bucket round trip`() {
        val entry = json.decodeFromString<TrackEntry>(
            """{"id":"video-1","title":"MV","artist":"Artist","isVideo":true}""",
        )

        assertTrue(entry.asSong().isVideo)
        assertTrue(json.decodeFromString<TrackEntry>(json.encodeToString(entry)).asSong().isVideo)
    }

    @Test
    fun `an old cover row without the flag is still a video`() {
        val entry = json.decodeFromString<TrackEntry>(
            """{"id":"cover-1","title":"아이묭 - Marigold / cover by 오화요","artist":"Ohwayo"}""",
        )

        assertTrue(entry.asSong().isVideo)
    }
}
