package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ui.player.nextSyncReportLine
import kotlin.test.Test
import kotlin.test.assertEquals

class NextSyncReportLineTest {

    @Test
    fun skips_untimed_and_instrumental_lines() {
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "intro"),
            LyricLine(timeMs = 1_000L, text = ""),
            LyricLine(timeMs = 1_500L, text = "   "),
            LyricLine(timeMs = 2_000L, text = "sung"),
            LyricLine(timeMs = 3_000L, text = "later"),
        )
        assertEquals(3, nextSyncReportLine(lines, 1_400L))
    }

    @Test
    fun no_line_after_the_playhead_is_minus_one() {
        val lines = listOf(
            LyricLine(timeMs = 1_000L, text = "sung"),
            LyricLine(timeMs = 2_000L, text = ""),
        )
        assertEquals(-1, nextSyncReportLine(lines, 2_000L))
    }
}
