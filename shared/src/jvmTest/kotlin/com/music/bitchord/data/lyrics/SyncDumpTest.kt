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
            "sync-dump: line abc 3 1234 rep=1/1 words=0 lit=1234 ${"a".repeat(60)}",
            SyncDump.line("abc", 3, 1234L, text),
        )
        assertEquals(
            "sync-dump: line abc 1 0 rep=1/1 words=0 lit=0 short",
            SyncDump.line("abc", 1, 0L, "short"),
        )
        assertEquals(
            "sync-dump: line abc 2 0 rep=1/1 words=0 lit=0 ${"b".repeat(60)}",
            SyncDump.line("abc", 2, 0L, "b".repeat(60)),
        )
        assertEquals(
            "sync-dump: line abc 4 0 rep=1/1 words=0 lit=0 a b",
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
                "sync-dump: line abc 1 1000 rep=1/1 words=1 lit=1000 first",
                "sync-dump: line abc 2 2500 rep=1/1 words=0 lit=2500 ",
                "sync-dump: line abc 3 3000 rep=1/1 words=1 lit=3000 third",
                "sync-dump: end abc",
            ),
            SyncDump.dump("abc", "abc", "BiniLyrics", "ai", 40L, 100, lines),
        )
    }

    /** The reported shape: a chorus line that comes round four times, and a one-word line. */
    @Test
    fun `a repeated line counts which time it comes round and a short line its words`() {
        val lines = listOf(
            LyricLine(timeMs = 1_000L, text = "Baby, I'm preying on you tonight"),
            LyricLine(timeMs = 3_000L, text = "Animals"),
            LyricLine(timeMs = 5_000L, text = "Baby I'm preying on you tonight"),
            LyricLine(timeMs = 7_000L, text = "Animals"),
        )

        val dump = SyncDump.dump("abc", "abc", "lrc.red", "measured", 0L, 90, lines)

        assertEquals("sync-dump: line abc 1 1000 rep=1/2 words=6 lit=1000 Baby, I'm preying on you tonight", dump[1])
        assertEquals("sync-dump: line abc 2 3000 rep=1/2 words=1 lit=3000 Animals", dump[2])
        assertEquals("sync-dump: line abc 3 5000 rep=2/2 words=6 lit=5000 Baby I'm preying on you tonight", dump[3])
        assertEquals("sync-dump: line abc 4 7000 rep=2/2 words=1 lit=7000 Animals", dump[4])
    }

    @Test
    fun `lit is where the sweep starts, not the stamp`() {
        val sheetWords = LyricLine(
            timeMs = 1_000L,
            text = "held back",
            words = listOf(LyricWord(1_400L, 1_800L, "held"), LyricWord(1_900L, 2_200L, "back")),
        )
        val heardLine = LyricLine(timeMs = 3_000L, text = "hello world")
        val guessed = LyricLine(timeMs = 5_000L, text = "nobody heard")
        val heard = listOf(
            LyricsWordAlign.Heard("hello", 3_300L, 3_600L),
            LyricsWordAlign.Heard("world", 3_700L, 4_000L),
        )

        val dump = SyncDump.dump("abc", "abc", "lrc.red", "ai", 0L, 90, listOf(sheetWords, heardLine, guessed), heard)

        assertEquals("sync-dump: line abc 1 1000 rep=1/1 words=2 lit=1400 held back", dump[1])
        assertEquals("sync-dump: line abc 2 3000 rep=1/1 words=2 lit=3300 hello world", dump[2])
        assertEquals("sync-dump: line abc 3 5000 rep=1/1 words=2 lit=5000 nobody heard", dump[3])
    }
}