package com.music.bitchord.playback.smart

import com.music.bitchord.ui.screens.aiLyricsKeyMissing
import com.music.bitchord.ui.screens.aiTimingKeyMissing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI lyric rows only do work when a speech key is present. A switch that
 * looks on while the transcription silently returns null is the bug this pins.
 */
class AiLyricsAvailabilityTest {

    @Test
    fun `a missing Gemini key is a missing key, not the off switch`() {
        assertEquals(
            AiLyricsSkipReason.NO_KEY,
            aiLyricsSkipReason(enabled = true, geminiKeyUsable = false),
        )
    }

    @Test
    fun `the off switch is reported separately from a missing key`() {
        assertEquals(
            AiLyricsSkipReason.DISABLED,
            aiLyricsSkipReason(enabled = false, geminiKeyUsable = true),
        )
        assertNull(aiLyricsSkipReason(enabled = true, geminiKeyUsable = true))
    }

    @Test
    fun `AI lyric transcription needs a Gemini key`() {
        assertTrue(aiLyricsKeyMissing(""))
        assertFalse(aiLyricsKeyMissing("gemini-key"))
    }

    @Test
    fun `AI timing can use either Gemini or Deepgram`() {
        assertTrue(aiTimingKeyMissing("", ""))
        assertFalse(aiTimingKeyMissing("gemini-key", ""))
        assertFalse(aiTimingKeyMissing("", "deepgram-key"))
        assertFalse(aiTimingKeyMissing("gemini-key", "deepgram-key"))
    }
}
