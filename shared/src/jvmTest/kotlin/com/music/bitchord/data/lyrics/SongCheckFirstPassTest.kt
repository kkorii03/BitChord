package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `a screen-off first pass is the full opening, not the short one`() {
        // Nobody is looking at the screen, so the short pass has no correction
        // to show; a second full pass would only decode and transcribe the
        // same opening twice. The pocket keeps v1.9's single full pass.
        assertEquals(
            SongCheckWindow.FIRST_SECONDS,
            SongCheckWindow.firstPassSeconds(screenOn = true, fullSeconds = 90.0),
        )
        assertEquals(
            90.0,
            SongCheckWindow.firstPassSeconds(screenOn = false, fullSeconds = 90.0),
        )
    }

    @Test
    fun `no second pass once the screen is off or the first pass was refused`() {
        assertEquals(
            90.0,
            SongCheckWindow.secondPassSeconds(
                screenOnAtStart = true,
                screenOnNow = true,
                result = "applied",
                fullSeconds = 90.0,
            ),
        )
        // The screen went off during the first pass: do not decode the full
        // opening for a correction nobody can see.
        assertNull(
            SongCheckWindow.secondPassSeconds(
                screenOnAtStart = true,
                screenOnNow = false,
                result = "applied",
                fullSeconds = 90.0,
            ),
        )
        // The first pass already read the full opening (screen was off).
        assertNull(
            SongCheckWindow.secondPassSeconds(
                screenOnAtStart = false,
                screenOnNow = true,
                result = "applied",
                fullSeconds = 90.0,
            ),
        )
        assertNull(
            SongCheckWindow.secondPassSeconds(
                screenOnAtStart = true,
                screenOnNow = true,
                result = "no-answer",
                fullSeconds = 90.0,
            ),
        )
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
