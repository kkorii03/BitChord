package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops one track paying for the same lyrics timing repeatedly:
 * an accepted answer is reused, a manual tap still forces an ask, and a failed
 * or low-agreement engine is not asked twice for the same lyrics.
 */
class AiTimingPolicyTest {

    @Test
    fun `a background placement waits for its turn but a tap never does`() {
        assertTrue(AiTimingPolicy.mayStartRequest(automatic = true, running = 1))
        assertFalse(AiTimingPolicy.mayStartRequest(automatic = true, running = 2))
        assertTrue(AiTimingPolicy.mayStartRequest(automatic = false, running = 2))
    }

    private val key = 7

    @Test
    fun acceptedAnswerIsReusedEvenAfterMeasurementGaveUp() {
        val knowledge = AiTimingKnowledge(answerKey = key, answerEngine = "Whisper", answerMatch = 94)
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(knowledge, key, manual = false, measurementGaveUp = true))
        assertFalse(AiTimingPolicy.shouldAskAgain(knowledge, key, manual = false, measurementGaveUp = true))
    }

    @Test
    fun acceptedQuickAnswerGetsOneWhisperUpgradeOnlyAfterMeasurementGaveUp() {
        val knowledge = AiTimingKnowledge(answerKey = key, answerEngine = "Gemini", answerMatch = 100)
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(knowledge, key, manual = false, measurementGaveUp = false))
        assertEquals(listOf("Whisper"), AiTimingPolicy.nextEngines(knowledge, key, manual = false, measurementGaveUp = true))
        val upgraded = knowledge.copy(attemptedKey = key, attemptedEngines = setOf("Whisper"))
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(upgraded, key, manual = false, measurementGaveUp = true))
    }

    @Test
    fun legacyAnswerWithoutAgreementIsAccepted() {
        val knowledge = AiTimingKnowledge(answerKey = key, answerEngine = "Gemini", answerMatch = null)
        assertTrue(AiTimingPolicy.isAccepted(knowledge, key))
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(knowledge, key, manual = false, measurementGaveUp = false))
        val heard = knowledge.copy(whisperKey = key)
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(heard, key, manual = false, measurementGaveUp = true))
    }

    @Test
    fun manualAlwaysForcesAllEngines() {
        val knowledge = AiTimingKnowledge(
            answerKey = key,
            answerEngine = "Whisper",
            answerMatch = 100,
            attemptedKey = key,
            attemptedEngines = setOf("Deepgram", "Whisper", "Gemini"),
        )
        assertEquals(AiTimingPolicy.ALL_ENGINES, AiTimingPolicy.nextEngines(knowledge, key, manual = true, measurementGaveUp = false))
    }

    @Test
    fun differentLyricsAreAskedAgain() {
        val knowledge = AiTimingKnowledge(answerKey = key, answerEngine = "Whisper", answerMatch = 100)
        assertEquals(
            AiTimingPolicy.AHEAD_ENGINES,
            AiTimingPolicy.nextEngines(knowledge, key + 1, manual = false, measurementGaveUp = false),
        )
    }

    @Test
    fun quickEnginesOnlyWhileMeasurementStillMightTimeTheVideo() {
        assertEquals(
            AiTimingPolicy.AHEAD_ENGINES,
            AiTimingPolicy.nextEngines(AiTimingKnowledge(), key, manual = false, measurementGaveUp = false),
        )
        assertEquals(
            AiTimingPolicy.ALL_ENGINES,
            AiTimingPolicy.nextEngines(AiTimingKnowledge(), key, manual = false, measurementGaveUp = true),
        )
    }

    @Test
    fun spentVideoEnginesAreNotAskedAgain() {
        val knowledge = AiTimingKnowledge(
            attemptedKey = key,
            attemptedEngines = setOf("Deepgram Whisper", "Gemini"),
        )
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(knowledge, key, manual = false, measurementGaveUp = true))
        val oneLeft = knowledge.copy(attemptedEngines = setOf("Deepgram Whisper"))
        assertEquals(listOf("Gemini"), AiTimingPolicy.nextEngines(oneLeft, key, manual = false, measurementGaveUp = true))
    }

    @Test
    fun lowAgreementAnswerLetsTheNextEngineTryOnce() {
        val knowledge = AiTimingKnowledge(answerKey = key, answerEngine = "Gemini", answerMatch = 20)
        assertFalse(AiTimingPolicy.isAccepted(knowledge, key))
        assertEquals(
            listOf("Deepgram Whisper"),
            AiTimingPolicy.nextEngines(knowledge, key, manual = false, measurementGaveUp = true),
        )
        val spent = knowledge.copy(attemptedKey = key, attemptedEngines = setOf("Deepgram Whisper"))
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(spent, key, manual = false, measurementGaveUp = true))
    }

    @Test
    fun legacyWhisperKeyAndGeminiFailureCountAsSpent() {
        // The legacy whisperKey named the same Deepgram Whisper request, so it
        // spends both ids and only Gemini is left.
        assertEquals(
            listOf("Gemini"),
            AiTimingPolicy.nextEngines(
                AiTimingKnowledge(whisperKey = key),
                key,
                manual = false,
                measurementGaveUp = true,
            ),
        )
        assertEquals(
            listOf("Deepgram Whisper"),
            AiTimingPolicy.nextEngines(
                AiTimingKnowledge(failedKey = key),
                key,
                manual = false,
                measurementGaveUp = true,
            ),
        )
    }

    @Test
    fun attemptsForOtherLyricsDoNotBlockThese() {
        val knowledge = AiTimingKnowledge(
            attemptedKey = key - 1,
            attemptedEngines = setOf("Deepgram", "Whisper", "Gemini"),
        )
        assertEquals(
            AiTimingPolicy.ALL_ENGINES,
            AiTimingPolicy.nextEngines(knowledge, key, manual = false, measurementGaveUp = true),
        )
    }

    @Test
    fun songCheckSpendsDeepgramWhisperOnly() {
        assertEquals(
            listOf("Deepgram Whisper"),
            AiTimingPolicy.nextEngines(AiTimingKnowledge(), key, manual = false, measurementGaveUp = false, songOnly = true),
        )
        assertEquals(
            AiTimingPolicy.SONG_ENGINES,
            AiTimingPolicy.nextEngines(AiTimingKnowledge(), key, manual = false, measurementGaveUp = true, songOnly = true),
        )
        val spent = AiTimingKnowledge(attemptedKey = key, attemptedEngines = setOf("Deepgram Whisper"))
        assertEquals(
            emptyList<String>(),
            AiTimingPolicy.nextEngines(spent, key, manual = false, measurementGaveUp = true, songOnly = true),
        )
        assertFalse(AiTimingPolicy.shouldAskAgain(spent, key, manual = false, measurementGaveUp = false, songOnly = true))
    }

    @Test
    fun songTriedWithNovaIsCheckedAgainWithWhisper() {
        // 924b631a–5b09cb2a recorded the song check as "Deepgram" (nova-3);
        // those songs must get one Whisper check, not stay marked as spent.
        val novaTried = AiTimingKnowledge(attemptedKey = key, attemptedEngines = setOf("Deepgram"))
        assertEquals(
            AiTimingPolicy.SONG_ENGINES,
            AiTimingPolicy.nextEngines(novaTried, key, manual = false, measurementGaveUp = false, songOnly = true),
        )
        // A Whisper attempt or answer does stop the next request.
        val whisperTried = novaTried.copy(attemptedEngines = setOf("Deepgram", "Deepgram Whisper"))
        assertEquals(
            emptyList<String>(),
            AiTimingPolicy.nextEngines(whisperTried, key, manual = false, measurementGaveUp = false, songOnly = true),
        )
    }

    @Test
    fun songCheckNeverUpgradesToWhisperOrGemini() {
        val accepted = AiTimingKnowledge(answerKey = key, answerEngine = "Deepgram Whisper", answerMatch = 95)
        assertEquals(
            emptyList<String>(),
            AiTimingPolicy.nextEngines(accepted, key, manual = false, measurementGaveUp = true, songOnly = true),
        )
        // A Deepgram Whisper answer is already the model the upgrade would ask.
        assertEquals(
            emptyList<String>(),
            AiTimingPolicy.nextEngines(accepted, key, manual = false, measurementGaveUp = true),
        )
        // A Gemini answer still gets that one upgrade on the video path.
        val gemini = accepted.copy(answerEngine = "Gemini")
        assertEquals(
            listOf("Whisper"),
            AiTimingPolicy.nextEngines(gemini, key, manual = false, measurementGaveUp = true),
        )
    }

    @Test
    fun videoPathUsesDeepgramWhisperInsteadOfNova() {
        assertEquals(
            listOf("Deepgram Whisper", "Gemini"),
            AiTimingPolicy.AHEAD_ENGINES,
        )
        val engines = AiTimingPolicy.nextEngines(
            AiTimingKnowledge(),
            key,
            manual = false,
            measurementGaveUp = false,
        )
        assertEquals(AiTimingPolicy.AHEAD_ENGINES, engines)
        assertFalse(engines.contains("Deepgram"))
    }

    @Test
    fun songCheckLeavesTheManualButtonAlone() {
        assertEquals(
            AiTimingPolicy.ALL_ENGINES,
            AiTimingPolicy.nextEngines(AiTimingKnowledge(), key, manual = true, measurementGaveUp = false, songOnly = true),
        )
    }

    /** vc147 NEON (rlYOsUfpi3M): `allowed=false` with base=none must say the Whisper reply was spent, not accepted. */
    @Test
    fun songSkipReasonTellsSpentFromAccepted() {
        val w = AiTimingPolicy.DEEPGRAM_WHISPER
        val spent = AiTimingKnowledge(attemptedKey = key, attemptedEngines = setOf(w))
        assertEquals("spent", AiTimingPolicy.songSkipReason(spent, key, deepgramUsable = true))
        val refused = AiTimingKnowledge(answerKey = key, answerEngine = w, answerMatch = 50)
        assertEquals("spent", AiTimingPolicy.songSkipReason(refused, key, deepgramUsable = true))
        val accepted = AiTimingKnowledge(answerKey = key, answerEngine = w, answerMatch = 90)
        assertEquals("accepted", AiTimingPolicy.songSkipReason(accepted, key, deepgramUsable = true))
        assertEquals("deepgram-off", AiTimingPolicy.songSkipReason(accepted, key, deepgramUsable = false))
        assertEquals("none", AiTimingPolicy.songSkipReason(AiTimingKnowledge(), key, deepgramUsable = true))
        // Other lyrics: the spent engine was for another text.
        assertEquals("none", AiTimingPolicy.songSkipReason(spent, key + 1, deepgramUsable = true))
    }

    @Test
    fun onlyARealReplySpendsTheSongCheck() {
        assertTrue(AiTimingPolicy.songReplySpends(200))
        assertFalse(AiTimingPolicy.songReplySpends(null))
        assertFalse(AiTimingPolicy.songReplySpends(500))
        assertFalse(AiTimingPolicy.songReplySpends(401))
        assertFalse(AiTimingPolicy.songReplySpends(429))
    }

    @Test
    fun onlyAnAppliedSongCheckMovesTheSheet() {
        assertEquals("moved", AiTimingPolicy.songSheetEffect("applied"))
        for (r in listOf("no-audio", "no-words", "no-answer", "few-words", "no-fit", "refused-match", "refused-shift")) {
            assertEquals(r, "kept", AiTimingPolicy.songSheetEffect(r))
        }
    }

    @Test
    fun aRefusedSongReplyGetsExactlyOneRetry() {
        val w = AiTimingPolicy.DEEPGRAM_WHISPER
        // vc147 NEON: Whisper answered, the fit was refused; the song used to stay uncorrectable.
        val refused = AiTimingKnowledge(attemptedKey = key, attemptedEngines = setOf(w), rejectedKey = key)
        assertTrue(AiTimingPolicy.songRetry(refused, key))
        assertEquals(listOf(w), AiTimingPolicy.nextEngines(refused, key, manual = false, measurementGaveUp = false, songOnly = true))
        // The retry's own reply is marked; no third check.
        val retried = refused.copy(attemptedEngines = AiTimingPolicy.songSpent(listOf(w), w, retry = true).toSet())
        assertFalse(AiTimingPolicy.songRetry(retried, key))
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(retried, key, manual = false, measurementGaveUp = false, songOnly = true))
        assertEquals("spent", AiTimingPolicy.songSkipReason(retried, key, deepgramUsable = true))
        // Spent but not refused (no heard words kept), other lyrics, or accepted: no retry.
        val plainSpent = AiTimingKnowledge(attemptedKey = key, attemptedEngines = setOf(w))
        assertFalse(AiTimingPolicy.songRetry(plainSpent, key))
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(plainSpent, key, manual = false, measurementGaveUp = false, songOnly = true))
        assertFalse(AiTimingPolicy.songRetry(refused.copy(rejectedKey = key + 1), key))
        val accepted = refused.copy(answerKey = key, answerEngine = w, answerMatch = 90)
        assertFalse(AiTimingPolicy.songRetry(accepted, key))
        // Not spent yet: the first check is the ordinary one, not the retry.
        assertFalse(AiTimingPolicy.songRetry(AiTimingKnowledge(rejectedKey = key), key))
    }

    @Test
    fun songCheckStateNamesEachStandingForTheBaseApplyLine() {
        val w = AiTimingPolicy.DEEPGRAM_WHISPER
        val refused = AiTimingKnowledge(attemptedKey = key, attemptedEngines = setOf(w), rejectedKey = key)
        assertEquals("not-run", AiTimingPolicy.songCheckState(AiTimingKnowledge(), key, retryHeld = false))
        assertEquals("held-429", AiTimingPolicy.songCheckState(AiTimingKnowledge(), key, retryHeld = true))
        assertEquals("spent 1/2 retry-next", AiTimingPolicy.songCheckState(refused, key, retryHeld = false))
        assertEquals(
            "spent 1/2 no-retry",
            AiTimingPolicy.songCheckState(refused.copy(rejectedKey = null), key, retryHeld = false),
        )
        // 11-06 rlYOsUfpi3M (NEON): both replies refused (47%, 44%).
        val both = refused.copy(attemptedEngines = setOf(w, AiTimingPolicy.SONG_RETRY))
        assertEquals("spent 2/2", AiTimingPolicy.songCheckState(both, key, retryHeld = false))
        assertEquals(
            "accepted",
            AiTimingPolicy.songCheckState(refused.copy(answerKey = key, answerEngine = w, answerMatch = 90), key, retryHeld = false),
        )
        // Another lyrics text's attempts say nothing about this one.
        assertEquals("not-run", AiTimingPolicy.songCheckState(both, key + 1, retryHeld = false))
    }

    @Test
    fun onlyTheRetryReplyIsMarked() {
        val w = AiTimingPolicy.DEEPGRAM_WHISPER
        assertEquals(listOf(w), AiTimingPolicy.songSpent(emptyList(), w, retry = false))
        // The first check's full pass spends the same engine again without using up the retry.
        assertEquals(listOf(w), AiTimingPolicy.songSpent(listOf(w), w, retry = false))
        assertEquals(listOf(w, AiTimingPolicy.SONG_RETRY), AiTimingPolicy.songSpent(listOf(w), w, retry = true))
        assertEquals(listOf(w, AiTimingPolicy.SONG_RETRY), AiTimingPolicy.songSpent(listOf(w, AiTimingPolicy.SONG_RETRY), w, retry = true))
    }
}
