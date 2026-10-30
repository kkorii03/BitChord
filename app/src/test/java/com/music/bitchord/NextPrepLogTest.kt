package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.playback.NextPrepLog
import org.junit.Assert.assertEquals
import org.junit.Test

/** The next-song log must not report a disabled step as a failed one. */
class NextPrepLogTest {

    @Test
    fun aDisabledLyricsStepIsOffNotNone() {
        assertEquals("off", NextPrepLog.lyrics(source = null, attempted = false))
    }

    @Test
    fun anAttemptedMissIsNone() {
        assertEquals("none", NextPrepLog.lyrics(source = null, attempted = true))
    }

    @Test
    fun aFoundAnswerNamesItsSource() {
        assertEquals("lrclib", NextPrepLog.lyrics(source = LyricsSource.LRCLIB, attempted = true))
    }

    @Test
    fun syncDistinguishesOffFromSkipped() {
        assertEquals("off", NextPrepLog.sync(applied = false, attempted = false))
        assertEquals("skipped", NextPrepLog.sync(applied = false, attempted = true))
        assertEquals("applied", NextPrepLog.sync(applied = true, attempted = true))
    }
}
