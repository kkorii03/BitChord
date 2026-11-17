package com.music.bitchord.data.lyrics

import com.music.bitchord.data.lyrics.LyricsTranslation.BatchAnswer
import com.music.bitchord.data.lyrics.LyricsTranslation.TextSlot
import com.music.bitchord.ko.KoLyricsTranslation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Review X7: auto-translate, the ahead pass and the lock screen can ask for the
 * same sheet while the first whole-song Gemini generation is still running.
 * Each one missed the cache and sent its own request.
 */
class GeminiInFlightShareTest {

    private val slots = LyricsTranslation.flatten(
        listOf(LyricLine(timeMs = 0, text = "share line one"), LyricLine(timeMs = 2_000, text = "share line two")),
    )

    private fun answer(s: List<TextSlot>) =
        BatchAnswer(translations = s.map { "번역 ${it.text}" }, sourceLanguage = "en", sourceWeight = s.size)

    @BeforeTest
    fun clear() = KoLyricsTranslation.forgetUnusableForTest()

    @AfterTest
    fun clearAfter() = KoLyricsTranslation.forgetUnusableForTest()

    @Test
    fun `two asks for the same sheet at once send one request`() = runBlocking<Unit> {
        var asks = 0
        val slow: suspend (List<TextSlot>, String, () -> Unit) -> BatchAnswer? = { s, _, _ ->
            asks++
            delay(200)
            answer(s)
        }
        val first = async { KoLyricsTranslation.geminiEntry("inflight-same", slots, "ko", slow) }
        val second = async { KoLyricsTranslation.geminiEntry("inflight-same", slots, "ko", slow) }

        assertNotNull(first.await())
        assertNotNull(second.await())
        assertEquals(1, asks)
    }

    @Test
    fun `a waiter asks itself when the first ask came back with nothing`() = runBlocking<Unit> {
        var asks = 0
        val flaky: suspend (List<TextSlot>, String, () -> Unit) -> BatchAnswer? = { s, _, _ ->
            asks++
            delay(100)
            if (asks == 1) null else answer(s)
        }
        val first = async { KoLyricsTranslation.geminiEntry("inflight-flaky", slots, "ko", flaky) }
        val second = async { KoLyricsTranslation.geminiEntry("inflight-flaky", slots, "ko", flaky) }

        assertNull(first.await())
        assertNotNull(second.await())
        assertEquals(2, asks)
    }

    @Test
    fun `different sheets are not held behind each other`() = runBlocking<Unit> {
        var running = 0
        var overlapped = false
        val slow: suspend (List<TextSlot>, String, () -> Unit) -> BatchAnswer? = { s, _, _ ->
            running++
            if (running > 1) overlapped = true
            delay(100)
            running--
            answer(s)
        }
        val one = async { KoLyricsTranslation.geminiEntry("inflight-one", slots, "ko", slow) }
        val two = async { KoLyricsTranslation.geminiEntry("inflight-two", slots, "ko", slow) }

        assertNotNull(one.await())
        assertNotNull(two.await())
        assertEquals(true, overlapped)
    }
}
