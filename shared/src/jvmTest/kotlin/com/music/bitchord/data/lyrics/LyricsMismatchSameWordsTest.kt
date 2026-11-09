package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * rs27 231-c (iKraCjaTBVk): VIBE's sheet was rejected on 264 heard words, and
 * the reload showed Bugs' copy of the same text, split into twice as many
 * lines on the same clock, +10 s off. A rejection is about the words, so the
 * copy is refused too; a different text, clock or video is judged on its own.
 */
class LyricsMismatchSameWordsTest {

    private val verdict = LyricsMismatch.Verdict(heardWords = 264, matchedLines = 15, totalLines = 16, rejected = true)

    /** 160 distinct words, 4 per long line, starting at [from]. */
    private fun words(from: Int) = (from until from + 160).map { "w$it" }

    /** VIBE's shape: a label line, then 40 long lines 5.5 s apart. */
    private fun merged(words: List<String>, startMs: Long = 33_100L): List<LyricLine> =
        listOf(LyricLine(32_400L, "Intro)")) +
            words.chunked(4).mapIndexed { i, chunk -> LyricLine(startMs + i * 5_500L, chunk.joinToString(" ")) }

    /** Bugs' shape: the same words as 80 half lines, curly apostrophes and all. */
    private fun split(words: List<String>, startMs: Long = 32_859L): List<LyricLine> =
        words.chunked(2).mapIndexed { i, chunk -> LyricLine(startMs + i * 2_750L, chunk.joinToString(" ") + "’") }

    @Test
    fun anotherProvidersSplitOfARejectedSheetIsRejectedToo() {
        val id = "same-words-231c"
        LyricsMismatch.rememberRejected(id, LyricsSource.VIBE, merged(words(0)), verdict)

        assertTrue(LyricsMismatch.sameWordsRejected(id, split(words(0))))
        // One wrong sheet and its copy are still one wrong sheet: the sound is not blamed.
        assertNull(LyricsMismatch.wrongSound(id))
    }

    @Test
    fun otherTextClockOrVideoIsJudgedOnItsOwn() {
        val id = "same-words-other"
        LyricsMismatch.rememberRejected(id, LyricsSource.VIBE, merged(words(0)), verdict)

        assertFalse(LyricsMismatch.sameWordsRejected(id, split(words(1_000))), "different words")
        assertFalse(LyricsMismatch.sameWordsRejected(id, split(words(0), startMs = 43_000L)), "another clock")
        assertFalse(LyricsMismatch.sameWordsRejected("same-words-elsewhere", split(words(0))), "another video")
        // A sheet that holds only part of the rejected text is not a copy of it.
        assertFalse(LyricsMismatch.sameWordsRejected(id, split(words(0).take(100))), "a fragment")
    }

    @Test
    fun aShortSheetSaysTooLittleToCallItACopy() {
        val id = "same-words-short"
        val few = (0 until 10).map { "w$it" }
        LyricsMismatch.rememberRejected(id, LyricsSource.VIBE, merged(few), verdict)

        assertFalse(LyricsMismatch.sameWordsRejected(id, split(few)))
    }
}
