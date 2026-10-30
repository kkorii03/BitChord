package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 1031: soundtrack tails must never become the song's identity. */
class TrackMatcherOstReviewTest {

    @Test
    fun `a mid-title OST tail is not part of the core`() {
        val parts = TrackMatcher.parseTitle("Song - OST Something")
        assertEquals("song", parts.core)
        assertEquals(
            listOf("song artist", "song"),
            TrackMatcher.queries(TrackMatcher.Target("Song - OST Something", "Artist")),
        )
    }

    @Test
    fun `a real performer credit is kept in the soundtrack queries`() {
        val song = Song(
            videoId = "joker-song",
            title = "Song | Joker OST",
            artist = "Hildur Guðnadóttir",
            thumbnailUrl = null,
        )
        val queries = TrackMatcher.audioSwitchQueries(song)
        assertTrue(queries.toString(), "song hildur guðnadóttir" in queries)
        assertTrue(queries.toString(), "song" in queries)
    }

    @Test
    fun `a bracketed take followed by packaging keeps the take and drops the packaging`() {
        val parts = TrackMatcher.parseTitle("Song - Original Soundtrack (Live) Version")
        assertEquals("song", parts.core)
        assertEquals(setOf("live"), parts.versions)
        assertEquals(
            listOf("song live artist", "song live"),
            TrackMatcher.queries(
                TrackMatcher.Target("Song - Original Soundtrack (Live) Version", "Artist"),
            ),
        )
    }

    @Test
    fun `a bracketed take followed by packaging is cleaned without a soundtrack label`() {
        val parts = TrackMatcher.parseTitle("Song (Live) Version")
        assertEquals("song", parts.core)
        assertEquals(setOf("live"), parts.versions)
        assertEquals("song", TrackMatcher.parseTitle("Song Edition").core)
    }

    @Test
    fun `a language version is a take of the title, not the title`() {
        val dash = TrackMatcher.parseTitle("Song - Korean Version")
        assertEquals("song", dash.core)
        assertEquals(setOf("korean"), dash.versions)
        val bracket = TrackMatcher.parseTitle("Song (Korean Ver.)")
        assertEquals("song", bracket.core)
        assertEquals(setOf("korean"), bracket.versions)
        val japanese = TrackMatcher.parseTitle("Song (日本語 ver.)")
        assertEquals("song", japanese.core)
        assertEquals(setOf("japanese"), japanese.versions)
    }

    @Test
    fun `a VEVO channel credit still inverts an artist-title upload`() {
        val raw = "Artist - Song (Official Video)"
        assertEquals("song", TrackMatcher.parseTitle(raw, "ArtistVEVO").core)
        assertEquals(
            listOf("song artist", "song"),
            TrackMatcher.queries(TrackMatcher.Target(raw, "ArtistVEVO")),
        )
    }

    @Test
    fun `a take after a mid-title OST label is kept as a take`() {
        val live = TrackMatcher.parseTitle("Song - OST Live at Wembley")
        assertEquals("song", live.core)
        assertEquals(setOf("live"), live.versions)

        val remix = TrackMatcher.parseTitle("Song - OST Remix by X")
        assertEquals("song", remix.core)
        assertEquals(setOf("remix"), remix.versions)

        val target = TrackMatcher.Target("Song - OST Live at Wembley", "Artist")
        val studio = Song(
            videoId = "studio-song",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
        )
        assertEquals(false, TrackMatcher.sameRecording(studio, target))
    }

    @Test
    fun `a language word used as context is not a language version`() {
        val drama = TrackMatcher.parseTitle("Song (Korean Drama OST)")
        assertEquals("song", drama.core)
        assertEquals(emptySet<String>(), drama.versions)

        val translation = TrackMatcher.parseTitle("Song (English Translation)")
        assertEquals("song", translation.core)
        assertEquals(emptySet<String>(), translation.versions)
    }

    @Test
    fun `a Hangul title with a language version tail is a language take not a translation`() {
        val parts = TrackMatcher.parseTitle("여수밤바다 - Japanese Version")
        assertEquals("여수밤바다", parts.core)
        assertEquals(setOf("japanese"), parts.versions)
        assertEquals(emptyList<String>(), parts.altCores)
    }

    @Test
    fun `a CJK title drops trailing packaging from its core`() {
        val bracketed = TrackMatcher.parseTitle("紅蓮華 (Live) Version")
        assertEquals("紅蓮華", bracketed.core)
        assertEquals(setOf("live"), bracketed.versions)

        val bare = TrackMatcher.parseTitle("紅蓮華 Version")
        assertEquals("紅蓮華", bare.core)
    }
}
