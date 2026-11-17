package com.music.bitchord.playback.smart

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 11-17 (lane RGA). The verification runs only against a real cache,
 * decoder and engine, so, as in [HqVersionGuardWiringTest], its wiring of the
 * rules KoVersionGuardRgaTest pins is checked on the source.
 *
 *  - RG2-1: words heard to differ on a row nobody doubted skip the swap for the
 *    run and store nothing: no refusal, no cached copy removed.
 *  - RG1-4: a doubted pair accepted on too few words is not measured on every play.
 *  - RG2-5: who hears the kept words is the guard's rule.
 */
class HqVerifyRgaWiringTest {

    private val outcome: String by lazy {
        val root = File(".").canonicalFile
        listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt") }
            .first { it.exists() }
            .readText()
            .substringAfter("private suspend fun verifyOutcome(")
            .substringBefore("private fun Outcome.verifiedOrNull()")
    }

    @Test
    fun `an unasked different verdict is not stored and deletes nothing`() {
        val rejectBranch = outcome.substringAfter("if (decision == KoVersionGuard.Decision.REJECT) {")
        val unasked = rejectBranch.substringAfter("if (!KoVersionGuard.persistsRefusal(verdict.accepted, needsWords, words)) {")
            .substringBefore("return@coroutineScope Outcome.Mismatch")
        assertTrue(unasked.contains("unaskedDifferent.add(key)"))
        assertFalse(unasked.contains("reject("))
        // The memo is asked before anything is fetched, on both sides of the lock.
        assertEquals(2, Regex("""if \(key in unaskedDifferent\) return@""").findAll(outcome).count())
        assertTrue(
            rejectBranch.indexOf("KoVersionGuard.persistsRefusal(") <
                rejectBranch.indexOf("reject(context, youtubeId, sourceId, trackId)"),
        )
    }

    @Test
    fun `a stored verdict is asked through the hearing cap on both sides of the lock`() {
        assertFalse(outcome.contains("!needsWords || it.wordsChecked"))
        assertEquals(2, Regex("""\?\.takeIf \{ storedStands\(it\) \}""").findAll(outcome).count())
        assertTrue(outcome.contains("unsettledHearings = riskyUnsettledHearings[key] ?: 0"))
        val counted = outcome.substringAfter("HqVerificationStore.put(context, youtubeId, sourceId, trackId, verified)")
            .substringBefore("pendingRateLimits.remove(youtubeId)")
        assertTrue(counted.contains("if (needsWords && !verified.wordsChecked)"))
        assertTrue(counted.contains("riskyUnsettledHearings.merge(key, 1)"))
        assertTrue(counted.contains("riskyUnsettledHearings.remove(key)"))
    }

    @Test
    fun `an undoubted row's kept words are heard by the guard's rule`() {
        assertTrue(outcome.contains("KoVersionGuard.hearsKeptWords(needsWords = false, it.seconds, hearer)"))
        // The candidate's opening is kept whenever there are words to hear it against.
        assertTrue(outcome.contains("candidateFetch(context, stream, epoch, keepOpening = youtubeWords != null)"))
        // Only a chroma match is heard; the guard decides with what was heard.
        assertTrue(outcome.contains("if (youtubeWords != null && verdict.accepted) {"))
        assertTrue(outcome.contains("KoVersionGuard.decide(verdict.accepted, needsWords, words)"))
    }

    @Test
    fun `a saved copy is heard only while its pair owes the words, and refused only on other words`() {
        val root = File(".").canonicalFile
        val saved = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt") }
            .first { it.exists() }
            .readText()
            .substringAfter("suspend fun savedCopyHasOtherWords(")
            .substringBefore("private suspend fun hearCachedCopyLocked(")
        val owes = saved.indexOf("if (!owesCachedWords(stored, key)) return false")
        assertTrue(owes >= 0 && owes < saved.indexOf("wordsHearer()"))
        assertTrue(saved.contains("} == KoVersionGuard.CachedCopy.REFUSE"))
    }
}
