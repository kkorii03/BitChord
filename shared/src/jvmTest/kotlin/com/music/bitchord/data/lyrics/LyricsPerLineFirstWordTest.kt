package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A line heard from its first word starts on that word ([LyricsPerLine] first-word snaps). */
class LyricsPerLineFirstWordTest {

    private fun line(at: Long, text: String) = LyricLine(timeMs = at, text = text)

    private fun heard(text: String, startMs: Long, endMs: Long = startMs + 200L) =
        LyricsWordAlign.Heard(text, startMs, endMs)

    private fun layer(lines: List<LyricLine>, words: List<LyricsWordAlign.Heard>) =
        LyricsPerLine.compute(lines, words, heardAudioKey = 1, currentAudioKey = 1)

    /**
     * 11-09 6rS7OUGXUik in figures: a hand-tapped sheet already on the right clock, each line
     * off by its own ±0.3 s, sung with held notes (the second word held 1.5 s), so a line's
     * words do not follow 180 ms a letter. The words' median put lines 2 and 9 about 0.4 s
     * from where they were sung; each line's first heard word is where it starts.
     */
    @Test
    fun `a jittered hand-tapped sheet starts each line on its first heard word`() {
        val names = "alpha bravo charlie delta echoes foxtrot golfing hotels indigo juliet kilos lima".split(" ")
        val lines = List(12) { i ->
            line(20_000L + i * 12_000L, "${names[i]}x ${names[(i + 1) % 12]}y ${names[(i + 2) % 12]}z ${names[(i + 3) % 12]}w")
        }
        val jitter = listOf(240L, -260L, 180L, -220L, 280L, -150L, 230L, -270L, 200L, -240L, 260L, -190L)
        val gaps = listOf(0L, 900L, 2_600L, 2_900L)
        val words = lines.flatMapIndexed { i, l ->
            l.text.split(" ").mapIndexed { k, w ->
                val start = l.timeMs + jitter[i] + gaps[k]
                heard(w, start, start + if (k == 1) 1_500L else 250L)
            }
        }

        val result = layer(lines, words)

        lines.indices.forEach { i ->
            assertTrue(abs(result.shiftsMs[i] - jitter[i]) <= 30L, "line $i moved ${result.shiftsMs[i]}, sung at ${jitter[i]}")
        }
        assertEquals(12, result.snapped)
    }

    /** A held last note: the next line's opening heard before it ends is not where that line starts. */
    @Test
    fun `a line is not started before the held note before it ends`() {
        val lines = listOf(line(10_000, "alpha bravo charlie"), line(14_200, "delta echo"))
        // Two words 20 ms apart are no pace a singer has: the words alone do not move the line.
        val held = listOf(
            heard("alpha", 10_200), heard("bravo", 10_500), heard("charlie", 10_800, 14_000),
            heard("delta", 13_800), heard("echo", 13_820),
        )

        val result = layer(lines, held)

        assertEquals(200L, result.shiftsMs[0])
        assertEquals(0L, result.shiftsMs[1], "the line started inside the note before it")

        val released = held.map { if (it.text == "charlie") it.copy(endMs = 13_700) else it }
        assertEquals(-400L, layer(lines, released).shiftsMs[1])
    }

    /** The opening of a line the sheet repeats may be the other copy's words: only a small move. */
    @Test
    fun `a repeated line moves on its first word only a little`() {
        fun words(third: String) = listOf(
            heard("alpha", 10_100), heard("bravo", 10_700),
            heard("delta", 14_100), heard("echo", 14_700),
            heard(third.substringBefore(' '), 19_000), heard(third.substringAfter(' '), 19_020),
        )
        val repeated = listOf(line(10_000, "alpha bravo"), line(14_000, "delta echo"), line(18_000, "alpha bravo"))
        val unique = repeated.take(2) + line(18_000, "golf hotel")

        assertEquals(0L, layer(repeated, words("alpha bravo")).shiftsMs[2])
        assertEquals(1_000L, layer(unique, words("golf hotel")).shiftsMs[2])
    }

    /** Fast rap: lines 0.7 s apart, one heard 0.65 s early into the line before it. Order holds. */
    @Test
    fun `dense rap lines start on their words and stay in order`() {
        val lines = "kilo lima mike nova oscar papa quebec romeo".split(" ").mapIndexed { i, w ->
            line(30_000L + i * 700L, "${w}a ${w}b ${w}c")
        }
        val words = lines.flatMapIndexed { i, l ->
            val offset = if (i == 4) -650L else 100L
            l.text.split(" ").mapIndexed { k, w ->
                val start = l.timeMs + offset + k * 150L
                heard(w, start, start + 140L)
            }
        }.sortedBy { it.startMs }

        val shifts = layer(lines, words).shiftsMs

        listOf(0, 1, 2, 5, 6, 7).forEach { assertEquals(100L, shifts[it], "line $it") }
        val starts = lines.indices.map { lines[it].timeMs + shifts[it] }
        for (i in 1 until starts.size) {
            assertTrue(starts[i] >= starts[i - 1] + LyricsPerLine.MIN_LINE_GAP_MS, "line $i at ${starts[i]} after ${starts[i - 1]}")
        }
    }

    /** A first word heard as another word is no opening: the line is not started on it. */
    @Test
    fun `a line whose first word was misheard is not started on it`() {
        val lines = listOf(line(10_000, "alphax bravoy charliez"), line(14_000, "deltax echoy foxtrotz"))
        val words = listOf(
            heard("olfa", 10_900), heard("bravoy", 11_380), heard("charliez", 12_460),
            heard("deltax", 14_000), heard("echoy", 15_080), heard("foxtrotz", 16_160),
        )

        val result = layer(lines, words)

        assertEquals(1, result.snapped, "only the line heard from its own first word")
        assertTrue(result.shiftsMs[0] != 900L, "the misheard word started the line")
        assertEquals(0L, result.shiftsMs[1])
    }
}
