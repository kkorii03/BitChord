package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.SongCheckWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 11-07 phone fsCxgDq2T9k: spent by older code, no answer, no words kept, 6.8 s late for good. */
class LyricsTimingSpentBeforeTest {
    private val key = 7
    private val engine = LyricsTiming.ENGINE_DEEPGRAM_WHISPER

    private fun engines(entry: LyricsTiming.Entry) = AiTimingPolicy.nextEngines(
        LyricsTiming.knowledge(entry),
        key,
        manual = false,
        measurementGaveUp = true,
        songOnly = true,
    )

    @Test
    fun `an engine spent by older code with nothing to show gets one more turn`() {
        val entry = LyricsTiming.Entry(aiAttemptedKey = key, aiAttemptedEngines = listOf(engine))
        assertEquals(listOf(engine), engines(entry))
    }

    @Test
    fun `an engine spent by this code stays spent`() {
        val entry = LyricsTiming.Entry(
            aiAttemptedKey = key,
            aiAttemptedEngines = listOf(engine),
            refitVersion = SongCheckWindow.FIT_VERSION,
        )
        assertTrue(engines(entry).isEmpty())
    }
}
