package com.music.bitchord.ko.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playhead the lyrics read after a resume (see [KoResumeCeiling]). Named for the lyric clock
 * because that is what it feeds, and so the clock step of ko-wip-check runs it.
 */
class KoResumeLyricClockTest {
    /** A sink polled every 10 ms, the way the audio renderer polls it. */
    private class Sink {
        val lines = mutableListOf<String>()
        val clock = KoResumeCeiling { lines += it }
        var nowNanos = 5_000_000_000_000L

        fun poll(rawMs: Double, speed: Float = 1f, allowed: Boolean = true): Long {
            nowNanos += POLL_NANOS
            return clock.position((rawMs * 1000).toLong(), nowNanos, speed, allowed)
        }

        /** Plays [ms] of song at [rate] from [fromMs], one poll per 10 ms; returns where it got to. */
        fun playLinear(fromMs: Double, ms: Int, rate: Double = 1.0): Double {
            var raw = fromMs
            repeat(ms / 10) {
                raw += 10 * rate
                poll(raw, rate.toFloat())
            }
            return raw
        }

        fun pauseFor(ms: Long) {
            clock.pause(nowNanos)
            nowNanos += ms * 1_000_000L
        }
    }

    @Test
    fun `a reading that jumps ahead after a resume is held to where the song stopped plus the time since`() {
        // 11-09 20:36:34 on LDAC: 72 s paused, and 1.5 s after play the reading was 3.47 s further
        // on than the song could have got. The old sink handed that reading straight on.
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        val stoppedAt = sink.playLinear(fromMs = 118_600.0, ms = 60_000)
        sink.pauseFor(72_000)
        sink.clock.play(sink.nowNanos)

        var rawAt5s = 0L
        var outAt5s = 0L
        for (i in 1..1100) {
            val since = i * 10.0
            val moving = (since - 30).coerceAtLeast(0.0)
            val rawMs = stoppedAt + moving + if (since >= 1000) 3470.0 else 0.0
            val out = sink.poll(rawMs)
            val possibleUs = ((stoppedAt + moving) * 1000).toLong()
            assertTrue("at ${since}ms: $out over $possibleUs", out <= possibleUs + KoResumeCeiling.SLACK_US + 10_000)
            if (i == 500) {
                rawAt5s = (rawMs * 1000).toLong()
                outAt5s = out
            }
        }
        assertTrue("held back ${rawAt5s - outAt5s}us", rawAt5s - outAt5s > 3_300_000)
        assertTrue(sink.lines.toString(), sink.lines.any { it.contains("re-anchored after a 72s pause, step=-33") })
        assertTrue(sink.lines.toString(), sink.lines.any { it.contains("after a 72s pause (10s in") && it.contains("peak lead=+34") })
    }

    @Test
    fun `the hypothesised resume without the Bluetooth delay is held too`() {
        // A play head estimate that leaves the route's delay out reads ~0.9 s ahead from the very
        // first poll after play, and moves on from there.
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        val stoppedAt = sink.playLinear(fromMs = 0.0, ms = 40_000)
        sink.pauseFor(75_000)
        sink.clock.play(sink.nowNanos)
        var last = 0L
        for (i in 1..600) {
            val since = i * 10.0
            last = sink.poll(stoppedAt + 900 + (since - 200).coerceAtLeast(0.0))
        }
        val possibleUs = ((stoppedAt + 6000 - 200) * 1000).toLong()
        assertTrue("$last vs $possibleUs", last <= possibleUs + KoResumeCeiling.SLACK_US + 10_000)
        assertTrue(sink.clock.heldBackUs > 700_000)
        assertTrue(sink.lines.toString(), sink.lines.any { it.contains("step=-") })
    }

