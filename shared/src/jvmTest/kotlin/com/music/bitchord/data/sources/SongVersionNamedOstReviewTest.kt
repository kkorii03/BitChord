package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Review 1031 r3 HIGH-1 follow-up: a take after a *named* soundtrack label
 * ("Joker OST") must keep its language marker on both the dash and pipe
 * upload shapes, not just after a bare " - OST".
 */
class SongVersionNamedOstReviewTest {

    @Test
    fun `a language take after a named OST label keeps its language marker`() {
        val korean = TrackMatcher.parseTitle("Dynamite - Joker OST Korean")
        assertEquals("dynamite", korean.core)
        assertEquals(setOf("korean"), korean.versions)

        val japanese = TrackMatcher.parseTitle("Dynamite - Joker OST Japanese Ver.")
        assertEquals("dynamite", japanese.core)
        assertEquals(setOf("japanese"), japanese.versions)

        val versioned = TrackMatcher.parseTitle("Dynamite - Joker OST Korean Version")
        assertEquals("dynamite", versioned.core)
        assertEquals(setOf("korean"), versioned.versions)
    }

    @Test
    fun `a take after a pipe OST label is kept instead of dropped with the label`() {
        val korean = TrackMatcher.parseTitle("Dynamite | Joker OST Korean Version")
        assertEquals("dynamite", korean.core)
        assertEquals(setOf("korean"), korean.versions)

        val live = TrackMatcher.parseTitle("Dynamite | Joker OST (Live)")
        assertEquals("dynamite", live.core)
        assertEquals(setOf("live"), live.versions)
    }

    @Test
    fun `a named OST language take is not the original recording`() {
        val original = Song(
            videoId = "studio-song",
            title = "Dynamite",
            artist = "Artist",
            thumbnailUrl = null,
        )
        for (title in listOf(
            "Dynamite - Joker OST Korean",
            "Dynamite - Joker OST Japanese Ver.",
            "Dynamite | Joker OST Korean Version",
        )) {
            assertFalse(
                title,
                TrackMatcher.sameRecording(original, TrackMatcher.Target(title, "Artist")),
            )
        }
    }

    @Test
    fun `named OST context words are still not a language take`() {
        val drama = TrackMatcher.parseTitle("Dynamite - Joker OST Korean Drama")
        assertEquals("dynamite", drama.core)
        assertEquals(emptySet<String>(), drama.versions)

        val plain = TrackMatcher.parseTitle("Dynamite - Joker OST")
        assertEquals("dynamite", plain.core)
        assertEquals(emptySet<String>(), plain.versions)
    }
}
