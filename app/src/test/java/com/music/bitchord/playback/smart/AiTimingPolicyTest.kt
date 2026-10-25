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
    fun failedQuickEnginesLeaveWhisperOnceAfterGivingUp() {
        val knowledge = AiTimingKnowledge(
            attemptedKey = key,
            attemptedEngines = setOf("Deepgram", "Gemini"),
        )
        assertEquals(listOf("Whisper"), AiTimingPolicy.nextEngines(knowledge, key, manual = false, measurementGaveUp = true))
        val allSpent = knowledge.copy(attemptedEngines = setOf("Deepgram", "Whisper", "Gemini"))
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(allSpent, key, manual = false, measurementGaveUp = true))
    }

    @Test
    fun lowAgreementAnswerLetsTheNextEngineTryOnce() {
        val knowledge = AiTimingKnowledge(answerKey = key, answerEngine = "Gemini", answerMatch = 20)
        assertFalse(AiTimingPolicy.isAccepted(knowledge, key))
        assertEquals(
            listOf("Deepgram", "Whisper"),
            AiTimingPolicy.nextEngines(knowledge, key, manual = false, measurementGaveUp = true),
        )
        val spent = knowledge.copy(attemptedKey = key, attemptedEngines = setOf("Deepgram", "Whisper"))
        assertEquals(emptyList<String>(), AiTimingPolicy.nextEngines(spent, key, manual = false, measurementGaveUp = true))
    }

    @Test
    fun legacyWhisperKeyAndGeminiFailureCountAsSpent() {
        assertEquals(
            listOf("Deepgram", "Gemini"),
            AiTimingPolicy.nextEngines(
                AiTimingKnowledge(whisperKey = key),
                key,
                manual = false,
                measurementGaveUp = true,
            ),
        )
        assertEquals(
            listOf("Deepgram", "Whisper"),
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
        // The same entry on the video path still gets its one Whisper upgrade.
        assertEquals(
            listOf("Whisper"),
            AiTimingPolicy.nextEngines(accepted, key, manual = false, measurementGaveUp = true),
        )
    }

    @Test
    fun songCheckLeavesTheManualButtonAlone() {
        assertEquals(
            AiTimingPolicy.ALL_ENGINES,
            AiTimingPolicy.nextEngines(AiTimingKnowledge(), key, manual = true, measurementGaveUp = false, songOnly = true),
        )
    }
}
