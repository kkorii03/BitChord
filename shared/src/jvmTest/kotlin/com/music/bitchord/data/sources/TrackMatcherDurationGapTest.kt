package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The runtime parser every length gate in playback, downloads and sync calls.
 *
 * A displayed duration is the only length a catalogue row or a queued track
 * carries, and it is compared against the decoder's own figure before a
 * recording is accepted. A parser that accepts `3:60` or overflows a large
 * minute count therefore lets a malformed row pass a check it should fail.
 */
class TrackMatcherDurationGapTest {

    @Test
    fun `ordinary minute second and hour minute second runtimes parse`() {
        assertEquals(221, TrackMatcher.secondsOf("3:41"))
        assertEquals(3, TrackMatcher.secondsOf("00:03"))
        assertEquals(245, TrackMatcher.secondsOf(" 4:05 "))
        assertEquals(5_025, TrackMatcher.secondsOf("1:23:45"))
        assertEquals(7_199, TrackMatcher.secondsOf("1:59:59"))
        assertEquals(3_600, TrackMatcher.secondsOf("60:00"))
    }

    @Test
    fun `blank and non-time text is not a runtime`() {
        listOf(null, "", "   ", "3", "3:", ":41", "3:xx", "3:41.5", "1:2:3:4")
            .forEach { assertNull("[$it]", TrackMatcher.secondsOf(it)) }
    }

    @Test
    fun `zero is not a runtime`() {
        assertNull(TrackMatcher.secondsOf("0:00"))
        assertNull(TrackMatcher.secondsOf("0:00:00"))
    }

    @Test
    fun `a negative component is not a runtime`() {
        assertNull(TrackMatcher.secondsOf("-1:30"))
        assertNull(TrackMatcher.secondsOf("3:-1"))
        assertNull(TrackMatcher.secondsOf("-1:00:00"))
    }

    @Test
    fun `seconds and minutes outside their lane are not runtimes`() {
        assertNull(TrackMatcher.secondsOf("3:60"))
        assertNull(TrackMatcher.secondsOf("1:60:00"))
        assertNull(TrackMatcher.secondsOf("3:99:00"))
        assertNull(TrackMatcher.secondsOf("1:00:60"))
    }

    @Test
    fun `a huge minute count does not overflow into a plausible runtime`() {
        assertNull(TrackMatcher.secondsOf("100000000:00"))
        assertNull(TrackMatcher.secondsOf("2147483647:59"))
    }
}
