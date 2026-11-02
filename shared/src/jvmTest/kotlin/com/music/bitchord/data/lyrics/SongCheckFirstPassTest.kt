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
    fun `the full opening follows every first result but a refusal`() {
        assertTrue(SongCheckWindow.needsFullOpening("applied"))
        assertTrue(SongCheckWindow.needsFullOpening("no-words"))
        assertTrue(SongCheckWindow.needsFullOpening("few-words"))
        assertTrue(SongCheckWindow.needsFullOpening("no-fit"))
        assertTrue(SongCheckWindow.needsFullOpening("refused-match"))
        assertTrue(SongCheckWindow.needsFullOpening("refused-shift"))
        assertFalse(SongCheckWindow.needsFullOpening("no-answer"))
    }

    @Test
    fun `a full check without a fit never replaces an early correction`() {
        assertFalse(SongCheckWindow.fullMayReplace(firstFitted = true, fullFitted = false))
        assertTrue(SongCheckWindow.fullMayReplace(firstFitted = true, fullFitted = true))
        assertTrue(SongCheckWindow.fullMayReplace(firstFitted = false, fullFitted = false))
        assertTrue(SongCheckWindow.fullMayReplace(firstFitted = false, fullFitted = true))
    }
}
