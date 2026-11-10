package dev.dunkblock.core

import java.nio.ByteBuffer
import kotlin.math.abs

/** Normalized rectangle (0..1 of frame width/height). */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun contains(x: Float, y: Float) = x >= l && x < r && y >= t && y < b
}

data class MotionConfig(
    /** Analysis-frame size (the downscaled grayscale frame all backends work on). */
    val width: Int,
    val height: Int,
    val cols: Int = 16,
    val rows: Int = 12,
    /** |cur-prev| above this (0..255) counts a pixel as "changed". */
    val diffThreshold: Int = 24,
    /** Regions the user controls / static HUD. Cells whose centre falls in one are ignored. */
    val excluded: List<NRect> = DEFAULT_EXCLUDED,
    /** The "local activity" signal is the mean energy of the K most active cells. */
    val topK: Int = 6,
    val roiFracW: Float = 0.30f,
    val roiFracH: Float = 0.45f,
) {
    val cellCount get() = cols * rows

    companion object {
        /** Landscape layout guess: score bar on top, joystick bottom-left, buttons bottom-right. UNVERIFIED for the game. */
        val DEFAULT_EXCLUDED = listOf(
            NRect(0f, 0f, 1f, 0.10f),
            NRect(0f, 0.55f, 0.28f, 1f),
            NRect(0.72f, 0.45f, 1f, 1f),
        )
    }
}

/** Output of one motion-analysis step. Reused across frames (no allocation). */
class MotionFeatures(cells: Int) {
    val cellEnergy = FloatArray(cells)
    var hasPrev = false
    /** Mean energy over active cells (0..1): camera pans / cut-ins push this up. */
    var global = 0f
    /** Mean energy of the top-K active cells (0..1): localized activity such as a player. */
    var local = 0f
    var peakCell = -1
    /** Energy-weighted centroid of the top-K cells, normalized to the full frame (0..1). */
    var cx = 0.5f
    var cy = 0.5f
    /** Pixels with any change at all (diff>0) at analysis resolution; 0 => identical frames. */
    var anyChanged = 0
    // ROI refinement (changed pixels inside the tracked ROI)
    var roiMass = 0f
    var roiTop = 0f
    var roiBottom = 0f
    var meanLuma100 = 0
    /** Scratch for the top-K selection (kept here so analysis allocates nothing per frame). */
    internal val picked = IntArray(64)

    fun reset() {
        java.util.Arrays.fill(cellEnergy, 0f)
        hasPrev = false; global = 0f; local = 0f; peakCell = -1; cx = 0.5f; cy = 0.5f
        anyChanged = 0; roiMass = 0f; roiTop = 0f; roiBottom = 0f; meanLuma100 = 0
    }
}

/**
 * A motion-analysis backend. The caller times the three stages separately, which is why the API is split:
 *  pre    : downscale + grayscale into the backend's current analysis frame
 *  roi    : extract the region of interest (around the previously tracked activity) from current/previous frames
 *  motion : difference / threshold / grid + ROI features, then rotate current->previous
 */
interface MotionBackend {
    val name: String
    fun pre(src: ByteBuffer, srcW: Int, srcH: Int, rowStride: Int, pixelStride: Int)
    fun roi(trackValid: Boolean, trackX: Float, trackY: Float)
    fun motion(out: MotionFeatures)
    /** Copies the most recently analysed gray frame (width*height bytes) — used for evidence images only. */
    fun copyLatestGray(dst: ByteArray)
    fun release() {}
}

object RgbaDownscaler {
    /** Offsets (in bytes) of the source pixel sampled for each destination column / row. */
    fun colOffsets(srcW: Int, dstW: Int, pixelStride: Int) =
        IntArray(dstW) { (((it + 0.5) * srcW / dstW).toInt().coerceIn(0, srcW - 1)) * pixelStride }

    fun rowOffsets(srcH: Int, dstH: Int, rowStride: Int) =
        IntArray(dstH) { (((it + 0.5) * srcH / dstH).toInt().coerceIn(0, srcH - 1)) * rowStride }

