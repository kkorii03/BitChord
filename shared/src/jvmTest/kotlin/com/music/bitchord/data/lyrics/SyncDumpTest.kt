package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncDumpTest {

    @Test
    fun `begin carries the figures and treats a missing match as zero`() {
        assertEquals(
            "sync-dump: begin abc playing=abc source=lrc.red base=measured shift=-120 match=87 lines=12",
            SyncDump.begin("abc", "abc", "lrc.red", "measured", -120L, 87, 12),
        )
        assertEquals(
            "sync-dump: begin abc playing=src:mod::t1 source=LRCLIB base=none shift=0 match=0 lines=0",
            SyncDump.begin("abc", "src:mod::t1", "LRCLIB", "none", 0L, null, 0),
        )
    }

    @Test
    fun `a line keeps the first sixty characters and stays on one line`() {
        val text = "a".repeat(80)
        assertEquals(
            "sync-dump: line abc 3 1234 ${"a".repeat(60)}",
            SyncDump.line("abc", 3, 1234L, text),
        )
        assertEquals(
            "sync-dump: line abc 1 0 short",
            SyncDump.line("abc", 1, 0L, "short"),
        )
        assertEquals(
            "sync-dump: line abc 2 0 ${"b".repeat(60)}",
            SyncDump.line("abc", 2, 0L, "b".repeat(60)),
        )
        assertEquals(
            "sync-dump: line abc 4 0 a b",
            SyncDump.line("abc", 4, 0L, "a\nb"),
        )
    }

    @Test
    fun `dump numbers every line from one and closes with end`() {
        val lines = listOf(
            LyricLine(timeMs = 1_000L, text = "first"),
            LyricLine(timeMs = 2_500L, text = ""),
            LyricLine(timeMs = 3_000L, text = "third"),
        )
        assertEquals(
            listOf(
                "sync-dump: begin abc playing=abc source=BiniLyrics base=ai shift=40 match=100 lines=3",
                "sync-dump: line abc 1 1000 first",
                "sync-dump: line abc 2 2500${' '}",
                "sync-dump: line abc 3 3000 third",
                "sync-dump: end abc",
            ),
            SyncDump.dump("abc", "abc", "BiniLyrics", "ai", 40L, 100, lines),
        )
    }
}
