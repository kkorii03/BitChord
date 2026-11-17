package com.music.bitchord.data.lyrics

import com.music.bitchord.data.lyrics.LyricsTranslation.BatchAnswer
import com.music.bitchord.data.lyrics.LyricsTranslation.TextSlot
import com.music.bitchord.ko.KoLyricsTranslation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    /*
     * The cache read before the gate always hops to Dispatchers.IO
     * (LyricsTranslation.readStored), so two callers started together reach
     * the gate in either order. These tests fix the order with a signal
     * instead of assuming the first started is the first to ask (CI 11-17
     * kkorii123 44104820248: the second caller asked first).
     */

    @Test
    fun `a waiter asks itself when the first ask came back with nothing`() = runBlocking<Unit> {
        var asks = 0
        val firstAsking = CompletableDeferred<Unit>()
        val flaky: suspend (List<TextSlot>, String, () -> Unit) -> BatchAnswer? = { s, _, _ ->
            asks++
            if (asks == 1) {
                firstAsking.complete(Unit)
                delay(100)
                null
            } else {
                answer(s)
            }
        }
        val first = async { KoLyricsTranslation.geminiEntry("inflight-flaky", slots, "ko", flaky) }
        // The second caller starts only once the first holds the sheet's gate.
        firstAsking.await()
        val second = async { KoLyricsTranslation.geminiEntry("inflight-flaky", slots, "ko", flaky) }

        assertNull(first.await())
        assertNotNull(second.await())
        assertEquals(2, asks)
    }

    @Test
    fun `different sheets are not held behind each other`() = runBlocking<Unit> {
        var running = 0
        var overlapped = false
        val bothAsking = CompletableDeferred<Unit>()
        val slow: suspend (List<TextSlot>, String, () -> Unit) -> BatchAnswer? = { s, _, _ ->
            running++
            if (running > 1) bothAsking.complete(Unit)
            // Held behind each other, the other request never starts while
            // this one waits; 5 s is the most this test can then take.
            if (withTimeoutOrNull(5_000) { bothAsking.await() } != null) overlapped = true
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
