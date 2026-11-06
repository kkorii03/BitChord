package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one AI slot belongs to the sheet asked about last, whichever answer lands last. */
class LyricsTimingVideoAnswerTest {

    @Test
    fun `only the sheet asked about last may take the slot`() {
        LyricsTiming.newestSongKey["sheets"] = 1
        LyricsTiming.newestSongKey["sheets"] = 2

        assertTrue(LyricsTiming.replacedSheet("sheets", 1))
        assertFalse(LyricsTiming.replacedSheet("sheets", 2))
    }

    @Test
    fun `a video never asked about has nothing to be replaced by`() {
        assertFalse(LyricsTiming.replacedSheet("never-asked", 1))
    }
}
