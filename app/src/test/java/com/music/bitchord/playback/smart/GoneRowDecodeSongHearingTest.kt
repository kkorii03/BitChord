package com.music.bitchord.playback.smart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * w1120-battery: 11-18 15:47:01, 15:48:07 and 11-19 18:00:02 the phone decoded three
 * whole-song WAVs (16.1 MB, 66 s of decoder) for rows the user had already left; each
 * was then dropped by [SongHearing.askWhileWanted] before a byte went out. And 11-19
 * 02:33 _QtN6qocpKU: webm got http=503, so a 2.8 MB WAV was decoded and sent, 503 again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoneRowDecodeSongHearingTest {

    @Test
    fun aDecodeForARowThatLeavesStopsWhenTheRowsChange() = runTest {
        val rows = MutableStateFlow<Any?>("ZhYhERzJlXk" to "P9sDPvaRwUo")
        var gone = false
        var finished = false
        var cancelled = false
        launch {
            delay(10_000L)
            gone = true
            rows.value = "P9sDPvaRwUo" to "Gj1bDGnbopY"
        }
        val outcome = SongHearing.whileRowWanted({ gone }, rows) {
            try {
                delay(66_000L) // the decoder's time for a whole song
                finished = true
                "wav"
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        assertTrue(outcome.left)
        assertNull(outcome.value)
        assertFalse(finished)
        assertTrue(cancelled)
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun aRowGoneBeforeTheDecodeDecodesNothing() = runTest {
        var started = 0
        val outcome = SongHearing.whileRowWanted({ true }, MutableStateFlow<Any?>(null)) {
            started++
            "wav"
        }
        assertTrue(outcome.left)
        assertEquals(0, started)
    }

    @Test
    fun aRowThatStaysGetsItsDecode() = runTest {
        val rows = MutableStateFlow<Any?>("ZhYhERzJlXk" to null)
        launch {
            delay(5_000L)
            rows.value = "ZhYhERzJlXk" to "P9sDPvaRwUo" // a next row arrives; this one still plays
        }
        val outcome = SongHearing.whileRowWanted({ false }, rows) {
            delay(30_000L)
            "wav"
        }
        assertFalse(outcome.left)
        assertEquals("wav", outcome.value)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun aServerErrorDoesNotDecodeTheSongAgain() {
        assertFalse(SongHearing.decodesAfter(503))
        assertFalse(SongHearing.decodesAfter(500))
        assertFalse(SongHearing.decodesAfter(502))
    }

    @Test
    fun onlyBytesTheServerCouldNotReadGoRoundTheDecoder() {
        assertTrue(SongHearing.decodesAfter(400))
        assertTrue(SongHearing.decodesAfter(415))
        assertFalse(SongHearing.decodesAfter(200))
        assertFalse(SongHearing.decodesAfter(429))
        assertFalse(SongHearing.decodesAfter(401))
        assertFalse(SongHearing.decodesAfter(403))
        assertFalse(SongHearing.decodesAfter(null))
    }
}
