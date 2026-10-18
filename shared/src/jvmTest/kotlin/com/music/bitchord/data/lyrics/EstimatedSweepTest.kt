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

    private fun parsedSong() = LrcLib.parseLrc(SONG_LRC)

    @Test
    fun wholeSongInvariants() {
        val lines = parsedSong()
        assertEquals(13, lines.size)

        for (i in lines.indices) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            val est = line.withEstimatedWords(next?.timeMs)

            if (line.isGap || line.text.isBlank()) {
                assertSame(line, est, "gap line $i changed")
                continue
            }

            assertTrue(est.words.isNotEmpty(), "line $i has no words")
            assertTrue(est.wordsEstimated, "line $i not marked estimated")
            assertEquals(
                line.text.split(Regex("\\s+")).joinToString(" "),
                est.words.joinToString(" ") { it.text },
                "line $i words differ from text",
            )
            assertEquals(line.timeMs, est.words.first().startMs, "line $i first word start")
            est.words.forEachIndexed { w, word ->
                assertTrue(word.startMs <= word.endMs, "line $i word $w reversed")
                if (w > 0) {
                    assertTrue(
                        est.words[w - 1].startMs <= word.startMs,
                        "line $i word $w starts before the previous",
                    )
                }
            }
            if (next != null && next.timeMs > line.timeMs + 250) {
                assertTrue(
                    est.words.last().endMs <= next.timeMs - 250,
                    "line $i end ${est.words.last().endMs} reaches next ${next.timeMs}",
                )
            }

            val endSample = maxOf(next?.timeMs ?: 0L, est.words.last().endMs) + 20
            var previous = -1f
            var t = line.timeMs - 500
            while (t < endSample) {
                val revealed = est.revealedChars(t)
                assertTrue(revealed >= previous, "line $i went backwards at $t: $revealed < $previous")
                previous = revealed
                if (t < line.timeMs) assertEquals(0f, revealed, "line $i revealed before its stamp at $t")
                t += 20
            }
            val lastRevealed = est.revealedChars(endSample)
            assertTrue(
                lastRevealed >= previous,
                "line $i went backwards at the last sample: $lastRevealed < $previous",
            )
            assertEquals(line.text.length.toFloat(), lastRevealed, "line $i not fully revealed")
        }

        val beforeBreak = lines.indexOfFirst { it.timeMs == 40_000L }
        assertTrue(beforeBreak >= 0)
        val breakNext = lines[beforeBreak + 1]
        assertEquals(65_000L, breakNext.timeMs)
        assertEquals("", breakNext.text)
        val swept = lines[beforeBreak].withEstimatedWords(breakNext.timeMs)
        val syllables = lines[beforeBreak].text.count { !it.isWhitespace() }
        val bound = 550L * (syllables + 1)
        assertTrue(
            swept.words.last().endMs - swept.timeMs <= bound,
            "line before the break stretched to ${swept.words.last().endMs}",
        )
        assertTrue(swept.words.last().endMs < breakNext.timeMs, "line before the break stretched over it")
    }

    @Test
    fun wordSyncedSongIsUntouched() {
        val lines = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "첫 번째",
                words = listOf(LyricWord(1_000, 1_400, "첫"), LyricWord(1_400, 2_000, "번째")),
            ),
            LyricLine(
                timeMs = 5_000,
                text = "Baby I love you",
                words = listOf(
                    LyricWord(5_000, 5_400, "Baby"),
                    LyricWord(5_400, 5_700, "I"),
                    LyricWord(5_700, 6_100, "love"),
                    LyricWord(6_100, 6_800, "you"),
                ),
            ),
            LyricLine(
                timeMs = 9_000,
                text = "마지막",
                words = listOf(LyricWord(9_000, 9_800, "마지막")),
            ),
        )
        lines.forEachIndexed { i, line ->
            assertSame(line, line.withEstimatedWords(lines.getOrNull(i + 1)?.timeMs))
        }
    }

    @Test
    fun estimatedLinesKeepProviderEndUnknown() {
        val lines = parsedSong()
        lines.forEachIndexed { i, line ->
            val est = line.withEstimatedWords(lines.getOrNull(i + 1)?.timeMs)
            assertEquals(line.hasKnownEnd, est.hasKnownEnd, "line $i changed hasKnownEnd")
        }
    }

    private companion object {
        val SONG_LRC = """
            [00:00.00]
            [00:10.00]첫 번째 줄은 한국어로 시작해
            [00:14.00]오늘 밤 Tonight 너와 함께
            [00:18.00]Baby I love you
            [00:22.00]그래, 그렇게...
            [00:26.00]같은 시간에 두 줄이
            [00:26.00]동시에 시작되는 줄
            [00:31.00]멀리서 들려오는 멜로디
            [00:36.00]Second verse in English only
            [00:40.00]이제 곧 긴 간주가 시작돼
            [01:05.00]
            [01:25.00]다시 노래가 이어져
            [01:30.00]마지막 줄 Last line
        """.trimIndent()
    }
}
