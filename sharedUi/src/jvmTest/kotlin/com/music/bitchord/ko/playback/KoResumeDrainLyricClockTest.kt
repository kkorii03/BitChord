package com.music.bitchord.ko.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [KoResumeCeiling] when the sink runs dry under a hold (midsilence, phone log 11-19 15:47:27,
 * Centuries 24/96 FLAC, JBL Tour Pro 3). Media3 counts the audio in hand from the position this
 * clock reports, so a hold left standing over a drained sink reads as seconds still to play:
 * `pos=107553 buffered=110976 loading=false` with a 3441 ms hold, and ~15 s of silence.
 * Named for the lyric clock, which reads this position, so the clock step of ko-wip-check runs it.
 */
class KoResumeDrainLyricClockTest {
    @get:Rule
    val koTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    /** A sink polled every 10 ms, the way the audio renderer polls it. */
    private class Sink {
        val lines = mutableListOf<String>()
        val clock = KoResumeCeiling { lines += it }
        var nowNanos = 5_000_000_000_000L

        fun poll(rawMs: Double, drained: Boolean = false): Long {
            nowNanos += POLL_NANOS
            return clock.position((rawMs * 1000).toLong(), nowNanos, 1f, drained = drained)
        }

        fun pauseFor(ms: Long) {
            clock.pause(nowNanos)
            nowNanos += ms * 1_000_000L
        }

        /** 92.6 s played, a 290 s pause, then a resume whose reading jumps 3.884 s ahead. */
        fun resumeAsOnThePhone(): Double {
            clock.play(nowNanos)
            var raw = 0.0
            repeat(9_260) { raw += 10; poll(raw) }
            pauseFor(290_000)
            clock.play(nowNanos)
            val from = raw
            for (i in 1..1_300) {
                val moving = (i * 10.0 - 30).coerceAtLeast(0.0)
                raw = from + moving + if (moving > 0) 3_884.0 else 0.0
                poll(raw)
            }
            return raw
        }
    }

    @Test
    fun `a hold over a drained sink is released so Media3 sees the buffer is empty`() {
        val sink = Sink()
        val writtenEnd = sink.resumeAsOnThePhone()
        assertTrue("hold before the drain ${sink.clock.heldBackUs}", sink.clock.heldBackUs > 3_300_000)

        // Everything written has played: the reading stands at the end of it.
        val out = sink.poll(writtenEnd, drained = true)
        val inHandUs = (writtenEnd * 1000).toLong() - out
        // Media3 drops its back buffer to load again only below 0.5 s in hand.
        assertTrue("Media3 would count ${inHandUs}us still to play", inHandUs < 500_000)
        assertEquals(0L, sink.clock.heldBackUs)
        assertTrue(sink.lines.toString(), sink.lines.any { it.contains("released a 3") && it.contains("(sink drained)") })
    }

    @Test
    fun `the rebuffer after a drain does not put the hold back`() {
        // 15:47:27.120: the stop and restart of the rebuffer re-armed the hold ("after a 0s
        // pause, step=-3441ms") over a reading that could not move, and it stayed until 15:47:37.
        val sink = Sink()
        val writtenEnd = sink.resumeAsOnThePhone()
        sink.poll(writtenEnd, drained = true) // the renderer's last reading as it stops
        sink.pauseFor(0)
        sink.clock.play(sink.nowNanos)
        val reAnchors = sink.lines.count { it.contains("re-anchored") }
        var raw = writtenEnd
        for (i in 1..500) {
            if (i > 20) raw += 10 // new audio loaded and playing
            val out = sink.poll(raw)
            assertEquals("at ${i * 10}ms", (raw * 1000).toLong(), out)
        }
        assertEquals(sink.lines.toString(), reAnchors, sink.lines.count { it.contains("re-anchored") })
    }

    @Test
    fun `a hold the sink still has the audio for is kept`() {
        // The 11-09 case the class was built for: the sink is still playing what it holds.
        val sink = Sink()
        val raw = sink.resumeAsOnThePhone()
        val out = sink.poll(raw + 10, drained = false)
        assertTrue("held ${(raw + 10) * 1000 - out}", ((raw + 10) * 1000).toLong() - out > 3_300_000)
        assertTrue(sink.lines.none { it.contains("sink drained") })
    }

    @Test
    fun `a drained sink with no hold passes through without a line`() {
        val sink = Sink()
        sink.clock.play(sink.nowNanos)
        var raw = 0.0
        repeat(1_000) { raw += 10; sink.poll(raw) }
        assertEquals((raw * 1000).toLong(), sink.poll(raw, drained = true))
        assertTrue(sink.lines.toString(), sink.lines.none { it.contains("released") })
    }

    private companion object {
        const val POLL_NANOS = 10_000_000L
    }
}
