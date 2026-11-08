package com.music.bitchord.playback.smart

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * 11-02: the next-prep bundle was cancelled at 09:00:02, yet its decode
     * sat at the gate another 25 s because only the block after the acquire
     * checked the abort. A cancelled decode interrupts its blocking wait.
     */
    @Test
    fun aCancelledDecodeStopsWaitingAtTheGate() = runBlocking {
        val gate = DecodePriorityGate()
        gate.acquire(background = false) // the playing track holds the one slot
        val waiting = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            try {
                AudioDecoder.interruptibleDecode {
                    waiting.countDown()
                    try {
                        gate.acquire(background = true)
                        gate.release()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } catch (_: Throwable) {
                // The interrupted wait is the point; only completion matters.
            }
        }
        try {
            assertTrue("decode never reached the gate", waiting.await(5, TimeUnit.SECONDS))
            val stopped = withTimeoutOrNull(2_000L) {
                job.cancelAndJoin()
                true
            }
            assertTrue("the cancelled decode stayed queued at the gate", stopped == true)
        } finally {
            gate.release()
            job.cancel()
        }
    }

    /**
     * 11-08 round-score 9: the late handover's two alignment decodes waited up
     * to 160 s behind the video's own lyric and Automix decodes, every pass ran
     * out of time, and 0 of 9 handovers completed. A decode a swap is waiting
     * on goes before playing-track work that queued earlier.
     */
    @Test
    fun anUrgentDecodeGoesBeforeAPlayingTrackDecodeQueuedEarlier() {
        val gate = DecodePriorityGate()
        val order = Collections.synchronizedList(mutableListOf<String>())
        gate.acquire(background = false) // the video's lyric decode is running

        val lyric = Thread {
            gate.acquire(background = false)
            order += "lyric"
            gate.release()
        }
        lyric.start()
        waitUntilWaiting(lyric)
        val ahead = Thread {
            gate.acquire(background = true)
            order += "ahead"
            gate.release()
        }
        ahead.start()
        waitUntilWaiting(ahead)
        val align = Thread {
            gate.acquire(background = false, urgent = true)
            order += "align"
            gate.release()
        }
        align.start()
        waitUntilWaiting(align)

        gate.release()
        listOf(lyric, ahead, align).forEach { it.join(5_000) }
        assertEquals(listOf("align", "lyric", "ahead"), order)
    }

    /** An urgent waiter that is cancelled must not keep the slot from the rest. */
    @Test
    fun aCancelledUrgentWaiterLetsTheQueueThrough() {
        val gate = DecodePriorityGate()
        gate.acquire(background = false)
        val urgent = Thread {
            try {
                gate.acquire(background = false, urgent = true)
                gate.release()
            } catch (_: InterruptedException) {
                // cancelled while queued
            }
        }
        urgent.start()
        waitUntilWaiting(urgent)
        val lyric = CountDownLatch(1)
        val other = Thread {
            gate.acquire(background = false)
            lyric.countDown()
            gate.release()
        }
        other.start()
        waitUntilWaiting(other)
        urgent.interrupt()
        urgent.join(5_000)
        gate.release()
        assertTrue("a cancelled urgent waiter blocked the queue", lyric.await(5, TimeUnit.SECONDS))
    }

    /**
     * The alignment decode's abort used to ask only about the screen, so a
     * pass whose deadline or row had gone decoded on with the screen on, or
     * with the screen rule off (the late handover): 100-330 s into the next
     * song on the fleet. A cancelled pass aborts.
     */
    @Test
    fun aCancelledAlignmentPassAbortsItsDecode() {
        val pass = Job()
        assertEquals(false, VersionAudioAligner.alignmentAbandoned(pass, ignoreScreen = true, screenOn = false))
        assertEquals(false, VersionAudioAligner.alignmentAbandoned(pass, ignoreScreen = false, screenOn = true))
        assertEquals(true, VersionAudioAligner.alignmentAbandoned(pass, ignoreScreen = false, screenOn = false))
        pass.cancel()
        assertEquals(true, VersionAudioAligner.alignmentAbandoned(pass, ignoreScreen = true, screenOn = true))
    }

    private fun waitUntilWaiting(t: Thread) {
        val until = System.currentTimeMillis() + 5_000
        while (t.state != Thread.State.WAITING && System.currentTimeMillis() < until) Thread.sleep(5)
    }
}
