package com.music.bitchord.playback.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The next-prep bundle's paid requests run on the app-lifetime AI scope so a
 * screen closing does not lose them. A bundle that started one must still stop
 * it when its row is replaced (11-02: a Deepgram request for a prepared song
 * ran ~70 s after the bundle was cancelled).
 */
class LyricsTimingFlightCancellationTest {

    @Test
    fun aFlightStartedByTheCallerStopsWhenTheCallerIsCancelled() = runBlocking {
        val caller = launch { delay(60_000) }
        val flight = async(Dispatchers.Default) { delay(60_000) }
        with(LyricsTiming) { flight.cancelledWithCaller(caller, cancelWithCaller = true) }
        caller.cancelAndJoin()
        val stopped = withTimeoutOrNull(2_000L) {
            flight.join()
            true
        }
        assertTrue("the flight outlived the caller that started it", stopped == true)
        assertTrue(flight.isCancelled)
    }

    @Test
    fun aFlightAnotherCallerStartedIsNotCancelled() = runBlocking {
        val caller = launch { delay(60_000) }
        val flight = async(Dispatchers.Default) { delay(250) }
        // A joiner passes false (or does not register at all): cancelling it
        // must not cancel work it did not start.
        with(LyricsTiming) { flight.cancelledWithCaller(caller, cancelWithCaller = false) }
        caller.cancelAndJoin()
        assertFalse("a caller that did not start the flight must not cancel it", flight.isCancelled)
        flight.join()
    }
}
