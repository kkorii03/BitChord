package com.music.bitchord.data.innertube

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InnertubeWithRetryTest {

    @Test
    fun aDroppedConnectionWaitsLongerEachTimeUntilTheRouteIsBack() = runBlocking {
        var asked = 0
        val started = System.nanoTime()
        val answer = Innertube.withRetry(attempts = 4, firstWaitMs = 20L) {
            if (++asked <= 3) throw IOException("unexpected end of stream")
            "song"
        }
        assertEquals("song", answer)
        assertEquals(4, asked)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 140L, "waits were 20, 40, 80 ms")
    }

    @Test
    fun theAskingStopsAfterItsLastTry() = runBlocking {
        var asked = 0
        assertFailsWith<IOException> {
            Innertube.withRetry<String>(attempts = 4, firstWaitMs = 1L) {
                asked++
                throw IOException("unexpected end of stream")
            }
        }
        assertEquals(4, asked)
    }

    @Test
    fun anAnswerThatIsNotAConnectionFailureIsNotAskedAgain() = runBlocking {
        var asked = 0
        assertFailsWith<IllegalStateException> {
            Innertube.withRetry<String>(attempts = 4, firstWaitMs = 1L) {
                asked++
                error("no watch entry")
            }
        }
        assertEquals(1, asked)
    }
}
