package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RG2-2: the Musixmatch / PaxSenix name floor must not drop the right row over how the
 * same name is written (curly apostrophe, accents, "&", Hangul spacing).
 */
class RecordingMatchSpellingTest {

    @Test
    fun aCurlyApostropheIsTheSameName() {
        assertTrue(RecordingMatch.namesTheSong("Don’t Start Now", "Don't Start Now"))
    }

    @Test
    fun hangulSpacingIsTheSameName() {
        assertTrue(RecordingMatch.namesTheSong("좋은 날", "좋은날"))
        assertTrue(RecordingMatch.namesTheSong("좋은날", "좋은 날"))
    }

    @Test
    fun accentsAndAmpersandAreTheSameName() {
        assertTrue(RecordingMatch.namesTheSong("Café", "Cafe"))
        assertTrue(RecordingMatch.namesTheSong("Rock & Roll", "Rock and Roll"))
    }

    @Test
    fun anotherSongIsStillRefused() {
        assertFalse(RecordingMatch.namesTheSong("Other Song", "Don't Start Now"))
        assertFalse(RecordingMatch.namesTheSong("나쁜 날", "좋은날"))
        assertFalse(RecordingMatch.namesTheSong("!!!", "Wanted"))
        // Hangul is compared as syllables, not as the jamo the accent folding splits it into.
        assertFalse(RecordingMatch.namesTheSong("보미", "봄"))
    }

    @Test
    fun musixmatchTakesTheCurlyApostropheRow() {
        val rows = listOf(Triple("Don’t Start Now", "Dua Lipa", 183))
        assertEquals(0, Musixmatch.pickIndex(rows, "Don't Start Now", "Dua Lipa", 183))
    }
}
