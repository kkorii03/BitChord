package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.spotify.SpotifyImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotify importer must score every search hit rather than take the first,
 * and must only fall back to that first hit when nothing matched.
 */
class SpotifyImportMatchTest {

    private fun song(id: String, title: String, artist: String, duration: String?) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    private val target = TrackMatcher.Target(
        title = "Song",
        artist = "Artist",
        durationSec = 200,
        album = "Album",
    )

    @Test
    fun theBestScoredCandidateWinsEvenWhenItIsNotFirst() {
        val wrongTake = song("wrong", "Song", "Someone Else", "3:00")
        val rightTake = song("right", "Song", "Artist", "3:20")
        val match = SpotifyImporter.bestMatch(listOf(wrongTake, rightTake), target)
        assertEquals("right", match.song?.videoId)
        assertFalse(match.loose)
    }

    @Test
    fun anUnrelatedFirstHitIsOnlyTheLooseFallback() {
        val unrelated = song("other", "Different Track", "Nobody", "2:10")
        val match = SpotifyImporter.bestMatch(listOf(unrelated), target)
        assertEquals("other", match.song?.videoId)
        assertTrue(match.loose)
    }

    @Test
    fun noCandidatesIsNoMatchRatherThanALooseOne() {
        val match = SpotifyImporter.bestMatch(emptyList(), target)
        assertNull(match.song)
        assertFalse(match.loose)
    }
}
