package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The station's own artist gets more room than everyone else. Which artist
 * that is has to come from the song the station was started for: AutoPlay
 * hands [QueueBuilder.extend] the queue plus the session's history, so its
 * last row is usually some earlier autoplay track rather than the seed.
 */
class QueueSeedArtistTest {

    private fun song(id: String, artist: String) =
        Song(videoId = id, title = "Song $id", artist = artist, thumbnailUrl = null)

    @Test
    fun `the named seed artist keeps the larger cap`() {
        val queue = listOf(song("q1", "Diljit Dosanjh"), song("q2", "Someone Else"))
        val candidates = (1..6).map { song("c$it", "Diljit Dosanjh") }

        val extra = QueueBuilder.extend(
            existing = queue,
            candidates = candidates,
            limit = 10,
            seedArtist = "Diljit Dosanjh",
        )

        assertEquals(4, extra.size)
    }

    @Test
    fun `without a name the last queued artist stands in`() {
        val queue = listOf(song("q1", "Diljit Dosanjh"))
        val candidates = (1..6).map { song("c$it", "Diljit Dosanjh") }

        assertEquals(4, QueueBuilder.extend(queue, candidates, limit = 10).size)
    }

    @Test
    fun `an unrelated last row does not hand the larger cap to the wrong artist`() {
        val queue = listOf(song("q1", "Someone Else"))
        val candidates = (1..6).map { song("c$it", "Diljit Dosanjh") }

        val extra = QueueBuilder.extend(
            existing = queue,
            candidates = candidates,
            limit = 10,
            seedArtist = "Other Seed",
        )

        assertEquals(2, extra.size)
    }
}
