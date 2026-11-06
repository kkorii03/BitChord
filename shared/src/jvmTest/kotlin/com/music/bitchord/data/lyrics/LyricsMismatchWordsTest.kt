package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Latin letters line up with almost anything: scored on every sheet against every
 * other song's transcript (11-06, 143 pairs, numbers in ship/lanes/audit-sheet.md),
 * "the opening of a line was heard" held for 74% of a wrong sheet's lines, and the
 * judge rejected 1 of the 143. Whether the line's own letters were heard, and whether
 * the lines lie where the sheet says they do, told them apart. The words are made up.
 */
class LyricsMismatchWordsTest {

    private val syllables = listOf(
        "ka", "lo", "mi", "ren", "sa", "tu", "vo", "ne", "ri", "da", "fe", "go", "hu", "ji", "pe", "ba",
    )

    /** Deterministic made-up words, so a test run cannot change its own data. */
    private fun words(seed: Int, count: Int): List<String> {
        var state = seed
        fun next(limit: Int): Int {
            state = (state * 1_103_515_245 + 12_345) and 0x7fffffff
            return (state ushr 8) % limit
        }
        return List(count) { List(2 + next(2)) { syllables[next(syllables.size)] }.joinToString("") }
    }

    private fun sheet(seed: Int, lineCount: Int = 24): List<LyricLine> =
        words(seed, lineCount * 5).chunked(5).mapIndexed { i, line ->
            LyricLine(5_000L + i * 5_000L, line.joinToString(" "))
        }

    private fun sung(lines: List<LyricLine>, keepEvery: Int = 1): List<LyricsWordAlign.Heard> =
        lines.flatMap { line ->
            line.text.split(' ').mapIndexed { i, word -> word to line.timeMs + i * 800L }
        }.filterIndexed { i, _ -> keepEvery == 1 || i % keepEvery != 0 }
            .map { (word, at) -> LyricsWordAlign.Heard(word, at, at + 600L) }

    @Test
    fun aLatinSheetOfAnotherSongIsRejected() {
        val verdict = LyricsMismatch.judgeSheet(sheet(1), sung(sheet(2)))
        assertTrue(verdict.heardWords >= 100)
        assertTrue(verdict.rejected, "placed ${verdict.matchedLines}/${verdict.totalLines}")
    }

    @Test
    fun theRightSheetIsKept() {
        assertFalse(LyricsMismatch.judgeSheet(sheet(1), sung(sheet(1))).rejected)
    }

    @Test
    fun theRightSheetIsKeptWhenAThirdOfTheWordsWereMissed() {
        assertFalse(LyricsMismatch.judgeSheet(sheet(1), sung(sheet(1), keepEvery = 3)).rejected)
    }

    @Test
    fun aShortTranscriptIsNoEvidenceForThisRule() {
        val verdict = LyricsMismatch.judgeSheet(sheet(1, lineCount = 8), sung(sheet(2, lineCount = 8)).take(60))
        assertTrue(verdict.heardWords < 100)
        // Too little to read the words by: only the old "barely any line opened" rule may speak.
        assertFalse(verdict.rejected && verdict.matchedLines * 100 >= 15 * verdict.totalLines)
    }
}
