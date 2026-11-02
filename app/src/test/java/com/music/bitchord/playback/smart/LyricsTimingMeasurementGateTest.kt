package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-02 sync-g: two late-version lookups started the same (song, version) pair
 * a moment apart, and the measurement for a song that had already ended kept
 * taking the one decode slot. Only the current and prepared-next rows are
 * measured, and only one late measurement per pair runs at a time.
 */
class LyricsTimingMeasurementGateTest {

    @Test
    fun `the playing song and the prepared next are wanted`() {
        assertTrue(LyricsTiming.measurementWanted("playing", "playing", "next"))
        assertTrue(LyricsTiming.measurementWanted("next", "playing", "next"))
    }

    @Test
    fun `a song that has left the window is not wanted`() {
        assertFalse(LyricsTiming.measurementWanted("gone", "playing", "next"))
        assertFalse(LyricsTiming.measurementWanted("gone", null, null))
    }

    @Test
    fun `a second late measurement of the same pair waits for the first`() {
        val flights = LyricsTiming.MeasurementFlights()
        assertTrue(flights.begin("song\u0000original"))
        assertFalse(flights.begin("song\u0000original"))
        assertTrue(flights.begin("song\u0000other-original"))
    }

    @Test
    fun `the pair can be measured again once the first has finished`() {
        val flights = LyricsTiming.MeasurementFlights()
        assertTrue(flights.begin("song\u0000original"))
        flights.end("song\u0000original")
        assertTrue(flights.begin("song\u0000original"))
    }
}
