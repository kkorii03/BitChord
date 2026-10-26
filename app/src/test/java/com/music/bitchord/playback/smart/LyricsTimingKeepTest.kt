package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which measurement survives a later pass. The case is the phone run of 10-26:
 * FRIENDS (ZjZRLy3A3QA) measured +199 ms from the record's opening, the full
 * pass came back empty, and the empty answer replaced the good one.
 */
class LyricsTimingKeepTest {

    @Test
    fun `an empty full pass keeps the opening's measurement`() {
        assertEquals(199L, LyricsTiming.autoAfter(previous = 199L, previousCurrent = true, fresh = null, noChange = false))
    }

    @Test
    fun `a new result replaces the old one`() {
        assertEquals(210L, LyricsTiming.autoAfter(previous = 199L, previousCurrent = true, fresh = 210L, noChange = false))
    }

    @Test
    fun `a finding that no shift is needed replaces it`() {
        assertNull(LyricsTiming.autoAfter(previous = 199L, previousCurrent = true, fresh = null, noChange = true))
    }

    @Test
    fun `a measurement from an older version is not carried over`() {
        assertNull(LyricsTiming.autoAfter(previous = 199L, previousCurrent = false, fresh = null, noChange = false))
    }
}
