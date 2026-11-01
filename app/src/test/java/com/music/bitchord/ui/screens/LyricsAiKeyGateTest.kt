package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A key that the service refused is no more usable than a blank one. The AI
 * rows stayed enabled for a rejected key and silently did nothing; these pin
 * the gate to the rejected state as well as to blank keys.
 */
class LyricsAiKeyGateTest {

    @Test
    fun `a Gemini key the service refused is not a usable key for AI lyrics`() {
        assertTrue(aiLyricsKeyMissing("gemini-key", geminiKeyRejected = true))
        assertFalse(aiLyricsKeyMissing("gemini-key", geminiKeyRejected = false))
        assertTrue(aiLyricsKeyMissing("", geminiKeyRejected = true))
    }

    @Test
    fun `a refused key is missing only while no other AI timing key is usable`() {
        assertTrue(
            aiTimingKeyMissing(
                "gemini-key",
                "deepgram-key",
                geminiKeyRejected = true,
                deepgramKeyRejected = true,
            ),
        )
        assertFalse(
            aiTimingKeyMissing(
                "gemini-key",
                "deepgram-key",
                geminiKeyRejected = true,
                deepgramKeyRejected = false,
            ),
        )
        assertFalse(
            aiTimingKeyMissing(
                "gemini-key",
                "deepgram-key",
                geminiKeyRejected = false,
                deepgramKeyRejected = true,
            ),
        )
        assertFalse(
            aiTimingKeyMissing(
                "gemini-key",
                "deepgram-key",
                geminiKeyRejected = false,
                deepgramKeyRejected = false,
            ),
        )
    }
}
