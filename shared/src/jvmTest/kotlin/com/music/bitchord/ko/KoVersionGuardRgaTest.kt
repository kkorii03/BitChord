package com.music.bitchord.ko

import com.music.bitchord.ko.KoVersionGuard.Hearer
import com.music.bitchord.ko.KoVersionGuard.Words
import com.music.bitchord.ko.KoVersionGuard.WordsCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review 11-17 (lane RGA): the three rules the verification now asks
 * [KoVersionGuard] instead of deciding inline. Counts only, no lyric text.
 */
class KoVersionGuardRgaTest {

    private fun words(verdict: Words, windowMs: Long = 40_000L) =
        WordsCheck(verdict, referenceWords = 20, candidateWords = 20, shared = 1, windowMs = windowMs)

    // RG2-1: a row nobody doubted is refused for good only by its sound.
    @Test
    fun `words heard to differ on a row nobody doubted are not stored as a refusal`() {
        val different = words(Words.DIFFERENT)
        // Still refused for this play.
        assertEquals(KoVersionGuard.Decision.REJECT, KoVersionGuard.decide(true, needsWords = false, words = different))
        assertFalse(KoVersionGuard.persistsRefusal(chromaAccepted = true, needsWords = false, words = different))
    }

    @Test
    fun `a doubted row's different words and any chroma refusal are stored as before`() {
        val different = words(Words.DIFFERENT)
        assertTrue(KoVersionGuard.persistsRefusal(chromaAccepted = true, needsWords = true, words = different))
        assertTrue(KoVersionGuard.persistsRefusal(chromaAccepted = false, needsWords = false, words = null))
        assertTrue(KoVersionGuard.persistsRefusal(chromaAccepted = false, needsWords = false, words = different))
    }

    // RG1-4: a doubted pair accepted on too few words is measured again only so often.
    @Test
    fun `a doubted pair's unsettled verdict answers once the hearings are spent`() {
        val short = words(Words.UNDECIDED, windowMs = 8_000L)
        assertFalse(KoVersionGuard.settles(short))
        val max = 2
        val answers = (0..3).map { hearings ->
            KoVersionGuard.storedVerdictStands(wordsChecked = false, needsWords = true, unsettledHearings = hearings, maxHearings = max)
        }
        assertEquals(listOf(false, false, true, true), answers)
    }

    @Test
    fun `a settled or undoubted verdict answers at once`() {
        assertTrue(KoVersionGuard.storedVerdictStands(wordsChecked = true, needsWords = true, unsettledHearings = 0, maxHearings = 2))
        assertTrue(KoVersionGuard.storedVerdictStands(wordsChecked = false, needsWords = false, unsettledHearings = 0, maxHearings = 2))
    }

    // RG2-5: who hears the kept words.
    @Test
    fun `a doubted row is heard by any engine, an undoubted one by Groq on enough words`() {
        assertTrue(KoVersionGuard.hearsKeptWords(needsWords = true, keptSeconds = 5.0, hearer = Hearer.DEEPGRAM))
        assertFalse(KoVersionGuard.hearsKeptWords(needsWords = true, keptSeconds = null, hearer = Hearer.GROQ))
        assertFalse(KoVersionGuard.hearsKeptWords(needsWords = true, keptSeconds = 90.0, hearer = Hearer.NONE))

        assertTrue(KoVersionGuard.hearsKeptWords(needsWords = false, keptSeconds = 14.0, hearer = Hearer.GROQ))
        assertFalse(KoVersionGuard.hearsKeptWords(needsWords = false, keptSeconds = 8.0, hearer = Hearer.GROQ))
        assertFalse(KoVersionGuard.hearsKeptWords(needsWords = false, keptSeconds = 90.0, hearer = Hearer.DEEPGRAM))
        assertFalse(KoVersionGuard.hearsKeptWords(needsWords = false, keptSeconds = null, hearer = Hearer.GROQ))
    }
}
