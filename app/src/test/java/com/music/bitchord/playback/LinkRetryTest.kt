package com.music.bitchord.playback

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-06 smoke: five songs never started because their one `links:` look-up
 * failed ("unexpected end of stream", 3.5-4.3 s) while the same route answered
 * again seconds later, and the request was spent on a toast.
 */
class LinkRetryTest {

    private fun dropped() = Result.failure<String>(IOException("unexpected end of stream"))

    @Test
    fun aDroppedConnectionIsAskedAgainUntilTheRouteIsBack() = runTest {
        var asked = 0
        val answer = LinkRetry.resolve {
            asked++
            delay(4_260L) // what one failed look-up took in the log
            if (asked <= 3) dropped() else Result.success("song")
        }
        assertEquals("song", answer.getOrNull())
        assertEquals(4, asked)
        assertTrue("the whole wait stays inside the deadline", currentTime <= LinkRetry.DEADLINE_MS)
    }

    @Test
    fun theAskingStopsAfterItsLastTry() = runTest {
        var asked = 0
        val answer = LinkRetry.resolve {
            asked++
            dropped()
        }
        assertTrue(answer.exceptionOrNull() is IOException)
        assertEquals(4, asked)
    }

    @Test
    fun anAnswerThatIsNotAConnectionFailureIsNotAskedAgain() = runTest {
        var asked = 0
        val answer = LinkRetry.resolve {
            asked++
            Result.failure<String>(IllegalStateException("no watch entry"))
        }
        assertTrue(answer.exceptionOrNull() is IllegalStateException)
        assertEquals(1, asked)
    }

    @Test
    fun aHungRequestEndsAtTheDeadline() = runTest {
        val answer = LinkRetry.resolve<String> { awaitCancellation() }
        assertTrue(answer.isFailure)
        assertEquals(LinkRetry.DEADLINE_MS, currentTime)
    }
}