    /** Nearest-neighbour RGBA -> luma. Reads only dstW*dstH source pixels regardless of capture resolution. */
    fun toGray(src: ByteBuffer, colOff: IntArray, rowOff: IntArray, dst: ByteArray, dstW: Int, dstH: Int): Int {
        var lumaSum = 0L
        var o = 0
        for (y in 0 until dstH) {
            val rb = rowOff[y]
            for (x in 0 until dstW) {
                val p = rb + colOff[x]
                val r = src.get(p).toInt() and 0xFF
                val g = src.get(p + 1).toInt() and 0xFF
                val b = src.get(p + 2).toInt() and 0xFF
                val l = (r * 77 + g * 151 + b * 28) shr 8
                dst[o++] = l.toByte()
                lumaSum += l
            }
        }
        return ((lumaSum * 100) / (dstW.toLong() * dstH)).toInt()
    }
}

/** Pure-Kotlin reference/fast-path backend (no OpenCV). */
class KotlinMotion(private val cfg: MotionConfig, srcWForMaps: Int, srcHForMaps: Int, rowStrideForMaps: Int, pixelStride: Int = 4) : MotionBackend {
    override val name = "KOTLIN"
    private val w = cfg.width
    private val h = cfg.height
    private var cur = ByteArray(w * h)
    private var prev = ByteArray(w * h)
    private var havePrev = false
    private var lumaMean100 = 0

    private var colOff = RgbaDownscaler.colOffsets(srcWForMaps, w, pixelStride)
    private var rowOff = RgbaDownscaler.rowOffsets(srcHForMaps, h, rowStrideForMaps)
    private var mapsFor = longArrayOf(srcWForMaps.toLong(), srcHForMaps.toLong(), rowStrideForMaps.toLong())

    private val cellOfCol = IntArray(w) { it * cfg.cols / w }
    private val cellRowBase = IntArray(h) { (it * cfg.rows / h) * cfg.cols }
    private val cellArea = FloatArray(cfg.cellCount)
    private val active = BooleanArray(cfg.cellCount)
    private val counts = IntArray(cfg.cellCount)

    // ROI crops
    private val roiW = (w * cfg.roiFracW).toInt().coerceAtLeast(8)
    private val roiH = (h * cfg.roiFracH).toInt().coerceAtLeast(8)
    private var roiX = 0
    private var roiY = 0
    private val roiCur = ByteArray(roiW * roiH)
    private val roiPrev = ByteArray(roiW * roiH)

    init {
        for (y in 0 until h) for (x in 0 until w) cellArea[cellRowBase[y] + cellOfCol[x]] += 1f
        for (r in 0 until cfg.rows) for (c in 0 until cfg.cols) {
            val nx = (c + 0.5f) / cfg.cols
            val ny = (r + 0.5f) / cfg.rows
            active[r * cfg.cols + c] = cfg.excluded.none { it.contains(nx, ny) }
        }
    }

    /** Test hook: load a ready-made gray frame instead of downscaling RGBA. */
    fun loadGray(gray: ByteArray) {
        require(gray.size == w * h)
        System.arraycopy(gray, 0, cur, 0, gray.size)
    }

    override fun pre(src: ByteBuffer, srcW: Int, srcH: Int, rowStride: Int, pixelStride: Int) {
        if (mapsFor[0] != srcW.toLong() || mapsFor[1] != srcH.toLong() || mapsFor[2] != rowStride.toLong()) {
            colOff = RgbaDownscaler.colOffsets(srcW, w, pixelStride)
            rowOff = RgbaDownscaler.rowOffsets(srcH, h, rowStride)
            mapsFor = longArrayOf(srcW.toLong(), srcH.toLong(), rowStride.toLong())
        }
        lumaMean100 = RgbaDownscaler.toGray(src, colOff, rowOff, cur, w, h)
    }

