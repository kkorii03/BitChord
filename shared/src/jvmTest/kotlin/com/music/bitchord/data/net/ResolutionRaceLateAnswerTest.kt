package com.music.bitchord.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After the early race gives up at its own window, a route can still answer
 * before the shared rescue deadline. That answer must be used rather than
 * waiting for extraction, which is the slower fallback.
 */
class ResolutionRaceLateAnswerTest {

    @Test
    fun `a late route answer inside the deadline beats the extraction fallback`() = runBlocking {
        val late = CompletableDeferred<Pair<String, String?>>()
        val completer = launch {
            delay(50)
            late.complete("proxy" to "proxy-result")
        }
        val result = raceLateAnswerOrFallback(
            late = late,
            deadlineRemainingMs = { 5_000L },
            fallback = {
                delay(500)
                "extraction-result"
            },
        )
        completer.join()
        assertEquals("proxy-result", result?.second)
        assertEquals("proxy", result?.first)
    }

    @Test
    fun `a route answering after the deadline is stopped and extraction answers`() = runBlocking {
        val late = CompletableDeferred<Pair<String, String?>>()
        val completer = launch {
            delay(200)
            late.complete("proxy" to "proxy-result")
        }
        val result = raceLateAnswerOrFallback(
            late = late,
            deadlineRemainingMs = { 100L },
            fallback = {
                delay(300)
                "extraction-result"
            },
        )
        completer.join()
        assertEquals("extraction-result", result?.second)
        assertTrue(late.isCancelled, "the route past the shared deadline must be stopped")
    }
}
