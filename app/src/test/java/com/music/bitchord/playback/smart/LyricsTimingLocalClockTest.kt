package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Test

/** A local file's clock comes from the download metadata, not from "it is local". */
class LyricsTimingLocalClockTest {

    @Test
    fun `an hq download is the substitute clock it was verified against`() {
        assertEquals(
            LyricsTiming.Clock.Substitute("src", "track"),
            LyricsTiming.clockForLocalCopy("src", "track"),
        )
    }

    @Test
    fun `a download with no hq source pair is on youtube's clock`() {
        assertEquals(LyricsTiming.Clock.YouTube, LyricsTiming.clockForLocalCopy(null, null))
        assertEquals(LyricsTiming.Clock.YouTube, LyricsTiming.clockForLocalCopy("src", null))
    }
}
