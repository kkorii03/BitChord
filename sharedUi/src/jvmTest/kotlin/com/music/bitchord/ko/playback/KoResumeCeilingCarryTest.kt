package com.music.bitchord.ko.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KoResumeCeiling] across pauses: steps on later resumes must not add up (G4, phone log
 * 11-12..11-16), and a reading that is still ahead stays held on the next resume (RG4-1, 11-09).
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
    fun `a reading still ahead after the next pause stays held on the next resume`() {
        // RG4-1, the 11-09 LDAC case paused twice: the reading jumped 3.47 s on the first
        // resume and stays that far ahead (until a seek). The second resume continues from
        // there in real time with no new step; letting it through put the lyrics ~3.4 s early.
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        val stoppedAt = sink.playLinear(fromMs = 118_600.0, ms = 60_000)
        sink.pauseFor(72_000)
        val rawAtPause = sink.resume(fromMs = stoppedAt, ms = 11_000, jumpMs = 3_470.0)
        assertTrue("first hold ${sink.clock.heldBackUs}", sink.clock.heldBackUs > 3_300_000)

        sink.pauseFor(20_000)
        assertEquals(0L, sink.clock.heldBackUs) // nothing is reported while paused
        sink.clock.play(sink.nowNanos)
        for (i in 1..1100) {
            val rawMs = rawAtPause + (i * 10.0 - 30).coerceAtLeast(0.0)
            val behind = (rawMs * 1000).toLong() - sink.poll(rawMs)
            // One slack (0.1 s) is given up per resume; the G4 code gave up all of it (0 behind).
            assertTrue("at ${i * 10}ms: $behind us behind the reading", behind >= 3_200_000)
        }
    }

    @Test
    fun `no hold is ever larger than one AudioTrack buffer`() {
        // The reading is capped at the frames written, at most one buffer ahead of the sound.
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        var raw = sink.playLinear(fromMs = 0.0, ms = 60_000)
        repeat(6) {
            sink.pauseFor(2_000)
            sink.clock.play(sink.nowNanos)
            repeat(300) { i ->
                raw += if (i == 3) 3_900.0 else 10.0
                sink.poll(raw)
                assertTrue("hold ${sink.clock.heldBackUs}", sink.clock.heldBackUs <= KoResumeCeiling.MAX_HOLD_US)
            }
        }
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
