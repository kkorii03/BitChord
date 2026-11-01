package com.music.bitchord.data.lyrics

import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole-request budget must not starve the Google fallback. Before the
 * Gemini loop got its own share, a slow model loop could consume every second
 * of the total and leave a valid translation unanswered.
 */
class LyricsTranslationBudgetTest {

    private val lines = listOf(LyricLine(timeMs = 0, text = "hello"))

    private fun answer(text: String, engine: String = "google") =
        LyricsTranslation.CachedTranslation(
            sourceLanguage = "en",
            targetLanguage = "ko",
            texts = listOf(text),
            engine = engine,
        )

    @Test
    fun aSlowGeminiLoopStillLeavesTheGoogleFallbackItsShare() = runBlocking {
        val enabled = LyricsTranslation.geminiEnabled
        val key = LyricsTranslation.geminiApiKey
        LyricsTranslation.geminiEnabled = true
        LyricsTranslation.geminiApiKey = "test-key"
        try {
            var googleCalled = false
            val result = LyricsTranslation.translateWith(
                trackId = "slow-gemini",
                lines = lines,
                targetLanguageTag = "ko",
                keep = false,
                deadlines = LyricsTranslation.TranslationDeadlines(totalMs = 240, googleReserveMs = 80),
                gemini = { _, _, _ ->
                    delay(260)
                    answer("from gemini", "gemini")
                },
                google = { _, _, _ ->
                    googleCalled = true
                    delay(20)
                    answer("from google")
                },
            )

            val translated = assertIs<LyricsTranslation.Result.Translated>(result)
            assertEquals(LyricsTranslation.Engine.GOOGLE, translated.engine)
            assertEquals("from google", translated.lines.single().text)
            assertTrue(googleCalled, "the fallback must still run after a slow Gemini attempt")
        } finally {
            LyricsTranslation.geminiEnabled = enabled
            LyricsTranslation.geminiApiKey = key
        }
    }

    @Test
    fun aValidGeminiAnswerWithinItsShareIsUsedWithoutWaitingForGoogle() = runBlocking {
        val enabled = LyricsTranslation.geminiEnabled
        val key = LyricsTranslation.geminiApiKey
        LyricsTranslation.geminiEnabled = true
        LyricsTranslation.geminiApiKey = "test-key"
        try {
            var googleCalled = false
            val result = LyricsTranslation.translateWith(
                trackId = "slow-but-valid-gemini",
                lines = lines,
                targetLanguageTag = "ko",
                keep = false,
                deadlines = LyricsTranslation.TranslationDeadlines(totalMs = 400, googleReserveMs = 100),
                gemini = { _, _, _ ->
                    delay(150)
                    answer("from gemini", "gemini")
                },
                google = { _, _, _ ->
                    googleCalled = true
                    answer("from google")
                },
            )

            val translated = assertIs<LyricsTranslation.Result.Translated>(result)
            assertEquals(LyricsTranslation.Engine.GEMINI, translated.engine)
            assertEquals("from gemini", translated.lines.single().text)
            assertTrue(!googleCalled, "a complete Gemini answer must be used as-is")
        } finally {
            LyricsTranslation.geminiEnabled = enabled
            LyricsTranslation.geminiApiKey = key
        }
    }

    @Test
    fun aKeptAnswerIsWrittenToDiskAndReadBackByThePublicEntryPoint() = runBlocking {
        val directory = Files.createTempDirectory("bitchord-translation-kept").toFile()
        val oldKeep = LyricsTranslation.keepDir
        val enabled = LyricsTranslation.geminiEnabled
        try {
            LyricsTranslation.keepDir = directory
            LyricsTranslation.geminiEnabled = false

            val seed = LyricsTranslation.translateWith(
                trackId = "downloaded-song",
                lines = lines,
                targetLanguageTag = "ko",
                keep = true,
                deadlines = LyricsTranslation.TranslationDeadlines(),
                gemini = { _, _, _ -> null },
                google = { _, _, _ -> answer("saved for offline") },
            )
            assertIs<LyricsTranslation.Result.Translated>(seed)
            assertTrue(
                directory.walkTopDown().any { it.isFile },
                "keep=true must write the answer to the kept directory",
            )

            // A fresh read goes through the same public entry point the player
            // uses; no provider runs because the kept answer is already there.
            val result = LyricsTranslation.translate("downloaded-song", lines, "ko")
            val translated = assertIs<LyricsTranslation.Result.Translated>(result)
            assertTrue(translated.fromCache)
            assertEquals("saved for offline", translated.lines.single().text)
        } finally {
            LyricsTranslation.keepDir = oldKeep
            LyricsTranslation.geminiEnabled = enabled
            directory.deleteRecursively()
        }
    }

    @Test
    fun whenTheWholeBudgetExpiresAKeptTranslationIsStillReturned() = runBlocking {
        val directory = Files.createTempDirectory("bitchord-translation-budget").toFile()
        val oldKeep = LyricsTranslation.keepDir
        val enabled = LyricsTranslation.geminiEnabled
        val key = LyricsTranslation.geminiApiKey
        try {
            LyricsTranslation.keepDir = directory
            LyricsTranslation.geminiEnabled = false

            val seed = LyricsTranslation.translateWith(
                trackId = "kept-song",
                lines = lines,
                targetLanguageTag = "ko",
                keep = true,
                deadlines = LyricsTranslation.TranslationDeadlines(),
                gemini = { _, _, _ -> null },
                google = { _, _, _ -> answer("kept text") },
            )
            assertIs<LyricsTranslation.Result.Translated>(seed)

            LyricsTranslation.geminiEnabled = true
            LyricsTranslation.geminiApiKey = "test-key"
            val result = LyricsTranslation.translateWith(
                trackId = "kept-song",
                lines = lines,
                targetLanguageTag = "ko",
                keep = false,
                deadlines = LyricsTranslation.TranslationDeadlines(totalMs = 80, googleReserveMs = 20),
                gemini = { _, _, _ ->
                    delay(500)
                    answer("late gemini", "gemini")
                },
                google = { _, _, _ ->
                    delay(500)
                    answer("late google")
                },
            )

            val translated = assertIs<LyricsTranslation.Result.Translated>(result)
            assertTrue(translated.fromCache, "the saved translation must survive the expired budget")
            assertEquals("kept text", translated.lines.single().text)
        } finally {
            LyricsTranslation.keepDir = oldKeep
            LyricsTranslation.geminiEnabled = enabled
            LyricsTranslation.geminiApiKey = key
            directory.deleteRecursively()
        }
    }
}
