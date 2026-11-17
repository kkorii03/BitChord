package com.music.bitchord.data.lyrics

import com.music.bitchord.data.lyrics.LyricsTranslation.BatchAnswer
import com.music.bitchord.data.lyrics.LyricsTranslation.TextSlot
import com.music.bitchord.ko.KoLyricsTranslation
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Review X2: a sheet every Gemini model answers 200 with nothing usable (one
 * line short, a safety stop) was sent to every model again on each play, and
 * the Google text waited behind those whole-song generations each time.
 */
class GeminiUnusableMemoTest {

    private val slots = LyricsTranslation.flatten(
        listOf(LyricLine(timeMs = 0, text = "memo line one"), LyricLine(timeMs = 2_000, text = "memo line two")),
    )

    @BeforeTest
    fun clear() = KoLyricsTranslation.forgetUnusableForTest()

    @AfterTest
    fun clearAfter() = KoLyricsTranslation.forgetUnusableForTest()

    @Test
    fun `a sheet every model answered unusably is not sent again`() = runBlocking<Unit> {
        var asks = 0
        val unusable: suspend (List<TextSlot>, String, () -> Unit) -> BatchAnswer? = { _, _, allUnusable ->
            asks++
            allUnusable()
            null
        }
        assertNull(KoLyricsTranslation.geminiEntry("memo-unusable", slots, "ko", unusable))
        assertNull(KoLyricsTranslation.geminiEntry("memo-unusable", slots, "ko", unusable))
        assertEquals(1, asks)
    }

    @Test
    fun `a network failure or a quota refusal is asked again`() = runBlocking<Unit> {
        var asks = 0
        val failed: suspend (List<TextSlot>, String, () -> Unit) -> BatchAnswer? = { _, _, _ ->
            asks++
            null
        }
        KoLyricsTranslation.geminiEntry("memo-failed", slots, "ko", failed)
        KoLyricsTranslation.geminiEntry("memo-failed", slots, "ko", failed)
        assertEquals(2, asks)
    }

    @Test
    fun `another sheet is still asked`() = runBlocking<Unit> {
        val unusable: suspend (List<TextSlot>, String, () -> Unit) -> BatchAnswer? = { _, _, allUnusable ->
            allUnusable()
            null
        }
        KoLyricsTranslation.geminiEntry("memo-first", slots, "ko", unusable)

        var asked = false
        val answer = KoLyricsTranslation.geminiEntry("memo-second", slots, "ko") { _, _, _ ->
            asked = true
            BatchAnswer(translations = listOf("하나", "둘"), sourceLanguage = "en", sourceWeight = 2)
        }
        assertEquals(true, asked)
        assertNotNull(answer)
    }
}
