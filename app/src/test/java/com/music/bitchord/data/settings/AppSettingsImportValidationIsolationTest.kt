package com.music.bitchord.data.settings

import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.GroqWhisper
import com.music.bitchord.data.lyrics.LyricsTranslation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.JUnitCore

/**
 * Review FLAKE-1 (11-17). All app unit tests share one JVM, so a test that sets
 * an AI key through AppSettings must put the process-wide key back. A leaked
 * Deepgram key made every later LyricsController test take the AI timing path
 * and time out (CI 43993585345), depending on class order.
 */
class AppSettingsImportValidationIsolationTest {

    @Test
    fun `the import tests leave no AI key behind for the next test class`() {
        val deepgram = Deepgram.apiKey
        val groq = GroqWhisper.apiKey
        val gemini = LyricsTranslation.geminiApiKey
        try {
            Deepgram.apiKey = ""
            GroqWhisper.apiKey = ""
            LyricsTranslation.geminiApiKey = ""

            val result = JUnitCore().run(AppSettingsImportValidationTest::class.java)

            assertTrue(result.failures.joinToString { it.toString() }, result.wasSuccessful())
            assertEquals("", Deepgram.apiKey)
            assertEquals("", GroqWhisper.apiKey)
            assertEquals("", LyricsTranslation.geminiApiKey)
        } finally {
            Deepgram.apiKey = deepgram
            GroqWhisper.apiKey = groq
            LyricsTranslation.geminiApiKey = gemini
        }
    }
}
