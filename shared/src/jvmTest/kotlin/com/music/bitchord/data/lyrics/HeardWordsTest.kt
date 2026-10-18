package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Word timings taken from what a speech model actually heard, rather than guessed. */
class HeardWordsTest {

    private fun line(text: String, at: Long, next: Long? = null) = LyricLine(timeMs = at, text = text)

    private fun heard(text: String, from: Long, to: Long) = LyricsWordAlign.Heard(text, from, to)

    @Test
    fun koreanWordSplitByTheModelIsStillMatched() {
        // Deepgram routinely hears "사랑해" as two words; the line keeps one.
        val result = assertNotNull(
            line("사랑해 너를", at = 10_000, next = 14_000).withHeardWords(
                heard = listOf(
                    heard("사랑", 10_000, 10_300),
                    heard("해", 10_300, 10_600),
                    heard("너를", 10_800, 11_200),
                ),
                nextLineMs = 14_000,
            ),
        )
        assertEquals(listOf("사랑해", "너를"), result.words.map { it.text })
        assertEquals(10_000L, result.words[0].startMs)
        assertEquals(10_600L, result.words[0].endMs)
        assertEquals(10_800L, result.words[1].startMs)
        assertEquals(11_200L, result.words[1].endMs)
        assertTrue(!result.wordsEstimated, "transcript times are not an estimate")
    }

    @Test
    fun englishWordsTakeTheirHeardTimesExactly() {
        val result = assertNotNull(
            line("Hello, world!", at = 0, next = 4_000).withHeardWords(
                heard = listOf(heard("hello", 500, 800), heard("world", 900, 1_200)),
                nextLineMs = 4_000,
            ),
        )
        assertEquals(listOf("Hello,", "world!"), result.words.map { it.text })
        assertEquals(500L, result.words[0].startMs)
        assertEquals(800L, result.words[0].endMs)
        assertEquals(900L, result.words[1].startMs)
        assertEquals(1_200L, result.words[1].endMs)
    }

    @Test
    fun aWordTheModelMissedIsPlacedBetweenItsNeighbours() {
        val result = assertNotNull(
            line("one two three", at = 5_000, next = 9_000).withHeardWords(
                heard = listOf(heard("one", 5_000, 5_300), heard("three", 6_800, 7_200)),
                nextLineMs = 9_000,
            ),
        )
        assertEquals(listOf("one", "two", "three"), result.words.map { it.text })
        val two = result.words[1]
        assertTrue(two.startMs >= result.words[0].endMs, "two starts before one ends: $two")
        assertTrue(two.endMs <= result.words[2].startMs, "two runs into three: $two")
        assertEquals(6_800L, result.words[2].startMs)
        assertTrue(two.endMs > two.startMs, "the missed word got no room: $two")
    }

    @Test
    fun aLineTheModelBarelyHeardFallsBackToTheEstimate() {
        assertNull(
            line("사랑해 너를", at = 10_000, next = 14_000)
                .withHeardWords(listOf(heard("completely different", 10_000, 12_000)), 14_000),
        )
    }

    @Test
    fun wordsOutsideTheStanzaAreIgnored() {
        val result = line("사랑해", at = 10_000, next = 12_000).withHeardWords(
            heard = listOf(
                // Before the stanza, well outside the lead-in.
                heard("사랑해", 0, 1_000),
                // After the next line has already started.
                heard("사랑해", 20_000, 21_000),
            ),
            nextLineMs = 12_000,
        )
        assertNull(result, "nothing in this line's slot was heard")
    }

    @Test
    fun timingsStayInTheSlotAndMonotonic() {
        val text = "사랑해 너를 정말"
        val result = assertNotNull(
            line(text, at = 10_000, next = 14_000).withHeardWords(
                heard = listOf(
                    heard("사랑", 10_100, 10_400),
                    heard("해", 10_400, 10_700),
                    heard("너를", 11_000, 11_400),
                    heard("정말", 12_000, 12_500),
                ),
                nextLineMs = 14_000,
            ),
        )
        assertTrue(result.words.isNotEmpty())
        assertTrue(!result.wordsEstimated)
        result.words.forEach { word ->
            assertTrue(word.startMs >= 10_000L, "${word.text} starts before the line")
            assertTrue(word.endMs <= 14_000L, "${word.text} ends after the slot")
            assertTrue(word.endMs >= word.startMs, "${word.text} runs backwards")
        }
        result.words.zipWithNext().forEach { (a, b) ->
            assertTrue(a.startMs <= b.startMs, "${b.text} starts before ${a.text}")
            assertTrue(a.endMs <= b.startMs, "${b.text} overlaps ${a.text}")
        }
        var previous = -1f
        var at = 9_000L
        while (at <= 14_500L) {
            val revealed = result.revealedChars(at)
            assertTrue(revealed >= previous, "reveal went backwards at $at: $revealed < $previous")
            previous = revealed
            at += 10
        }
        assertEquals(text.length.toFloat(), result.revealedChars(14_500), "line never fully revealed")
    }

    @Test
    fun linesAlreadyWordSyncedKeepTheirOwnTimes() {
        val synced = LyricLine(
            timeMs = 1_000,
            text = "사랑해",
            words = listOf(LyricWord(1_000, 1_400, "사랑해")),
        )
        assertNull(synced.withHeardWords(listOf(heard("사랑해", 1_000, 1_400)), 5_000))
    }

    @Test
    fun aHeardTimeBeforeTheLineIsPulledToItsStart() {
        val result = assertNotNull(
            line("사랑해", at = 10_000, next = 14_000)
                .withHeardWords(listOf(heard("사랑해", 9_700, 10_500)), 14_000),
        )
        assertEquals(10_000L, result.words.first().startMs)
        assertEquals(10_500L, result.words.first().endMs)
    }
}
