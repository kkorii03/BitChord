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
        // The candidate's opening is kept whenever there are words to hear it against
        // ([KO] unaligned-words: youtubeWords, or a doubted row's kept words for a null alignment).
        assertTrue(outcome.contains("val unalignedWords = youtubeWords ?: keptWords?.takeIf {"))
        assertTrue(outcome.contains("candidateFetch(context, stream, epoch, keepOpening = unalignedWords != null, onOpeningFull = onOpeningFull)"))
        // Only a chroma match is heard; the guard decides with what was heard.
        assertTrue(outcome.contains("if (youtubeWords != null && verdict.accepted) {"))
        assertTrue(outcome.contains("KoVersionGuard.decide(verdict.accepted, needsWords, words)"))
    }

    // Phone 11-25: an instrumental credited to Various Artists was stored on its backing alone.
    @Test
    fun `a nameless row is asked its words and stored only on them`() {
        assertTrue(outcome.contains("val needsWords = if (nameless || KoVersionGuard.risky(sourceId, trackId)) {"))
        val held = outcome.indexOf("if (nameless && decision == KoVersionGuard.Decision.ACCEPT && !KoVersionGuard.namelessStands(words)) {")
        assertTrue(held >= 0 && held < outcome.indexOf("HqVerificationStore.put(context, youtubeId, sourceId, trackId, verified)"))
        // A row with a doubted credit owes its words before the library may fetch it.
        assertTrue(outcome.contains("guardSeen = !needsWords && !KoUnverifiedRows.contains(sourceId, trackId)"))
        // A file far smaller than its claimed bitrate is measured before anything is compared.
        // [KO] print: another recording is refused before its words are heard, and an old verdict is measured again.
        val other = outcome.indexOf("if (verdict.accepted && otherRecording(")
        assertTrue(other >= 0 && other < outcome.indexOf("if (youtubeWords != null && verdict.accepted) {"))
        assertTrue(outcome.contains("= stored.printChecked && KoVersionGuard.storedVerdictStands("))
        assertTrue(outcome.contains("printChecked = true,"))
        val thin = outcome.indexOf("KoThinRendition.thin(stream.format,")
        assertTrue(thin >= 0 && thin < outcome.indexOf("ChromaAlignment.align("))
    }

    // Review OPT1 (hq-path-second-proof): the automatic look proved its winner in
    // the race, then verified it again; a doubted pair on too few words was
    // downloaded and heard twice within seconds, spending RG1-4's two hearings in one play.
    @Test
    fun `the automatic swap takes the race's own proof of its winner`() {
        val root = File(".").canonicalFile
        val look = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/PlaybackService.kt") }
            .first { it.exists() }
            .readText()
            .replace("\r\n", "\n")
            .substringAfter("val upgradeReport = SourceResolver.SearchReport()")
            .substringBefore("QualityUpgrade.forget(mediaId)")
        assertTrue(look.contains("java.util.IdentityHashMap<SourceStream, HqVerificationStore.Verified>()"))
        assertTrue(look.contains("?.also { koRaceProofs[stream] = it } != null"))
        val answer = look.substringAfter("val answer = if (").substringBefore("} else {")
        // The race's proof first; an unproved winner (the live lookup's) is still verified.
        assertTrue(answer.contains("koRaceProofs[better] ?: HqAudioVerify.verify(this@PlaybackService, mediaId, target, better)"))
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
