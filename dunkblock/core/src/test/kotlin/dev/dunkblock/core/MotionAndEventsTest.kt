package dev.dunkblock.core

import java.nio.ByteBuffer
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionAndEventsTest {
    private val aw = 192
    private val ah = 108
    private val cfg = MotionConfig(aw, ah, excluded = emptyList())
    private val frameNs = 8_333_333L

    /** Synthetic gray scene: flat court with sensor noise and an optional bright "player" square. */
    private fun scene(rnd: Random, px: Int, py: Int, size: Int, noise: Int, shift: Int = 0): ByteArray {
        val g = ByteArray(aw * ah)
        for (y in 0 until ah) for (x in 0 until aw) {
            var v = 90 + shift + (if (noise > 0) rnd.nextInt(2 * noise + 1) - noise else 0)
            if (px >= 0 && x in px until px + size && y in py until py + size) v = 230
            g[y * aw + x] = v.coerceIn(0, 255).toByte()
        }
        return g
    }

    private class Run(val det: AttackEventDetector, var detectedAtFrame: Int = -1, var frame: Int = 0)

    /** Plays a scripted sequence through backend + detector, returns the detector and the frame index of first detection. */
    private fun play(frames: Int, scripted: (Int, Random) -> ByteArray): Pair<AttackEventDetector, Int> {
        val rnd = Random(42)
        val backend = KotlinMotion(cfg, aw, ah, aw * 4)
        val feat = MotionFeatures(cfg.cellCount)
        var firstDetect = -1
        var f = 0
        val det = AttackEventDetector(DetectorConfig(warmupFrames = 20), object : AttackEventDetector.Listener {
            override fun onDetected(e: AttackEvent) { if (firstDetect < 0) firstDetect = f }
            override fun onFinalized(e: AttackEvent) {}
        })
        var trackValid = false; var tx = 0.5f; var ty = 0.5f
        while (f < frames) {
            backend.loadGray(scripted(f, rnd))
            backend.roi(trackValid, tx, ty)
            backend.motion(feat)
            if (feat.hasPrev) {
                det.onFrame(f * frameNs, f * frameNs, feat.local, feat.global, feat.cy)
                trackValid = feat.local > 0.02f; tx = feat.cx; ty = feat.cy
            }
            f++
        }
        det.flush(f * frameNs)
        return det to firstDetect
    }

    @Test fun quietNoiseProducesNoEvents() {
        val (det, first) = play(600) { _, r -> scene(r, -1, 0, 0, noise = 3) }
        assertEquals(0, det.events.size)
        assertEquals(-1, first)
    }

    @Test fun movingPlayerIsDetectedWithinFewFramesOfOnset() {
        val onset = 100
        val (det, first) = play(300) { f, r ->
            // idle, then the square jumps upward at 3px/frame for 40 frames, then idles
            val moving = f >= onset && f < onset + 40
            val py = if (f < onset) 80 else if (moving) 80 - (f - onset) * 3 else 80 - 40 * 3
            scene(r, 90, py.coerceAtLeast(0), 14, noise = 3)
        }
        assertEquals(1, det.events.size, "exactly one event")
        val e = det.events[0]
        assertTrue(first - onset in 0..3, "detected $first frames vs onset $onset")
        assertEquals("UPWARD_BURST", e.pattern)
        assertTrue(e.durationMs > 150)
    }

    @Test fun stutteringMotionIsMergedIntoOneEvent() {
        // two bursts 150 ms apart (inside cooldown) must not be reported twice
        val (det, _) = play(400) { f, r ->
            val phase = f - 100
            val on = phase in 0..20 || phase in 38..58
            val py = 80 - (if (on) (if (phase in 0..20) phase else phase - 18) * 3 else 0)
            scene(r, 90 + (if (on) (f % 2) * 6 else 0), py.coerceAtLeast(0), 14, noise = 2)
        }
        assertTrue(det.events.size <= 1, "got ${det.events.size} events; merged=${det.mergedDuplicates}")
    }

    @Test fun globalBrightnessShiftIsSuppressedNotReported() {
        // whole-screen change (cut-in / camera transition) must not become an attack event
        val (det, _) = play(300) { f, r -> scene(r, -1, 0, 0, noise = 2, shift = if (f in 100..110) 60 else 0) }
        assertEquals(0, det.events.size)
        assertTrue(det.suppressedGlobalFrames > 0)
    }

    @Test fun rgbaDownscalePathMatchesGrayPath() {
        val sw = 768; val sh = 432; val stride = sw * 4 + 32
        val buf = ByteBuffer.allocateDirect(stride * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val p = y * stride + x * 4
            val v = (x / 8 + y / 8) and 0xFF
            buf.put(p, v.toByte()); buf.put(p + 1, v.toByte()); buf.put(p + 2, v.toByte()); buf.put(p + 3, 255.toByte())
        }
        val b = KotlinMotion(cfg, sw, sh, stride)
        val feat = MotionFeatures(cfg.cellCount)
        b.pre(buf, sw, sh, stride, 4); b.roi(false, 0f, 0f); b.motion(feat)
        assertEquals(false, feat.hasPrev)
        // second identical frame: no motion at all
        b.pre(buf, sw, sh, stride, 4); b.roi(false, 0f, 0f); b.motion(feat)
        assertTrue(feat.hasPrev)
        assertEquals(0, feat.anyChanged)
        assertEquals(0f, feat.global)
    }

    @Test fun excludedRegionsAreIgnored() {
        val c2 = MotionConfig(aw, ah, excluded = listOf(NRect(0f, 0f, 1f, 1f))) // everything excluded
        val b = KotlinMotion(c2, aw, ah, aw * 4)
        val feat = MotionFeatures(c2.cellCount)
        val r = Random(1)
        b.loadGray(scene(r, -1, 0, 0, 0)); b.roi(false, 0f, 0f); b.motion(feat)
        b.loadGray(scene(r, 10, 10, 30, 0)); b.roi(false, 0f, 0f); b.motion(feat)
        assertEquals(0f, feat.local)
        assertEquals(0f, feat.global)
    }
}

class TruthMatcherTest {
    private fun ev(id: Int, onsetMs: Long, detectMs: Long) = AttackEvent(id, onsetMs * 1_000_000, 0, detectMs * 1_000_000, 0.5f)

    @Test fun matchesJumpsFakesAndFalseEvents() {
        val truth = listOf(
            TruthEvent(10, 1_000_000_000L, "JUMP"),
            TruthEvent(20, 3_000_000_000L, "FAKE"),
            TruthEvent(30, 5_000_000_000L, "JUMP"),
        )
        val events = listOf(ev(1, 1010, 1027), ev(2, 2000, 2010), ev(3, 3020, 3036))
        val r = TruthMatcher.match(truth, events)
        assertEquals(2, r.jumps); assertEquals(1, r.jumpsDetected)
        assertEquals(1, r.fakes); assertEquals(1, r.fakesTriggered)
        assertEquals(1, r.unmatchedEvents)
        assertEquals(27.0, r.detectDelayMs.median, 1e-6)
        assertEquals(10.0, r.onsetDelayMs.median, 1e-6)
    }
}
