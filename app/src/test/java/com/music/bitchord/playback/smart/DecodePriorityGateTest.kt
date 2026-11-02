package com.music.bitchord.playback.smart

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-02 smoke: the playing song's sync correction landed 10-18 minutes after it
 * started, because the next-track bundle's decodes were queued ahead of it at
 * the one-at-a-time gate. A waiting decode for the playing track goes first.
 */
class DecodePriorityGateTest {
    @Test
    fun thePlayingTracksDecodeGoesBeforeOneQueuedEarlierForTheNextTrack() {
        val gate = DecodePriorityGate()
        val order = Collections.synchronizedList(mutableListOf<String>())
        gate.acquire(background = false) // a decode already running

        val ahead = Thread {
            gate.acquire(background = true)
            order += "ahead"
            gate.release()
        }
        ahead.start()
        waitUntilWaiting(ahead)
        val playing = Thread {
            gate.acquire(background = false)
            order += "playing"
            gate.release()
        }
        playing.start()
        waitUntilWaiting(playing)

        gate.release()
        ahead.join(5_000)
        playing.join(5_000)
        assertEquals(listOf("playing", "ahead"), order)
    }

    @Test
    fun workDoneAheadStillRunsWhenNothingElseWaits() {
        val gate = DecodePriorityGate()
        val done = CountDownLatch(1)
        Thread {
            gate.acquire(background = true)
            done.countDown()
            gate.release()
        }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun waitUntilWaiting(t: Thread) {
        val until = System.currentTimeMillis() + 5_000
        while (t.state != Thread.State.WAITING && System.currentTimeMillis() < until) Thread.sleep(5)
    }
}
