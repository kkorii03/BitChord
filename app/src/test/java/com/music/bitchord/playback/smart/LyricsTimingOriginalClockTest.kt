package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A no-change measurement of the upload itself is always on YouTube's clock. */
class LyricsTimingOriginalClockTest {

    @Test
    fun `the video is the original result is tagged youtube`() {
        val done = LyricsTiming.originalNoChangeMeasurement()

        assertTrue(done.noChange)
        assertEquals(LyricsTiming.Clock.YouTube, done.clock)
    }
}
