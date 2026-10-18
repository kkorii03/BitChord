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
}
