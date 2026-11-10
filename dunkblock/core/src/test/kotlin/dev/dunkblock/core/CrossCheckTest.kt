package dev.dunkblock.core

import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes a realistic synthetic session (jittery 120 Hz display, capture dropping ~1/3 of frames, variable
 * processing time) through the same CSV writers the app uses, into build/crosscheck/. CI and developers can
 * then run tools/analyze.py on it and compare with the Kotlin summary printed here.
 */
class CrossCheckTest {
    @Test fun writeSyntheticSessionForPythonCrossCheck() {
        val rnd = Random(7)
        val period = 8_333_333L
        val nd = 3600
        val t0 = 50_000_000_000L
        val disp = DisplayLog(nd)
        var t = t0
        for (i in 0 until nd) {
            val jitter = if (rnd.nextInt(200) == 0) period else 0L // occasional UI-thread jank
            t += period + jitter
            disp.recordFrame(t, t + period)
            if (i > 0 && i % 97 == 0) disp.recordFlip(i, (i / 97) % 2)
        }
        val log = FrameLog(nd)
        var state = 0
        for (i in 0 until nd) {
            if (disp.flipState[i] >= 0) state = disp.flipState[i].toInt()
            if (rnd.nextInt(3) == 0) continue // dropped by capture
            val s = log.next()
            val arr = disp.expectedPresentNs[i] + 6_000_000L + rnd.nextInt(8_000_000)
            log.arrivalNs[s] = arr
            log.imageTsNs[s] = disp.expectedPresentNs[i] + 1_000_000L
            log.burst[s] = if (rnd.nextInt(50) == 0) 2 else 1
            log.counter[s] = if (rnd.nextInt(100) == 0) -1 else i and 0xFFFF
            log.patLuma[s] = if (state == 1) 250 else 5
            log.meanLuma100[s] = 6000
            val ana = 700 + rnd.nextInt(2500)
            log.tCopyUs[s] = 20; log.tPreUs[s] = ana / 2; log.tRoiUs[s] = 30; log.tMotionUs[s] = ana / 2 - 30
            log.tAnalysisUs[s] = ana; log.tTotalUs[s] = ana + 60
            log.commit()
        }
        val dir = File("build/crosscheck/session").apply { mkdirs() }
        File(dir, "frames.csv").bufferedWriter().use { Csv.frames(log, it) }
        File(dir, "display.csv").bufferedWriter().use { Csv.display(disp, it) }
        val report = SessionReport.build("crosscheck", log, disp, emptyList(), null, emptyList(), warmupSec = 1.0)
        File(dir, "summary.txt").writeText(report)
        println(report)
        assertTrue(report.contains("캡처 비율"))
    }
}
