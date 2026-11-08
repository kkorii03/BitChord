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
    }

}
