package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The sheets the wrong-sheet judge must never throw away: a right sheet heard
 * with transcription errors, a right sheet whose song opens after a long
 * instrumental, and a Korean sheet with English hooks. Every token is a
 * synthetic identifier (letters/digits), never lyric text.
 */
class LyricsMismatchRightSheetTest {

    private fun words(seed: Int, count: Int): List<String> = List(count) { "t${seed}x$it" }

    private fun sheet(seed: Int, lineCount: Int = 24, everyMs: Long = 5_000L): List<LyricLine> =
        words(seed, lineCount * 5).chunked(5).mapIndexed { i, line ->
            LyricLine(5_000L + i * everyMs, line.joinToString(" "))
        }

    private fun sung(lines: List<LyricLine>): List<LyricsWordAlign.Heard> =
        lines.flatMap { line ->
            line.text.split(' ').mapIndexed { i, word -> word to line.timeMs + i * 800L }
        }.map { (word, at) -> LyricsWordAlign.Heard(word, at, at + 600L) }

    @Test
    fun aRightSheetWithTranscriptionErrorsIsKept() {
        val lines = sheet(7)
        // Every fourth token is misheard as another identifier; line openings stay.
        val heard = sung(lines).mapIndexed { index, word ->
            if (index % 4 == 3) word.copy(text = "zq0") else word
        }

        assertFalse(LyricsMismatch.judgeSheet(lines, heard).rejected)
    }

    @Test
    fun aLongInstrumentalIntroDoesNotRejectTheRightSheet() {
        val lines = sheet(11, lineCount = 40)
        // Nothing is heard in the 90 s intro: the transcript covers only the lines
        // from 90 s on, and the rest of the sheet is outside what it could judge.
        val heard = sung(lines.drop(18))

        assertFalse(LyricsMismatch.judgeSheet(lines, heard).rejected)
    }

    @Test
    fun aKoreanSheetWithEnglishHooksIsKept() {
        val hangul = listOf("가", "나", "다", "라", "마", "바", "사", "아", "자", "차", "카", "타")
        val lines = (0 until 24).map { index ->
            val text = if (index % 4 == 3) {
                "hook${index}01 hook${index}02"
            } else {
                val at = (index * 3) % hangul.size
                "${hangul[at]}${index}01 ${hangul[(at + 1) % hangul.size]}${index}02 " +
                    "${hangul[(at + 2) % hangul.size]}${index}03 ${hangul[(at + 3) % hangul.size]}${index}04"
            }
            LyricLine(5_000L + index * 5_000L, text)
        }
        val heard = sung(lines).mapIndexed { index, word ->
            if (index % 7 == 6) word.copy(text = "zq0") else word
        }

        assertFalse(LyricsMismatch.judgeSheet(lines, heard).rejected)
    }
}
