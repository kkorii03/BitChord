package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** L15: Musixmatch's best row must name the song; the artist's other hit is not it. */
class MusixmatchPickTest {

    @Test
    fun theArtistsOtherSongIsNotTaken() {
        val rows = listOf(Triple("Old Hit", "Artist", 201))
        assertNull(Musixmatch.pickIndex(rows, "New Single", "Artist", 200))
    }

    @Test
    fun aTitleOnlyRoundDoesNotTakeAnyArtistsTrack() {
        val rows = listOf(Triple("Something Else", "Somebody", 200))
        assertNull(Musixmatch.pickIndex(rows, "Wanted", "", 200))
    }

    @Test
    fun theNamedRowStillWins() {
        val rows = listOf(Triple("Old Hit", "Artist", 200), Triple("Wanted (Remastered)", "Artist", 230))
        assertEquals(1, Musixmatch.pickIndex(rows, "Wanted", "Artist", 200))
    }

    @Test
    fun aRowInAnotherScriptIsLeftToTheScore() {
        val rows = listOf(Triple("Good Day", "IU", 233))
        assertEquals(0, Musixmatch.pickIndex(rows, "좋은 날", "아이유", 233))
    }

    @Test
    fun namesTheSongRules() {
        assertTrue(RecordingMatch.namesTheSong("wanted", "Wanted"))
        assertTrue(RecordingMatch.namesTheSong("Wanted", "Wanted (feat. X)"))
        assertFalse(RecordingMatch.namesTheSong("Other Song", "Wanted"))
        assertFalse(RecordingMatch.namesTheSong("", "좋은 날"))
        assertTrue(RecordingMatch.namesTheSong("Good Day", "좋은 날"))
        assertTrue(RecordingMatch.namesTheSong("Anything", ""))
    }
}
