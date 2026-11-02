package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The current song's title takes [com.music.bitchord.ui.components.PlayingAccent]
 * on every Home row shape. Only the card that was actually played may light up:
 * a duplicated title elsewhere in the shelf must not.
 */
class HomePlayingRowTest {

    private fun item(title: String, artist: String, videoId: String?) =
        ShelfItem(title, artist, null, videoId, null)

    private fun song(videoId: String, title: String, artist: String) =
        Song(videoId = videoId, title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun `a shelf that holds the played video marks only that card`() {
        val played = item("Same title", "Same artist", "played")
        val other = item("Same title", "Same artist", "other")
        val matcher = listOf(played, other).playingItemMatcher(song("played", "Same title", "Same artist"))

        assertTrue(matcher(played))
        assertFalse(matcher(other))
    }

    @Test
    fun `a shelf without the video falls back to one matching title`() {
        val first = item("Same title", "Same artist", "first")
        val second = item("Same title", "Same artist", "second")
        val matcher = listOf(first, second).playingItemMatcher(song("played", "Same title", "Same artist"))

        assertTrue(matcher(first))
        assertFalse(matcher(second))
    }

    @Test
    fun `a row lit by name needs both title and artist to match`() {
        val row = item("Same title", "Someone else", "other")

        assertFalse(row.matchesCurrentlyPlaying(song("played", "Same title", "Same artist")))
    }

    @Test
    fun `a browse-only card and no current song light nothing`() {
        val album = item("An album", "An artist", null)

        assertFalse(album.matchesCurrentlyPlaying(song("played", "An album", "An artist")))
        assertFalse(listOf(album).playingItemMatcher(null)(album))
    }
}
