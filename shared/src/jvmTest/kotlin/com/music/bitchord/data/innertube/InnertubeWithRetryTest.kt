package com.music.bitchord.data.innertube

import java.io.IOException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InnertubeWithRetryTest {

    @Test
    fun aDroppedConnectionWaitsLongerEachTimeUntilTheRouteIsBack() = runTest {
        var asked = 0
        val answer = Innertube.withRetry(attempts = 4, firstWaitMs = 3_000L) {
            if (++asked <= 3) throw IOException("unexpected end of stream")
            "song"
        }
        assertEquals("song", answer)
        assertEquals(21_000L, currentTime)
    }

    @Test
    fun theAskingStopsAfterItsLastTry() = runTest {
        var asked = 0
        assertFailsWith<IOException> {
            Innertube.withRetry<String>(attempts = 4, firstWaitMs = 3_000L) {
                asked++
                throw IOException("unexpected end of stream")
            }
        }
        assertEquals(4, asked)
    }

    @Test
    fun anAnswerThatIsNotAConnectionFailureIsNotAskedAgain() = runTest {
        var asked = 0
        assertFailsWith<IllegalStateException> {
            Innertube.withRetry<String>(attempts = 4, firstWaitMs = 3_000L) {
                asked++
                error("no watch entry")
            }
        }
        assertEquals(1, asked)
    }
}
