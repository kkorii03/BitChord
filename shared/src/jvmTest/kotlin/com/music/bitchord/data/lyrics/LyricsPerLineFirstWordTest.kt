package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** The measured line-start rule: skip coherent sheets, else move a line to its first heard word. */
class LyricsPerLineFirstWordTest {

    private fun line(at: Long, text: String) = LyricLine(timeMs = at, text = text)

    private fun heardLine(line: LyricLine, residualMs: Long): List<LyricsWordAlign.Heard> {
        var position = 0
        return line.text.split(" ").map { text ->
            val start = line.timeMs + residualMs + position * 180L
            position += LyricsWordAlign.fold(text).length
            LyricsWordAlign.Heard(text, start, start + 200L)
        }
    }

    private fun layer(lines: List<LyricLine>, heard: List<LyricsWordAlign.Heard>) =
        LyricsPerLine.computeFirstWord(lines, heard, heardAudioKey = 1, currentAudioKey = 1)

    private val alphabet = "alpha bravo charlie delta echoes foxtrot golfing hotels indigo juliet kilos lima".split(" ")

    private fun threeWordLine(at: Long, index: Int) =
        line(at, "${alphabet[index % 12]}x ${alphabet[(index + 1) % 12]}y ${alphabet[(index + 2) % 12]}z")

    @Test
    fun `a coherent sheet is left alone`() {
        val lines = List(8) { threeWordLine(20_000L + it * 12_000L, it) }
        val heard = lines.flatMap { heardLine(it, 0L) }

        assertEquals(List(8) { 0L }, layer(lines, heard).shiftsMs)
    }

    @Test
    fun `an incoherent sheet moves an opening-matched three-word line to its first word`() {
        val lines = List(8) { threeWordLine(20_000L + it * 12_000L, it) }
        val heard = lines.flatMapIndexed { index, line ->
            heardLine(line, if (index < 4) 0L else 3_000L)
        }

        assertEquals(List(4) { 0L } + List(4) { 3_000L }, layer(lines, heard).shiftsMs)
    }

    @Test
    fun `a line whose first matched word is not the opening does not move`() {
        // The opening word was not heard: the first matched word sits five letters in.
        val offsetLine = line(20_000L, "alpha bravo charlie delta")
        val offsetHeard = listOf(
            LyricsWordAlign.Heard("bravo", 22_000L, 22_200L),
            LyricsWordAlign.Heard("charlie", 22_900L, 23_100L),
            LyricsWordAlign.Heard("delta", 24_160L, 24_360L),
        )
        val lines = listOf(offsetLine) + List(5) { threeWordLine(40_000L + it * 12_000L, it) }
        val heard = offsetHeard + lines.drop(1).flatMapIndexed { index, line ->
            heardLine(line, if (index < 3) 0L else 3_000L)
        }

        assertEquals(0L, layer(lines, heard).shiftsMs[0], "a word that is not the opening moved the line")
    }

    @Test
    fun `a move of 30 ms or 4500 ms does not happen`() {
        val lines = List(12) { threeWordLine(20_000L + it * 12_000L, it) }
        val heard = lines.flatMapIndexed { index, line ->
            heardLine(line, when {
                index < 5 -> 0L
                index < 10 -> 3_000L
                index == 10 -> 30L
                else -> 4_500L
            })
        }

        val shifts = layer(lines, heard).shiftsMs

        assertEquals(0L, shifts[10], "a 30 ms move happened")
        assertEquals(0L, shifts[11], "a 4500 ms move happened")
    }

    @Test
    fun `word-timed sheets are identity`() {
        val lines = List(8) { index ->
            val text = "${alphabet[index % 12]}x ${alphabet[(index + 1) % 12]}y ${alphabet[(index + 2) % 12]}z"
            val at = 20_000L + index * 12_000L
            val words = heardLine(line(at, text), 0L).map { LyricWord(it.startMs, it.endMs, it.text) }
            LyricLine(timeMs = at, text = text, words = words)
        }
        val heard = lines.flatMap { line -> line.words.map { LyricsWordAlign.Heard(it.text, it.startMs, it.endMs) } }

        assertEquals(LyricsPerLine.Layer.identity(lines.size), layer(lines, heard))
    }
}
