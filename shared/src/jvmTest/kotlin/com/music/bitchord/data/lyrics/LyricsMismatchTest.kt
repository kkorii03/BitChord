package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A provider's sheet against the words the sound actually sang. */
class LyricsMismatchTest {

    private val lines = listOf(
        "alpha bravo charlie delta echo",
        "foxtrot golf hotel india juliet",
        "kilo lima mike november oscar",
        "papa puppy romeo sierra tango",
        "uniform victor whiskey xray yankee",
        "amber birch cedar dune ember",
        "frost glade hazel ivory juniper",
        "kelp lotus maple nectar olive",
        "pebble reed sage thistle umber",
        "violet willow yarrow aspen bloom",
    )

    private fun heard(text: String, at: Long) = LyricsWordAlign.Heard(text, at, at + 300)

    private fun sound(vararg sungLines: Int): List<LyricsWordAlign.Heard> {
        val words = mutableListOf<LyricsWordAlign.Heard>()
        var at = 1_000L
        for (index in sungLines) {
            lines[index].split(' ').forEach { word ->
                words += heard(word, at)
                at += 400L
            }
        }
        repeat(40) {
            words += heard("zzzz", at)
            at += 400L
        }
        return words
    }

    @Test
    fun aSheetTheSoundBarelySingsIsRejected() {
        val verdict = LyricsMismatch.judge(lines, sound(0))
        assertEquals(45, verdict.heardWords)
        assertTrue(verdict.matchedLines <= 1)
        assertEquals(10, verdict.totalLines)
        assertTrue(verdict.rejected)
    }

    @Test
    fun aSheetWithEnoughLinesHeardIsKept() {
        val verdict = LyricsMismatch.judge(lines, sound(0, 2, 4))
        assertEquals(3, verdict.matchedLines)
        assertEquals(10, verdict.totalLines)
        assertFalse(verdict.rejected)
    }

    /** NEON (A2O LITE, rlYOsUfpi3M), lrc.red: the 17 lines of the 40 s song check (phone 11-06, match=47%). */
    private val neonOpening = listOf(
        "Neon and beyond, that's what we on and beyond, yup",
        "Neon and beyond, that's what we on and beyond, yup",
        "Neon and beyond, that's what we on and beyond, yup",
        "Neon and beyond, that's what we on and",
        "Grab a hold on to something tight, A2O THE LITE",
        "감각을 마비 충격 그 자체",
        "Feel the energy and follow the ‘LITE’",
        "I know you like that, right",
        "시선 고정 Li-li-like that",
        "이미 가진 포스",
        "Here we come, come, here we come, come",
        "Here the drum rump, uh, pum, pum",
        "분석 중",
        "Hit the ground, run, do the math, hun",
        "They ain’t never seen another one",
        "Oh, what? What you gone do, waa",
        "지켜봐 봐 Zalpha 올라가 New Gen",
    )

    @Test
    fun aMixedSheetListenedToInEnglishCountsOnlyTheLinesEnglishCanSpell() {
        assertEquals("en", Deepgram.languageFor(neonOpening))
        // Five lines open in Hangul; an English transcript can never place them.
        assertEquals(12, Deepgram.spellableLines(neonOpening, "en"))
        assertEquals(12, Deepgram.spellableLines(neonOpening, "multi"))
        // Listening in Korean, every line may be written either way: nothing dropped.
        assertEquals(17, Deepgram.spellableLines(neonOpening, "ko"))
        assertEquals(1, Deepgram.spellableLines(listOf("...", "시선"), "en"))
    }

    @Test
    fun spellingNoiseFoldsAway() {
        // Curly apostrophes, quotes, case, punctuation and full-width letters are not a mismatch.
        assertEquals(LyricsWordAlign.fold("they aint"), LyricsWordAlign.fold("They ain’t"))
        assertEquals(LyricsWordAlign.fold("follow the lite"), LyricsWordAlign.fold("Follow the ‘LITE’!"))
        assertEquals("neon", LyricsWordAlign.fold("ＮＥＯＮ"))
    }

    @Test
    fun aMixedSheetHeardOnlyInItsEnglishIsKeptNotSwapped() {
        // The transcript skips one chorus repeat and every Hangul line, as Whisper in English did.
        val sung = neonOpening.withIndex()
            .filter { (i, text) -> i != 1 && Deepgram.spellableLines(listOf(text), "en") == 1 }
        val words = mutableListOf<LyricsWordAlign.Heard>()
        var at = 0L
        for ((_, text) in sung) {
            text.split(' ', ',').filter { it.isNotBlank() }.forEach { word ->
                words += heard(word, at)
                at += 300L
            }
        }
        val verdict = LyricsMismatch.judge(neonOpening, words)
        assertTrue(verdict.heardWords >= LyricsMismatch.MIN_HEARD_WORDS)
        assertTrue(verdict.matchedLines >= 8, "matched ${verdict.matchedLines}")
        assertFalse(verdict.rejected)
    }

    @Test
    fun tooLittleSoundIsNoVerdict() {
        val words = lines[0].split(' ').mapIndexed { index, word -> heard(word, 1_000L + index * 400L) }
        val verdict = LyricsMismatch.judge(lines, words)
        assertEquals(5, verdict.heardWords)
        assertFalse(verdict.rejected)
        assertFalse(LyricsMismatch.judge(lines, emptyList()).rejected)
    }
}
