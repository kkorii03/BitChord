package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Review RR (11-17): F3 the "Artist - Song" head not recognised on a VEVO
 * channel that runs the artist's words together, nor in the upload's own
 * casing; F5 "커버" matched inside other words.
 */
class KoTrackMatcherRrFixTest {

    private fun row(title: String, artist: String) =
        Song(videoId = "rr-$title", title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun aMultiWordArtistOnAConcatenatedVevoChannelIsTheHead() {
        assertEquals(
            "loveyourself",
            KoTrackMatcher.parseTitle("Justin Bieber - Love Yourself (Official Music Video)", "JustinBieberVEVO").core,
        )
        // The song first stays the song.
        assertEquals(
            "loveyourself",
            KoTrackMatcher.parseTitle("Love Yourself - Justin Bieber", "JustinBieberVEVO").core,
        )
        // A one-word act on its VEVO channel still inverts as before.
        assertEquals("song", KoTrackMatcher.parseTitle("Artist - Song (Official Video)", "ArtistVEVO").core)
    }

    @Test
    fun aCapitalisedArtistTailBeforeAnOstLabelIsNotTheTitle() {
        val got = KoTrackMatcher.soundtrackIdentity("Love Yourself - Justin Bieber | Joker OST", "Justin Bieber")
        assertNotEquals("Justin Bieber", got.title)
        assertEquals("", got.artist)
        // A performer who is not the credit still splits off.
        val cream = KoTrackMatcher.soundtrackIdentity("Cream - White Room | Joker OST", "Joker OST")
        assertEquals("White Room", cream.title)
        assertEquals("Cream", cream.artist)
    }

    @Test
    fun aWordContainingTheCoverSyllablesIsNotACover() {
        assertFalse(KoTrackMatcher.isCoverArrangement(row("디스커버리", "Artist")))
        assertFalse(KoTrackMatcher.isCoverArrangement(row("커버리지", "Artist")))
        assertFalse(KoTrackMatcher.isCoverArrangement(row("리커버리", "Artist")))
        // Real covers, glued or bracketed, still are.
        assertTrue(KoTrackMatcher.isCoverArrangement(row("밤편지 아이유커버", "홍길동")))
        assertTrue(KoTrackMatcher.isCoverArrangement(row("밤편지 (커버)", "홍길동")))
        assertTrue(KoTrackMatcher.isCoverArrangement(row("밤편지 커버곡", "홍길동")))
    }
}