    override fun roi(trackValid: Boolean, trackX: Float, trackY: Float) {
        val cxp = if (trackValid) trackX else 0.5f
        val cyp = if (trackValid) trackY else 0.5f
        roiX = ((cxp * w) - roiW / 2).toInt().coerceIn(0, w - roiW)
        roiY = ((cyp * h) - roiH / 2).toInt().coerceIn(0, h - roiH)
        for (y in 0 until roiH) {
            val s = (roiY + y) * w + roiX
            System.arraycopy(cur, s, roiCur, y * roiW, roiW)
            System.arraycopy(prev, s, roiPrev, y * roiW, roiW)
        }
    }

    override fun motion(out: MotionFeatures) {
        out.meanLuma100 = lumaMean100
        if (!havePrev) {
            havePrev = true
            swap()
            out.reset(); out.meanLuma100 = lumaMean100
            return
        }
        java.util.Arrays.fill(counts, 0)
        val thr = cfg.diffThreshold
        var any = 0
        var o = 0
        for (y in 0 until h) {
            val rb = cellRowBase[y]
            for (x in 0 until w) {
                val d = abs((cur[o].toInt() and 0xFF) - (prev[o].toInt() and 0xFF))
                o++
                if (d != 0) {
                    any++
                    if (d > thr) counts[rb + cellOfCol[x]]++
                }
            }
        }
        out.hasPrev = true
        out.anyChanged = any
        gridFeatures(counts, cellArea, active, cfg, out)

        // ROI refinement
        var mass = 0
        var top = roiH
        var bottom = -1
        for (y in 0 until roiH) {
            var rowHit = false
            val b = y * roiW
            for (x in 0 until roiW) {
                val d = abs((roiCur[b + x].toInt() and 0xFF) - (roiPrev[b + x].toInt() and 0xFF))
                if (d > thr) { mass++; rowHit = true }
            }
            if (rowHit) { if (y < top) top = y; bottom = y }
        }
        out.roiMass = mass.toFloat() / (roiW * roiH)
        out.roiTop = if (bottom >= 0) (roiY + top).toFloat() / h else 0f
        out.roiBottom = if (bottom >= 0) (roiY + bottom).toFloat() / h else 0f
        swap()
    }

    private fun swap() { val t = cur; cur = prev; prev = t }

    // After motion() the latest frame has been rotated into `prev`.
    override fun copyLatestGray(dst: ByteArray) = System.arraycopy(prev, 0, dst, 0, minOf(dst.size, prev.size))
}

/** Shared by all backends: turns per-cell changed-pixel counts into the global/local features. */
fun gridFeatures(counts: IntArray, cellArea: FloatArray, active: BooleanArray, cfg: MotionConfig, out: MotionFeatures) {
    val n = cfg.cellCount
    var sum = 0f
    var nAct = 0
    for (i in 0 until n) {
        val e = counts[i] / cellArea[i]
        out.cellEnergy[i] = e
        if (active[i]) { sum += e; nAct++ }
    }
    out.global = if (nAct > 0) sum / nAct else 0f
    // top-K by partial selection (K is tiny)
    val k = cfg.topK.coerceAtMost(nAct).coerceAtMost(out.picked.size).coerceAtLeast(1)
    var topSum = 0f
    var wx = 0f; var wy = 0f; var wsum = 0f
    var peak = -1; var peakV = -1f
    val picked = out.picked
    for (j in 0 until k) {
        var bi = -1; var bv = -1f
        for (i in 0 until n) {
            if (!active[i]) continue
            val e = out.cellEnergy[i]
            if (e > bv) { var used = false; for (q in 0 until j) if (picked[q] == i) { used = true; break }; if (!used) { bv = e; bi = i } }
        }
        if (bi < 0) break
        picked[j] = bi
        topSum += bv
        val cxn = ((bi % cfg.cols) + 0.5f) / cfg.cols
        val cyn = ((bi / cfg.cols) + 0.5f) / cfg.rows
        wx += cxn * bv; wy += cyn * bv; wsum += bv
        if (bv > peakV) { peakV = bv; peak = bi }
    }
    out.local = topSum / k
    out.peakCell = if (peakV > 0f) peak else -1
    if (wsum > 1e-6f) { out.cx = wx / wsum; out.cy = wy / wsum }
}
