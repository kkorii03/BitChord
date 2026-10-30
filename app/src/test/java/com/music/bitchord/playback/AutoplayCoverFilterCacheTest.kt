package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AutoplayCoverFilterCacheTest {

    private fun song(videoId: String, title: String, artist: String) =
        Song(videoId = videoId, title = title, artist = artist, thumbnailUrl = null)

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
}
