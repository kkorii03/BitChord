package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 1031 r3 follow-up: the work name before a named soundtrack label is
 * context, not a take, and neutral packaging after a pipe label is not a take.
 */
class SongVersionNamedOstPrefixTest {

    @Test
    fun `a work word before a named OST label is context not a take`() {
        val korean = TrackMatcher.parseTitle("Dynamite - Korean OST Drama")
        assertEquals("dynamite", korean.core)
        assertEquals(emptySet<String>(), korean.versions)

        val live = TrackMatcher.parseTitle("Dynamite - Live OST Korean Drama")
        assertEquals("dynamite", live.core)
        assertEquals(emptySet<String>(), live.versions)

        val pipe = TrackMatcher.parseTitle("Dynamite | Korean OST Drama")
        assertEquals("dynamite", pipe.core)
        assertEquals(emptySet<String>(), pipe.versions)
    }

    @Test
    fun `a work word before a named OST label still matches the plain recording`() {
        val original = Song(
            videoId = "studio-song",
            title = "Dynamite",
            artist = "Artist",
            thumbnailUrl = null,
        )
        assertTrue(
            TrackMatcher.sameRecording(
                original,
                TrackMatcher.Target("Dynamite - Korean OST Drama", "Artist"),
            ),
        )
    }

    @Test
    fun `neutral packaging after a pipe OST label is not a take`() {
        val radio = TrackMatcher.parseTitle("Dynamite | Joker OST Radio Edit")
        assertEquals("dynamite", radio.core)
        assertEquals(emptySet<String>(), radio.versions)

        val mix = TrackMatcher.parseTitle("Dynamite | Joker OST Original Mix")
        assertEquals("dynamite", mix.core)
        assertEquals(emptySet<String>(), mix.versions)
    }

    @Test
    fun `neutral packaging after a pipe OST label still matches the plain recording`() {
        val original = Song(
            videoId = "studio-song",
            title = "Dynamite",
            artist = "Artist",
            thumbnailUrl = null,
        )
        assertTrue(
            TrackMatcher.sameRecording(
                original,
                TrackMatcher.Target("Dynamite | Joker OST Radio Edit", "Artist"),
            ),
        )
    }
}
