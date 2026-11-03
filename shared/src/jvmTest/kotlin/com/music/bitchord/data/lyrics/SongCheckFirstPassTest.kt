package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first song check listens to a short opening so a cold song is corrected
 * while it plays, then follows with the full opening. These are the two rules
 * that keep that second pass from spending an engine twice on a refusal or
 * dropping an early correction.
 */
class SongCheckFirstPassTest {

    @Test
    fun `the first opening is short enough to answer within a minute`() {
        assertTrue(SongCheckWindow.FIRST_SECONDS in 30.0..45.0)
    }

    @Test
    fun `an applied first pass does not wait for the longer opening`() {
        assertFalse(SongCheckWindow.needsFullOpening("applied", placedLines = 9))
        assertFalse(SongCheckWindow.needsFullOpening("applied", placedLines = 3))
    }

    @Test
    fun `a first pass without an answer still gets the longer opening`() {
        assertTrue(SongCheckWindow.needsFullOpening("no-words", placedLines = 0))
        assertTrue(SongCheckWindow.needsFullOpening("few-words", placedLines = 1))
        assertTrue(SongCheckWindow.needsFullOpening("no-fit", placedLines = 2))
        assertTrue(SongCheckWindow.needsFullOpening("no-fit", placedLines = 5))
        assertTrue(SongCheckWindow.needsFullOpening("refused-match", placedLines = 7))
        assertTrue(SongCheckWindow.needsFullOpening("refused-shift", placedLines = 6))
    }

    @Test
    fun `a refusal is never asked again`() {
        assertFalse(SongCheckWindow.needsFullOpening("no-answer", placedLines = 0))
        assertFalse(SongCheckWindow.needsFullOpening("no-answer", placedLines = 9))
    }

    @Test
    fun `a full check without a fit never replaces an early correction`() {
        assertFalse(SongCheckWindow.fullMayReplace(firstFitted = true, fullFitted = false))
        assertTrue(SongCheckWindow.fullMayReplace(firstFitted = true, fullFitted = true))
        assertTrue(SongCheckWindow.fullMayReplace(firstFitted = false, fullFitted = false))
        assertTrue(SongCheckWindow.fullMayReplace(firstFitted = false, fullFitted = true))
    }
}