    @Test
    fun `an ordinary resume, late to start as it always is, is not touched`() {
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        val stoppedAt = sink.playLinear(fromMs = 10_000.0, ms = 30_000)
        sink.pauseFor(72_000)
        sink.clock.play(sink.nowNanos)
        for (i in 1..1200) {
            val since = i * 10.0
            val rawMs = stoppedAt + (since - 400).coerceAtLeast(0.0)
            assertEquals((rawMs * 1000).toLong(), sink.poll(rawMs))
        }
        assertEquals(0L, sink.clock.heldBackUs)
        assertFalse(sink.lines.toString(), sink.lines.any { it.contains("re-anchored") })
        // The summary still says how the resume went, for the next phone log.
        assertTrue(sink.lines.toString(), sink.lines.any { it.contains("after a 72s pause (10s in") && it.contains("hold=0ms") })
    }

    @Test
    fun `a pause in the middle of a sag starts from the top of the readings, not the bottom`() {
        // The 11-09 sags: down 930 ms with nothing announced, climbing back at ~3 %. Paused 3 s into
        // one, the song is where the top line says; started from the last reading instead, a
        // reading back on the top line after the resume would be held 0.84 s behind it.
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        var raw = sink.playLinear(fromMs = 0.0, ms = 30_000)
        raw -= 930
        sink.poll(raw)
        repeat(300) {
            raw += 10 * 1.03
            sink.poll(raw)
        }
        val top = 33_010.0
        sink.pauseFor(60_000)
        sink.clock.play(sink.nowNanos)
        for (i in 1..800) {
            val since = i * 10.0
            val rawMs = if (since < 200) raw else top + (since - 200)
            assertEquals("at ${since}ms", (rawMs * 1000).toLong(), sink.poll(rawMs))
        }
        assertEquals(0L, sink.clock.heldBackUs)
    }

    @Test
    fun `a seek between the pause and the play is not a resume`() {
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        sink.playLinear(fromMs = 0.0, ms = 20_000)
        sink.pauseFor(5_000)
        sink.clock.clear("flush")
        sink.clock.play(sink.nowNanos)
        assertEquals(150_000_000L, sink.poll(150_000.0))
        assertEquals(150_010_000L, sink.poll(150_010.0))
    }

    @Test
    fun `the line runs at the playing speed`() {
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        val stoppedAt = sink.playLinear(fromMs = 0.0, ms = 20_000, rate = 1.25)
        sink.pauseFor(10_000)
        sink.clock.play(sink.nowNanos)
        for (i in 1..1000) {
            val rawMs = stoppedAt + 1.25 * (i * 10.0 - 100).coerceAtLeast(0.0)
            assertEquals((rawMs * 1000).toLong(), sink.poll(rawMs, speed = 1.25f))
        }
    }

    @Test
    fun `a reading that comes back down is followed, and a skip or a splice passes untouched`() {
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        val stoppedAt = sink.playLinear(fromMs = 0.0, ms = 20_000)
        sink.pauseFor(30_000)
        sink.clock.play(sink.nowNanos)
        var since = 0.0
        repeat(300) {
            since += 10
            sink.poll(stoppedAt + 3000 + since)
        }
        assertTrue(sink.clock.heldBackUs > 2_800_000)
        // Media3 finds its feet again: the true reading is below the line and is reported as is.
        since += 10
        val back = stoppedAt + since - 50
        assertEquals((back * 1000).toLong(), sink.poll(back))
        assertEquals(0L, sink.clock.heldBackUs)
        // An Automix splice or silence skipping: nothing is held or learned.
        since += 10
        val skipped = stoppedAt + since + 5000
        assertEquals((skipped * 1000).toLong(), sink.poll(skipped, allowed = false))
    }

    @Test
    fun `a short stall that resumes cleanly logs nothing`() {
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        val stoppedAt = sink.playLinear(fromMs = 0.0, ms = 20_000)
        sink.pauseFor(800)
        sink.clock.play(sink.nowNanos)
        for (i in 1..1200) sink.poll(stoppedAt + (i * 10.0 - 100).coerceAtLeast(0.0))
        assertTrue(sink.lines.toString(), sink.lines.isEmpty())
    }

    private companion object {
        const val POLL_NANOS = 10_000_000L
    }
}
