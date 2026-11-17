package com.music.bitchord.data.lyrics

import com.music.bitchord.ko.KoLyricsTranslation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Review X1: while Gemini rests after quota refusals (5 minutes, or until the
 * Pacific-midnight reset), its translation already on disk must still be the
 * one shown. Gated on "ready", the rest sent every song straight to Google,
 * whose wording differs and which costs a request.
 */
class LyricsTranslationRestCacheTest {

    private val lines = listOf(LyricLine(timeMs = 0, text = "hello"))

    private fun answer(text: String, engine: String) =
        LyricsTranslation.CachedTranslation(
            sourceLanguage = "en",
            targetLanguage = "ko",
            texts = listOf(text),
            engine = engine,
        )

    private fun <T> resting(block: () -> T): T {
        val enabled = LyricsTranslation.geminiEnabled
        val key = LyricsTranslation.geminiApiKey
        val until = KoLyricsTranslation.geminiCooldownUntil
        LyricsTranslation.geminiEnabled = true
        LyricsTranslation.geminiApiKey = "rest-test-key"
        KoLyricsTranslation.geminiCooldownUntil = System.currentTimeMillis() + 60 * 60 * 1000L
        try {
            return block()
        } finally {
            LyricsTranslation.geminiEnabled = enabled
            LyricsTranslation.geminiApiKey = key
            KoLyricsTranslation.geminiCooldownUntil = until
        }
    }

    @Test
    fun `a saved Gemini translation is shown while Gemini rests`() = resting {
        runBlocking {
            var googleCalls = 0
            val result = LyricsTranslation.translateWith(
                trackId = "rested-song",
                lines = lines,
                targetLanguageTag = "ko",
                keep = false,
                deadlines = LyricsTranslation.TranslationDeadlines(),
                gemini = { _, _, _ -> answer("from gemini cache", "gemini") },
                google = { _, _, _ ->
                    googleCalls++
                    answer("from google", "google")
                },
            )

            val translated = assertIs<LyricsTranslation.Result.Translated>(result)
            assertEquals(LyricsTranslation.Engine.GEMINI, translated.engine)
            assertEquals("from gemini cache", translated.lines.single().text)
            assertEquals(0, googleCalls)
        }
    }

    @Test
    fun `nothing saved while resting still falls back to Google`() = resting {
        runBlocking {
            val result = LyricsTranslation.translateWith(
                trackId = "rested-new-song",
                lines = lines,
                targetLanguageTag = "ko",
                keep = false,
                deadlines = LyricsTranslation.TranslationDeadlines(),
                gemini = { _, _, _ -> null },
                google = { _, _, _ -> answer("from google", "google") },
            )
            val translated = assertIs<LyricsTranslation.Result.Translated>(result)
            assertEquals(LyricsTranslation.Engine.GOOGLE, translated.engine)
        }
    }

    @Test
    fun `Gemini switched off is never asked`() {
        val enabled = LyricsTranslation.geminiEnabled
        LyricsTranslation.geminiEnabled = false
        try {
            runBlocking {
                var geminiAsked = false
                LyricsTranslation.translateWith(
                    trackId = "gemini-off",
                    lines = lines,
                    targetLanguageTag = "ko",
                    keep = false,
                    deadlines = LyricsTranslation.TranslationDeadlines(),
                    gemini = { _, _, _ ->
                        geminiAsked = true
                        answer("from gemini", "gemini")
                    },
                    google = { _, _, _ -> answer("from google", "google") },
                )
                assertFalse(geminiAsked)
            }
        } finally {
            LyricsTranslation.geminiEnabled = enabled
        }
    }
}
