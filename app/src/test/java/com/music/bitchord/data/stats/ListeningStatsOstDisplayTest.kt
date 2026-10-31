package com.music.bitchord.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Recents/local history read path uses the same display cleaner. */
class ListeningStatsOstDisplayTest {

    @Test
    fun `a stored recent soundtrack row reads as its song and performer`() {
        val entry = TrackEntry(
            id = "joker-1",
            title = "Gary Glitter - Rock & Roll Part II | Joker OST - Original Soundtrack",
            artist = "Original Soundtrack",
        )

        val song = entry.asSong()

        assertEquals("Rock & Roll Part II", song.title)
        assertEquals("Gary Glitter", song.artist)
        assertEquals("Joker OST", song.albumName)
    }
}
