package com.music.bitchord

import com.music.bitchord.playback.smart.VersionAudioAligner
import com.music.bitchord.playback.smart.VersionSwapPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * [VersionAudioAligner.crossCorrelate] now runs the per-lag sums through an
 * FFT instead of the double loop it replaced. These tests pin the two
 * implementations together: same searchable range, same winning lag, same
 * confidence — a silent sign flip or wraparound here would turn every switch
 * into a misaligned one.
 */
class VersionAudioAlignerCorrelationTest {

    @Test
    fun `fft correlation matches brute force over many random envelopes`() {
        val random = Random(42)
        repeat(40) { case ->
            val sourceSize = 320 + random.nextInt(600)
            val targetSize = 320 + random.nextInt(600)
            val source = FloatArray(sourceSize) { random.nextFloat() }
            // Shift the target by a known lag and add noise, so the peak lands
            // somewhere plausible but not trivially at zero.
            val trueLag = random.nextInt(-150, 151)
            val target = FloatArray(targetSize) { index ->
                val sourceIndex = index - trueLag
                val base = if (sourceIndex in source.indices) source[sourceIndex] else random.nextFloat()
                (base + random.nextFloat() * 0.2f).coerceIn(0f, 1f)
            }

            val fft = VersionAudioAligner.crossCorrelate(source, target)
            val brute = bruteForce(source, target)

            assertEquals("floor differs in case $case", brute.floor, fft.floor)
            assertEquals("ceiling differs in case $case", brute.ceiling, fft.ceiling)
            assertEquals("best lag differs in case $case", brute.lagFrames, fft.lagFrames)
            assertTrue(
                "confidence differs in case $case: ${brute.confidence} vs ${fft.confidence}",
                (brute.confidence - fft.confidence).absoluteValue < 1e-6,
            )
        }
    }

    @Test
    fun `correlation finds a planted lag with high confidence`() {
        val random = Random(7)
        val source = FloatArray(900) { random.nextFloat() }
        val trueLag = 286 // 28.6 s at 100 fps — the field case's shape
        val target = FloatArray(900) { index ->
            val sourceIndex = index - trueLag
            if (sourceIndex in source.indices) source[sourceIndex] else random.nextFloat() * 0.3f
        }

        val result = VersionAudioAligner.crossCorrelate(source, target)

        assertEquals(trueLag, result.lagFrames)
        assertTrue("expected a confident peak, got ${result.confidence}", result.confidence > 0.9)
        assertTrue("peak must not be pinned to the searchable edge", result.lagFrames != result.floor)
        assertTrue("peak must not be pinned to the searchable edge", result.lagFrames != result.ceiling)
    }

    @Before
    fun resetAligner() = VersionAudioAligner.resetForTest()

    /**
     * An irregular beat train over a noise floor, at 100 frames per second: what
     * a decoded song's energy envelope looks like, and deterministic for a seed.
     */
    private fun beatTrain(frames: Int, seed: Int): FloatArray {
        val random = Random(seed)
        val env = FloatArray(frames) { random.nextFloat() * 0.5f }
        var at = random.nextInt(20, 60)
        while (at < frames) {
            for (k in 0 until 8) if (at + k < frames) env[at + k] += 1f - k / 8f
            at += random.nextInt(25, 90)
        }
        return env
    }

    /** [source] delayed by [lagFrames] (negative: advanced); frames it does not cover are a quiet noise floor. */
    private fun shifted(source: FloatArray, lagFrames: Int, size: Int, seed: Int): FloatArray {
        val random = Random(seed)
        return FloatArray(size) { index ->
            val sourceIndex = index - lagFrames
            if (sourceIndex in source.indices) source[sourceIndex] else random.nextFloat() * 0.3f
        }
    }

    @Test
    fun `a beat train delayed by 2_5 s is recovered with high confidence`() {
        val source = beatTrain(6000, seed = 11)
        val result = VersionAudioAligner.crossCorrelate(source, shifted(source, 250, 6000, seed = 12))

        assertEquals(250, result.lagFrames)
        assertTrue("expected a confident peak, got ${result.confidence}", result.confidence > 0.9)
    }

