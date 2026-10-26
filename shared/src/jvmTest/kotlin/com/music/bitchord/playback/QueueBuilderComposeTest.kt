package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The station build's de-duplication and caps, kept in the shared test set so
 * the queue composition rule runs on CI with the other pure playback tests.
 */
class QueueBuilderComposeTest {

    private fun song(id: String, title: String, artist: String = "Arijit Singh") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    private fun video(id: String, title: String, artist: String = "Arijit Singh") =
        song(id, title, artist).copy(isVideo = true)

    @Test
    fun `a queued recording is not added again under its video id`() {
        val seed = song("seed", "Kesariya")
        val extra = QueueBuilder.extend(
            existing = listOf(seed),
            candidates = listOf(
                seed,
                video("video", "Kesariya (Official Video)"),
                song("other", "Tum Hi Ho"),
            ),
            limit = 10,
        )

        assertEquals(listOf("other"), extra.map { it.videoId })
    }

    @Test
    fun `duplicates inside one candidate batch are added once`() {
        val extra = QueueBuilder.extend(
            existing = emptyList(),
            candidates = listOf(
                song("one", "Channa Mereya"),
                song("two", "Channa Mereya"),
                song("three", "Ae Dil Hai Mushkil"),
            ),
            limit = 10,
        )

        assertEquals(listOf("one", "three"), extra.map { it.videoId })
    }

    @Test
    fun `a live take of a queued song is left out`() {
        val extra = QueueBuilder.extend(
            existing = listOf(song("seed", "Helena", "My Chemical Romance")),
            candidates = listOf(
                song("live", "Helena (Live for BBC Radio 1)", "My Chemical Romance"),
                song("next", "Heaven Help Us", "My Chemical Romance"),
            ),
            limit = 10,
        )

        assertEquals(listOf("next"), extra.map { it.videoId })
    }

    @Test
    fun `a remix is a different recording but the same song`() {
        val original = song("one", "Kesariya")
        val remix = song("two", "Kesariya (Remix)")

        assertFalse(QueueBuilder.isSameRecording(original, remix))
        assertTrue(QueueBuilder.isVersionOf(original, remix))
    }

    @Test
    fun `the per-artist and seed-artist caps still bound the batch`() {
        val seed = song("seed", "Seed", "Diljit Dosanjh")
        val sameArtist = (1..6).map { song("d$it", "Song $it", "Diljit Dosanjh") }
        val others = (1..6).map { song("o$it", "Other $it", "Other Artist") }

        val extra = QueueBuilder.extend(
            existing = listOf(seed),
            candidates = sameArtist + others,
            limit = 20,
            seedArtist = "Diljit Dosanjh",
        )

        assertEquals(4, extra.count { it.artist == "Diljit Dosanjh" })
        assertEquals(2, extra.count { it.artist == "Other Artist" })
    }

    @Test
    fun `the batch never exceeds its limit`() {
        val candidates = (1..10).map { song("v$it", "Song $it", "Artist $it") }
        assertEquals(3, QueueBuilder.extend(emptyList(), candidates, limit = 3).size)
    }
}
