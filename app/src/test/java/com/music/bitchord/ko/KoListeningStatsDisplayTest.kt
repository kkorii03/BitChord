package com.music.bitchord.ko

import com.music.bitchord.data.stats.TrackEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A downloaded cover's listening-history row still carries the app's "(cover)"
 * marker after its video flag was lost; rebuilding it as a playable song must
 * restore the video identity the lyrics timing and cover video need.
 */
class KoListeningStatsDisplayTest {

    @Test
    fun `a saved cover row becomes a video again`() {
        val row = TrackEntry(id = "vRjfYdwM5L4", title = "Marigold (cover)", artist = "오화요")
        assertTrue(KoListeningStatsDisplay.asSong(row).isVideo)
    }

    @Test
    fun `an ordinary history row stays a song`() {
        val row = TrackEntry(id = "IU", title = "밤편지", artist = "IU")
        assertFalse(KoListeningStatsDisplay.asSong(row).isVideo)
    }

    @Test
    fun `a video row survives the history serialization round-trip`() {
        val row = Json { ignoreUnknownKeys = true }.decodeFromString(
            TrackEntry.serializer(),
            """{"id":"mv1","title":"Some MV","artist":"Channel","isVideo":true}""",
        )

        assertTrue(KoListeningStatsDisplay.asSong(row).isVideo)
    }
}
