package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOpeningSongHearingTest {
    @Test
    fun `an opening ask never shares the whole song's slot`() {
        // 11-09 230-d bYt37InYBlQ: exact 90 s joined the 420 s flight, answered 167 s later.
        val b = "v|stream|yt|ko|true"
        assertEquals(false, SongHearing.flightSlot(b, 90.0) == SongHearing.flightSlot(b, 420.0))
        assertEquals(SongHearing.flightSlot(b, 40.0), SongHearing.flightSlot(b, 90.0))
        assertEquals(SongHearing.flightSlot(b, 120.0), SongHearing.flightSlot(b, 90.0))
        assertEquals(b, SongHearing.flightSlot(b, 185.0))
    }

    @Test
    fun `a whole-song ask that may settle for the opening reads it, others do not`() {
        assertEquals(true, SongHearing.readsOpening(420.0, 90.0))
        assertEquals(false, SongHearing.readsOpening(420.0, 420.0))
        assertEquals(false, SongHearing.readsOpening(185.0, 185.0))
        assertEquals(false, SongHearing.readsOpening(90.0, 24.0))
    }
}
