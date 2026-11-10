package dev.dunkblock.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisTest {
    private val period = 8_333_333L

    /** Builds a capture where the producer delivered every [keepEvery]-th display frame (2 => 60 fps from a 120 Hz display). */
    private fun build(displayFrames: Int, keepEvery: Int, latencyMs: Double, tsOffsetNs: Long): Pair<FrameLog, DisplayLog> {
        val disp = DisplayLog(displayFrames + 10)
        val t0 = 5_000_000_000L
        for (i in 0 until displayFrames) disp.recordFrame(t0 + i * period, t0 + i * period + period)
        val log = FrameLog(displayFrames + 10)
        var seq = 0
        for (i in 0 until displayFrames step keepEvery) {
            val s = log.next()
            val present = t0 + i * period + period
            log.arrivalNs[s] = present + (latencyMs * 1e6).toLong()
            log.imageTsNs[s] = present + 2_000_000L + tsOffsetNs
            log.burst[s] = 1
            log.counter[s] = i and 0xFFFF
            log.patLuma[s] = -1
            log.meanLuma100[s] = 5000
            log.commit(); seq++
        }
        return log to disp
    }

    @Test fun detects60FpsCaptureOf120HzDisplay() {
        val (log, disp) = build(displayFrames = 1200, keepEvery = 2, latencyMs = 12.0, tsOffsetNs = 0)
        val r = CaptureAnalysis.analyze(log, warmupSec = 0.0, display = disp)
        assertEquals(600, r.frames)
        assertTrue(r.avgFps in 58.0..62.0, "avg ${r.avgFps}")
        assertTrue(r.rateClass.contains("60"), r.rateClass)
        assertEquals(0, r.dupCounters)
        assertEquals(r.displayedSpan - r.uniqueCounters, r.missingCounters)
        assertTrue(r.captureRatio in 0.49..0.51, "ratio ${r.captureRatio}")
        assertEquals(TimebaseCheck.Verdict.PLAUSIBLY_SAME_CLOCK, r.timebase.verdict)
    }

    @Test fun detects120FpsFullCapture() {
        val (log, disp) = build(1200, 1, 8.0, 0)
        val r = CaptureAnalysis.analyze(log, 0.0, disp)
        assertTrue(r.rateClass.contains("120"), r.rateClass)
        assertEquals(0, r.missingCounters)
        assertEquals(1200, r.uniqueFramesEstimate)
    }

    @Test fun latencyIsArrivalMinusExpectedPresentation() {
        val (log, disp) = build(1200, 1, latencyMs = 12.0, tsOffsetNs = 0)
        val r = CaptureAnalysis.analyze(log, 0.0, disp)
        val lat = r.latency!!
        assertEquals(12.0, lat.displayToArrivalMs.median, 0.01)
        assertEquals(12.0 + period / 1e6, lat.frameStartToArrivalMs.median, 0.01)
        assertEquals(0, lat.negativeCount)
    }

    @Test fun mismatchedImageTimebaseIsDetectedAndNotUsed() {
        // producer clock offset by 3.2 days: must NOT be subtracted from arrival time
        val (log, disp) = build(600, 1, 10.0, tsOffsetNs = 276_480_000_000_000L)
        val r = CaptureAnalysis.analyze(log, 0.0, disp)
        assertEquals(TimebaseCheck.Verdict.MISMATCH, r.timebase.verdict)
        assertEquals(null, r.bufferAgeMs)
        // frame-interval stats stay valid because they only compare producer timestamps to each other
        assertTrue(r.rateClass.contains("120"))
    }

    @Test fun counterWrapIsUnwrappedAgainstDisplayLog() {
        val n = 70_000
        val disp = DisplayLog(n + 1)
        val t0 = 1_000_000_000L
        for (i in 0 until n) disp.recordFrame(t0 + i * period, t0 + i * period + period)
        val log = FrameLog(n)
        for (i in 0 until n step 7) {
            val s = log.next()
            log.arrivalNs[s] = t0 + i * period + 20_000_000L
            log.imageTsNs[s] = log.arrivalNs[s] - 5_000_000L
            log.counter[s] = i and 0xFFFF; log.patLuma[s] = -1; log.meanLuma100[s] = 5000; log.burst[s] = 1
            log.commit()
        }
        val r = CaptureAnalysis.analyze(log, 0.0, disp)
        // arrival is 20 ms after the display frame's vsync start, expected presentation is one period later
        assertEquals(20.0 - period / 1e6, r.latency!!.displayToArrivalMs.median, 0.01)
        assertEquals(0, r.latency!!.negativeCount)
        assertTrue(r.displayedSpan > 65_536, "span ${r.displayedSpan} must exceed one 16-bit wrap")
    }

    @Test fun flipLatencyPairsByCounter() {
        val disp = DisplayLog(2000)
        val t0 = 1_000_000_000L
        for (i in 0 until 1000) disp.recordFrame(t0 + i * period, t0 + i * period + period)
        disp.recordFlip(100, 1); disp.recordFlip(400, 0); disp.recordFlip(700, 1)
        fun boxState(i: Int) = if (i >= 700) 1 else if (i >= 400) 0 else if (i >= 100) 1 else 0
        val log = FrameLog(1000)
        // only odd display frames reach the app (60 fps from 120 Hz), so each even flip frame is seen one refresh later
        for (i in 1 until 1000 step 2) {
            val s = log.next()
            log.arrivalNs[s] = t0 + i * period + 15_000_000L
            log.imageTsNs[s] = log.arrivalNs[s] - 3_000_000L
            log.counter[s] = i
            log.patLuma[s] = if (boxState(i) == 1) 255 else 0
            log.meanLuma100[s] = 5000; log.burst[s] = 1
            log.commit()
        }
        val r = CaptureAnalysis.analyze(log, 0.0, disp).latency!!
        assertEquals(3, r.flipsDisplayed)
        assertEquals(3, r.flipsCaptured)
        assertEquals(1.0, r.flipRefreshesLate.median, 1e-9)
        // arrival(101) - expectedPresent(100) = 15 ms
        assertEquals(15.0, r.flipLatencyMs.median, 0.01)
    }
}