    @Test
    fun `a beat train advanced by 7 s is recovered with high confidence`() {
        val source = beatTrain(6000, seed = 21)
        val result = VersionAudioAligner.crossCorrelate(source, shifted(source, -700, 6000, seed = 22))

        assertEquals(-700, result.lagFrames)
        assertTrue("expected a confident peak, got ${result.confidence}", result.confidence > 0.9)
    }

    @Test
    fun `a shift beyond the searchable lag is refused rather than locked on at the edge`() {
        val source = beatTrain(6000, seed = 31)
        // 45 s late: past the +-40 s search range, so the true peak is unreachable.
        val result = VersionAudioAligner.crossCorrelate(source, shifted(source, 4500, 10_500, seed = 32))

        assertTrue(
            "no lag inside the range may look like a match, got ${result.confidence} at ${result.lagFrames}",
            result.confidence < VersionSwapPolicy.MIN_SWAP_CONFIDENCE,
        )
        assertTrue("the true 4500-frame shift is outside the range", result.ceiling < 4500)
    }

    // Gap: the lag cap (40 s) sits inside the 45 s window, so a music video
    // whose intro runs past it could never be aligned. A second pass reaches
    // further into the video's side, and only for such pairs.
    @Test
    fun `a 55 s video intro is out of one window's reach and found by the second pass`() {
        val song = beatTrain(4500, seed = 41)
        val video = shifted(song, 5500, 9000, seed = 42) // 55 s of intro, then the song

        val first = VersionAudioAligner.crossCorrelate(video.copyOf(4500), song)
        val pinned = first.lagFrames == first.floor || first.lagFrames == first.ceiling
        assertTrue(
            "one window must not pass this as measured, got ${first.confidence} at ${first.lagFrames}",
            pinned || first.confidence < VersionSwapPolicy.MIN_SWAP_CONFIDENCE,
        )
        assertEquals(
            VersionAudioAligner.Reach.SOURCE,
            VersionAudioAligner.reachFor(first, sourceIsVideo = true, targetIsVideo = false),
        )

        val reached = VersionAudioAligner.crossCorrelate(
            video,
            song,
            maxLagSec = VersionAudioAligner.REACH_MAX_LAG_SECONDS,
        )
        assertEquals(-5500, reached.lagFrames)
        assertTrue("expected a confident peak, got ${reached.confidence}", reached.confidence > 0.9)
        assertTrue(reached.lagFrames != reached.floor && reached.lagFrames != reached.ceiling)
    }

    @Test
    fun `only a pinned or failed full window pays for a second pass`() {
        val cap = (VersionAudioAligner.WINDOW_SECONDS * 100).toInt() - 500 // 40 s at 100 fps
        fun c(lag: Int, conf: Double, floor: Int = -cap, ceiling: Int = cap) =
            VersionAudioAligner.Correlation(lag, conf, floor, ceiling)

        // A confident peak inside the range: the common case costs nothing more.
        assertEquals(null, VersionAudioAligner.reachFor(c(286, 0.95), sourceIsVideo = true, targetIsVideo = false))
        // Pinned, no video side to prefer: the edge says which side.
        assertEquals(VersionAudioAligner.Reach.SOURCE, VersionAudioAligner.reachFor(c(-cap, 0.53), false, false))
        assertEquals(VersionAudioAligner.Reach.TARGET, VersionAudioAligner.reachFor(c(cap, 0.7), false, false))
        // The intro is the video's, whatever edge a mismatch happened to pin.
        assertEquals(VersionAudioAligner.Reach.TARGET, VersionAudioAligner.reachFor(c(-cap, 0.4), false, true))
        assertEquals(VersionAudioAligner.Reach.SOURCE, VersionAudioAligner.reachFor(c(120, 0.3), true, false))
        // Weak, unpinned, no video: nothing says where an intro would be.
        assertEquals(null, VersionAudioAligner.reachFor(c(120, 0.3), false, false))
        // A truncated head's range is narrower than the cap: more of it cannot help.
        assertEquals(null, VersionAudioAligner.reachFor(c(-500, 0.7, floor = -500), true, false))
        assertEquals(null, VersionAudioAligner.reachFor(VersionAudioAligner.Correlation(0, 0.0, 0, 0), true, false))
    }

