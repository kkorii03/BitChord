package com.music.bitchord.playback.smart

import com.music.bitchord.ui.screens.aiLyricsKeyMissing
import com.music.bitchord.ui.screens.aiTimingKeyMissing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

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

    @Test
    fun `audio timeout returns null at exactly the wait boundary`() = runTest {
        assertNull(audioWithin(20_000L) { delay(60_000L); byteArrayOf(1) })
        assertEquals(20_000L, currentTime)
    }

    @Test
    fun `audio completed within wait returns bytes`() = runTest {
        assertArrayEquals(byteArrayOf(1), audioWithin(20_000L) { byteArrayOf(1) })
    }

    @Test
    fun `audio failure returns null`() = runTest {
        assertNull(audioWithin<ByteArray>(20_000L) { error("audio failed") })
    }

    @Test
    fun `audio wait is below transcription deadline`() {
        assertTrue(AI_LYRICS_AUDIO_WAIT_MS <= 30_000L)
    }
}
