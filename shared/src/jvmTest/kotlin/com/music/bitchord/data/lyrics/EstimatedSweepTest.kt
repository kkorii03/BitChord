package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Guessed word timings for lines a provider only stamped by line. */
class EstimatedSweepTest {

    private fun line(text: String, at: Long = 0L, next: Long? = null) =
        LyricLine(timeMs = at, text = text).withEstimatedWords(next)

    @Test
    fun wordSyncedLineIsLeftAlone() {
        val synced = LyricLine(
            timeMs = 1_000,
            text = "사랑해 너를",
            words = listOf(LyricWord(1_000, 1_400, "사랑해"), LyricWord(1_400, 1_800, "너를")),
        )
        assertSame(synced, synced.withEstimatedWords(5_000))
    }

    @Test
    fun gapAndBlankLinesAreLeftAlone() {
        val gap = LyricLine(timeMs = 1_000, text = "")
        val blank = LyricLine(timeMs = 2_000, text = "   ")
        assertSame(gap, gap.withEstimatedWords(5_000))
        assertSame(blank, blank.withEstimatedWords(5_000))
    }

    @Test
    fun timedTranslationIsLeftAlone() {
        val source = LyricLine(
            timeMs = 1_000,
            text = "사랑해",
            words = listOf(LyricWord(1_000, 1_500, "사랑해")),
        )
        val translated = LyricLine(timeMs = 1_000, text = "I love you", timingSource = source)
        assertSame(translated, translated.withEstimatedWords(5_000))
    }

    @Test
    fun koreanLineIsSplitIntoWords() {
        val swept = line("사랑해 너를 정말", at = 10_000, next = 13_000)
        assertEquals(listOf("사랑해", "너를", "정말"), swept.words.map { it.text })
        assertEquals(10_000L, swept.words.first().startMs)
        assertTrue(swept.words.last().endMs <= 12_750L, "end ${swept.words.last().endMs}")
        assertTrue(swept.wordsEstimated)
        assertTrue(swept.isWordSynced)
        assertTrue(!swept.hasKnownEnd)
        assertTrue(swept.growingWords.isEmpty())
    }

    @Test
    fun sweepAdvancesOneCharacterAtATime() {
        val swept = line("사랑해 너를 정말", at = 10_000, next = 13_000)
        var previous = -1f
        var halfToTwo = false
        var twoToThree = false
        var threeToFour = false
        for (t in 9_000L..14_000L step 50) {
            val revealed = swept.revealedChars(t)
            assertTrue(revealed >= previous, "went backwards at $t: $revealed < $previous")
            previous = revealed
            if (revealed > 0.5f && revealed < 1.5f) halfToTwo = true
            if (revealed > 1.5f && revealed < 2.5f) twoToThree = true
            if (revealed > 2.5f && revealed < 3.0f) threeToFour = true
        }
        assertEquals(0f, swept.revealedChars(10_000))
        assertEquals(swept.text.length.toFloat(), swept.revealedChars(13_000))
        assertTrue(halfToTwo)
        assertTrue(twoToThree)
        assertTrue(threeToFour)
    }

    @Test
    fun longInstrumentalDoesNotStretchTheSweep() {
        val swept = line("사랑해", at = 0, next = 30_000)
        assertTrue(swept.words.last().endMs <= 3 * 550 + 1, "end ${swept.words.last().endMs}")
    }

    @Test
    fun statedEndWinsOverTheGap() {
        val swept = LyricLine(timeMs = 1_000, text = "사랑해", sungUntilMs = 5_000)
            .withEstimatedWords(20_000)
        assertEquals(5_000L, swept.words.last().endMs)
    }

    @Test
    fun lastLineUsesTheCap() {
        val swept = line("사랑해", at = 4_000, next = null)
        val expected = 4_000L + (3 * 550)
        assertTrue(abs(swept.words.last().endMs - expected) <= 2, "${swept.words.last().endMs} vs $expected")
    }

    @Test
    fun nextLineOnTheSameStampDoesNotCrash() {
        val swept = line("사랑해 너를", at = 7_000, next = 7_000)
        assertTrue(swept.words.isNotEmpty())
        assertTrue(swept.words.all { it.endMs >= it.startMs && it.startMs >= 7_000 })
    }

    @Test
    fun chineseLineWithoutSpacesIsOneWord() {
        val swept = line("我爱你中国", at = 1_000, next = 5_000)
        assertEquals(1, swept.words.size)
        assertEquals("我爱你中国", swept.words.first().text)
    }

    @Test
    fun englishLineSplitsOnTheSpace() {
        val swept = line("Hello, world!", at = 0, next = 4_000)
        assertEquals(listOf("Hello,", "world!"), swept.words.map { it.text })
        assertEquals(7, swept.wordSpans[1].first)
    }

    @Test
    fun punctuationOnlyLineDoesNotCrash() {
        val swept = line("...", at = 0, next = 2_000)
        assertTrue(swept.words.all { it.endMs >= it.startMs })
    }

    @Test
    fun realWordTimingsStillStateAnEnd() {
        val synced = LyricLine(
            timeMs = 1_000,
            text = "사랑해",
            words = listOf(LyricWord(1_000, 1_400, "사랑해")),
        )
        assertTrue(synced.hasKnownEnd)
    }
}
