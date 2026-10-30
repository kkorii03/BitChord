package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AutoplayCoverFilterCacheTest {

    private fun song(videoId: String, title: String, artist: String, album: String? = null) =
        Song(videoId = videoId, title = title, artist = artist, thumbnailUrl = null, albumName = album)

    private val seed = song("seed", "Wannabe", "Spice Girls")

    @Test
    fun `the same station is filtered once and reused`() {
        val radio = listOf(
            song("a", "Wannabe", "Spice Girls"),
            song("b", "Wannabe (Karaoke Version)", "Sing King"),
            song("c", "Wannabe - Cover", "Some Singer"),
        )

        val first = coverFilteredRadio(seed, radio)
        val second = coverFilteredRadio(seed, radio)

        assertEquals(listOf("a"), first.map { it.videoId })
        assertSame("a second settings toggle must not rescan the same rows", first, second)
    }

    @Test
    fun `a changed station is filtered on its own`() {
        val first = coverFilteredRadio(seed, listOf(song("a", "Wannabe", "Spice Girls")))
        val second = coverFilteredRadio(
            seed,
            listOf(
                song("a", "Wannabe", "Spice Girls"),
                song("d", "Tribute", "Tribute Band"),
            ),
        )

        assertEquals(listOf("a"), first.map { it.videoId })
        assertEquals(listOf("a"), second.map { it.videoId })
    }

    @Test
    fun `a seed album change is not served from the previous filter result`() {
        // The marker "karaoke" in the seed's own album means the row is the
        // recording, not an arrangement; without an album it is an arrangement.
        val karaokeAlbumSeed = seed.copy(albumName = "Karaoke Hits")
        val ordinaryAlbumSeed = seed.copy(albumName = "Spice")
        val radio = listOf(song("b", "Wannabe", "Spice Girls", album = "Karaoke Version"))

        assertEquals(
            listOf("b"),
            coverFilteredRadio(karaokeAlbumSeed, radio).map { it.videoId },
        )
        assertEquals(
            "same videoId/title/artist but a different album must scan again",
            emptyList<String>(),
            coverFilteredRadio(ordinaryAlbumSeed, radio).map { it.videoId },
        )
    }
}
