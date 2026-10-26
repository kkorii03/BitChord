package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsSyncReportTest {

    @Test
    fun line_names_every_field_the_sync_line_uses() {
        val line = LyricsSyncReport.line(
            videoId = "vid1",
            line = 3,
            total = 20,
            shownMs = 12_345L,
            tappedMs = 10_545L,
            source = "lrc.red",
            base = "measured",
            shiftMs = -200L,
            manualMs = 100L,
            playing = "src:qobuz::t1",
        )
        assertEquals(
            "sync-report: vid1 line=3/20 shown=12345 tapped=10545 error=1800 " +
                "source=lrc.red base=measured shift=-200ms manual=100ms " +
                "playing=src:qobuz::t1",
            line,
        )
    }

    @Test
    fun missing_shift_is_written_as_a_dash() {
        val line = LyricsSyncReport.line(
            videoId = "vid1",
            line = 1,
            total = 4,
            shownMs = 100L,
            tappedMs = 50L,
            source = "",
            base = "",
            shiftMs = null,
            manualMs = 0L,
            playing = "",
        )
        assertTrue(line.contains("source=none"))
        assertTrue(line.contains("base=none"))
        assertTrue(line.contains("shift=-ms"))
        assertTrue(line.endsWith("playing=vid1"))
    }

    @Test
    fun error_is_shown_minus_tapped() {
        assertTrue(LyricsSyncReport.isLate(1_800L))
        assertFalse(LyricsSyncReport.isLate(-1_800L))
        assertEquals("1.8", LyricsSyncReport.seconds(-1_800L))
    }
}
