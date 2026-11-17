package com.music.bitchord.ko.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A [KoResumeCeiling] hold belongs to one resume (G4, phone log 11-12..11-16): it must not carry
 * into the next pause/resume, and steps on later resumes must not add up.
 */
class KoResumeCeilingCarryTest {
    /** A sink polled every 10 ms, the way the audio renderer polls it. */
    private class Sink {
        val lines = mutableListOf<String>()
        val clock = KoResumeCeiling { lines += it }
        var nowNanos = 5_000_000_000_000L
        var lastOutUs = 0L

        fun poll(rawMs: Double): Long {
            nowNanos += POLL_NANOS
            lastOutUs = clock.position((rawMs * 1000).toLong(), nowNanos, 1f)
            return lastOutUs
        }

        fun playLinear(fromMs: Double, ms: Int): Double {
            var raw = fromMs
            repeat(ms / 10) {
                raw += 10
                poll(raw)
            }
            return raw
        }

        fun pauseFor(ms: Long) {
            clock.pause(nowNanos)
            nowNanos += ms * 1_000_000L
        }

        /**
         * Resumes and plays [ms]: the reading stands at [fromMs] for the first 30 ms, then moves
         * in real time, [jumpMs] further on than the song could have got. Returns the last reading.
         */
        fun resume(fromMs: Double, ms: Int, jumpMs: Double): Double {
            clock.play(nowNanos)
            var raw = fromMs
            for (i in 1..ms / 10) {
                val moving = (i * 10.0 - 30).coerceAtLeast(0.0)
                raw = fromMs + moving + if (moving > 0) jumpMs else 0.0
                poll(raw)
            }
            return raw
        }

        fun reAnchoredSteps(): List<Long> = lines
            .filter { it.contains("re-anchored") }
            .map { it.substringAfter("step=-").substringBefore("ms").toLong() }
    }

    @Test
    fun `a hold does not carry into the next pause and resume`() {
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        val stoppedAt = sink.playLinear(fromMs = 0.0, ms = 60_000)
        sink.pauseFor(5_000)

        // First resume: the reading jumps 3.9 s ahead and is held, for the whole run.
        val rawAtPause = sink.resume(fromMs = stoppedAt, ms = 2_000, jumpMs = 3_900.0)
        val firstHold = sink.clock.heldBackUs
        assertTrue("first hold $firstHold", firstHold in 3_500_000L..4_000_000L)

        // The hold ends with its run.
        sink.pauseFor(3_000)
        assertEquals(0L, sink.clock.heldBackUs)

        // Second resume, the reading carries on in real time with no new step: nothing to hold.
        sink.clock.play(sink.nowNanos)
        for (i in 1..300) {
            val rawMs = rawAtPause + (i * 10.0 - 30).coerceAtLeast(0.0)
            val out = sink.poll(rawMs)
            val behind = (rawMs * 1000).toLong() - out
            // The old code started this run from the held position: ~3.8 s behind the reading.
            assertTrue("at ${i * 10}ms: $behind us behind the reading", behind < KoResumeCeiling.STEP_REPORT_US)
        }
        assertEquals(0L, sink.clock.heldBackUs)
        assertEquals(sink.lines.toString(), 1, sink.reAnchoredSteps().size)
    }

    @Test
    fun `steps on back-to-back resumes do not add up`() {
        // 11-12 22:31:58-22:32:27: five pauses in 30 s logged step=-3885, -7656, -11442, -15196.
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        var raw = sink.playLinear(fromMs = 0.0, ms = 60_000)
        repeat(4) { n ->
            sink.pauseFor(2_000)
            raw = sink.resume(fromMs = raw, ms = 3_000, jumpMs = 3_900.0)
            val hold = sink.clock.heldBackUs
            assertTrue("resume ${n + 1}: hold $hold", hold in 3_500_000L..4_000_000L)
        }
        val steps = sink.reAnchoredSteps()
        assertEquals(sink.lines.toString(), 4, steps.size)
        assertTrue(steps.toString(), steps.all { it < 4_000 })
    }

    @Test
    fun `within one run a reading that jumped ahead is still held until a seek`() {
        // What the class was built for (11-09 20:36:34, LDAC): +3.47 s 1.5 s after play.
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        val stoppedAt = sink.playLinear(fromMs = 118_600.0, ms = 60_000)
        sink.pauseFor(72_000)
        val last = sink.resume(fromMs = stoppedAt, ms = 11_000, jumpMs = 3_470.0)
        val behind = (last * 1000).toLong() - sink.lastOutUs
        assertTrue("held $behind", behind > 3_300_000)
        assertTrue(sink.lines.toString(), sink.lines.any { it.contains("after a 72s pause (10s in") })
        sink.clock.clear("flush")
        assertTrue(sink.lines.toString(), sink.lines.any { it.contains("released a 33") && it.contains("(flush)") })
        assertEquals(0L, sink.clock.heldBackUs)
    }

    private companion object {
        const val POLL_NANOS = 10_000_000L
    }
}
