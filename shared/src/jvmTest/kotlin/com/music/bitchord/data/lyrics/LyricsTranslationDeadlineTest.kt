package com.music.bitchord.data.lyrics

import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A translation request must end within its budget, not after every provider
 * has had its own timeout: on a slow proxy the button otherwise stays in
 * Loading for minutes. A kept answer is still offered by the caller after a
 * timeout, so null here means "use the cache or report unavailable", not
 * "discard what we have".
 */
class LyricsTranslationDeadlineTest {

    @Test
    fun anAnswerInTimeIsReturnedUnchanged() = runBlocking {
        val answer = LyricsTranslation.withinTranslationDeadline(5_000) {
            delay(10)
            "translated"
        }
        assertEquals("translated", answer)
    }

    @Test
    fun aRequestOverTheBudgetIsCutOffInsteadOfWaitingForEveryProvider() = runBlocking {
        var answer: String? = null
        val elapsed = measureTimeMillis {
            answer = LyricsTranslation.withinTranslationDeadline(50) {
                delay(10_000)
                "too late"
            }
        }
        assertNull(answer)
        assertTrue(elapsed < 5_000, "the deadline must not wait for the full request")
    }
}