    @Test
    fun `the lag cap leaves an edge lag something to be judged on`() {
        assertEquals(45.0, VersionAudioAligner.WINDOW_SECONDS, 0.0)
        assertEquals(90.0, VersionAudioAligner.REACH_SECONDS, 0.0)
        assertEquals(85.0, VersionAudioAligner.REACH_MAX_LAG_SECONDS, 0.0)
    }

    // Gap: offsets were keyed by id only, and a downloaded file holding a
    // video's sound is replaced by the song version under the same id.
    @Test
    fun `a replaced recording forgets every alignment it was in`() {
        VersionAudioAligner.storeOffset("mv", "song", 1_200L)
        VersionAudioAligner.storeOffset("other", "third", 300L)
        VersionAudioAligner.reject("song", "cover")
        assertEquals(1_200L, VersionAudioAligner.getCachedOffsetMs("mv", "song"))
        assertEquals(-1_200L, VersionAudioAligner.getCachedOffsetMs("song", "mv"))

        VersionAudioAligner.forget("song")

        assertEquals(null, VersionAudioAligner.getCachedOffsetMs("mv", "song"))
        assertEquals(null, VersionAudioAligner.getCachedOffsetMs("song", "mv"))
        assertTrue(!VersionAudioAligner.isRejected("song", "cover"))
        assertEquals("an unrelated pair stays", 300L, VersionAudioAligner.getCachedOffsetMs("other", "third"))
    }

    @Test
    fun `empty envelopes yield no measurable correlation`() {
        val result = VersionAudioAligner.crossCorrelate(FloatArray(0), FloatArray(500))
        assertEquals(0, result.lagFrames)
        assertEquals(0.0, result.confidence, 0.0)
    }

    /** The pre-FFT implementation, verbatim, as the reference oracle. */
    private fun bruteForce(
        sourceEnv: FloatArray,
        targetEnv: FloatArray,
        hopMs: Double = 10.0,
        maxLagSec: Double = 40.0,
    ): VersionAudioAligner.Correlation {
        if (sourceEnv.isEmpty() || targetEnv.isEmpty()) {
            return VersionAudioAligner.Correlation(0, 0.0, 0, 0)
        }

        val maxLagFrames = (maxLagSec * 1000.0 / hopMs).toInt()
        val minOverlapFrames = (3.0 * 1000.0 / hopMs).toInt()

        var bestLag = 0
        var bestCorrelation = -1.0

        val minLag = max(-maxLagFrames, -(sourceEnv.size - minOverlapFrames))
        val maxLag = min(maxLagFrames, targetEnv.size - minOverlapFrames)

        for (lag in minLag..maxLag) {
            val srcStart = max(0, -lag)
            val tgtStart = max(0, lag)
            val overlapLen = min(sourceEnv.size - srcStart, targetEnv.size - tgtStart)
            if (overlapLen < minOverlapFrames) continue

            var sumSrc = 0.0
            var sumTgt = 0.0
            for (k in 0 until overlapLen) {
                sumSrc += sourceEnv[srcStart + k]
                sumTgt += targetEnv[tgtStart + k]
            }
            val meanSrc = sumSrc / overlapLen
            val meanTgt = sumTgt / overlapLen

            var num = 0.0
            var denSrc = 0.0
            var denTgt = 0.0
            for (k in 0 until overlapLen) {
                val diffSrc = sourceEnv[srcStart + k] - meanSrc
                val diffTgt = targetEnv[tgtStart + k] - meanTgt
                num += diffSrc * diffTgt
                denSrc += diffSrc * diffSrc
                denTgt += diffTgt * diffTgt
            }

            val denom = sqrt(denSrc * denTgt)
            if (denom > 1e-6) {
                val r = num / denom
                if (r > bestCorrelation) {
                    bestCorrelation = r
                    bestLag = lag
                }
            }
        }

        return VersionAudioAligner.Correlation(bestLag, bestCorrelation, minLag, maxLag)
    }

    private val Double.absoluteValue: Double
        get() = if (this < 0) -this else this
}
