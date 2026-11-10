package dev.dunkblock.bench

import dev.dunkblock.core.MotionBackend
import dev.dunkblock.core.MotionConfig
import dev.dunkblock.core.MotionFeatures
import dev.dunkblock.core.gridFeatures
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * OpenCV backend. The captured RGBA plane is wrapped as a Mat WITHOUT copying (direct ByteBuffer + row
 * stride), resized to the analysis size, converted to gray, then differenced. Cell layout and feature
 * definitions are identical to the Kotlin backend (see core/Motion.kt), so results are comparable.
 */
class OpenCvMotion(private val cfg: MotionConfig, private val areaResize: Boolean) : MotionBackend {
    override val name = "OPENCV"
    private val w = cfg.width
    private val h = cfg.height
    private val small4 = Mat()
    private var cur = Mat(h, w, CvType.CV_8UC1)
    private var prev = Mat(h, w, CvType.CV_8UC1)
    private val diff = Mat(h, w, CvType.CV_8UC1)
    private val bin = Mat(h, w, CvType.CV_8UC1)
    private val rowMax = Mat()
    private var havePrev = false
    private var lumaMean100 = 0

    private val cellViews: Array<Mat>
    private val cellArea = FloatArray(cfg.cellCount)
    private val active = BooleanArray(cfg.cellCount)
    private val counts = IntArray(cfg.cellCount)

    private val roiW = (w * cfg.roiFracW).toInt().coerceAtLeast(8)
    private val roiH = (h * cfg.roiFracH).toInt().coerceAtLeast(8)
    private var roiRect = Rect(0, 0, roiW, roiH)
    private val rowBytes = ByteArray(roiH)

    init {
        // Same cell membership as KotlinMotion: column x belongs to cell x*cols/w.
        fun firstIndex(c: Int, n: Int, cells: Int): Int { var x = 0; while (x < n && x * cells / n < c) x++; return x }
        val xs = IntArray(cfg.cols + 1) { firstIndex(it, w, cfg.cols) }
        val ys = IntArray(cfg.rows + 1) { firstIndex(it, h, cfg.rows) }
        cellViews = Array(cfg.cellCount) { i ->
            val c = i % cfg.cols; val r = i / cfg.cols
            val rect = Rect(xs[c], ys[r], (xs[c + 1] - xs[c]).coerceAtLeast(1), (ys[r + 1] - ys[r]).coerceAtLeast(1))
            cellArea[i] = (rect.width * rect.height).toFloat()
            val nx = (c + 0.5f) / cfg.cols; val ny = (r + 0.5f) / cfg.rows
            active[i] = cfg.excluded.none { it.contains(nx, ny) }
            bin.submat(rect)
        }
    }

    override fun pre(src: ByteBuffer, srcW: Int, srcH: Int, rowStride: Int, pixelStride: Int) {
        val rgba = Mat(srcH, srcW, CvType.CV_8UC4, src, rowStride.toLong())
        Imgproc.resize(rgba, small4, Size(w.toDouble(), h.toDouble()), 0.0, 0.0,
            if (areaResize) Imgproc.INTER_AREA else Imgproc.INTER_NEAREST)
        Imgproc.cvtColor(small4, cur, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        lumaMean100 = (Core.mean(cur).`val`[0] * 100).toInt()
    }

    override fun roi(trackValid: Boolean, trackX: Float, trackY: Float) {
        val cx = if (trackValid) trackX else 0.5f
        val cy = if (trackValid) trackY else 0.5f
        val x = ((cx * w) - roiW / 2).toInt().coerceIn(0, w - roiW)
        val y = ((cy * h) - roiH / 2).toInt().coerceIn(0, h - roiH)
        roiRect = Rect(x, y, roiW, roiH)
    }

    override fun motion(out: MotionFeatures) {
        out.meanLuma100 = lumaMean100
        if (!havePrev) {
            havePrev = true
            swap()
            out.reset(); out.meanLuma100 = lumaMean100
            return
        }
        Core.absdiff(cur, prev, diff)
        out.anyChanged = Core.countNonZero(diff)
        Imgproc.threshold(diff, bin, cfg.diffThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        for (i in counts.indices) counts[i] = Core.countNonZero(cellViews[i])
        out.hasPrev = true
        gridFeatures(counts, cellArea, active, cfg, out)

        val roiBin = bin.submat(roiRect)
        val mass = Core.countNonZero(roiBin)
        Core.reduce(roiBin, rowMax, 1, Core.REDUCE_MAX, CvType.CV_8U)
        rowMax.get(0, 0, rowBytes)
        roiBin.release()
        var top = -1; var bottom = -1
        for (y in rowBytes.indices) if (rowBytes[y].toInt() != 0) { if (top < 0) top = y; bottom = y }
        out.roiMass = mass.toFloat() / (roiW * roiH)
        out.roiTop = if (top >= 0) (roiRect.y + top).toFloat() / h else 0f
        out.roiBottom = if (bottom >= 0) (roiRect.y + bottom).toFloat() / h else 0f
        swap()
    }

    override fun copyLatestGray(dst: ByteArray) { prev.get(0, 0, dst) }

    private fun swap() { val t = cur; cur = prev; prev = t }

    override fun release() {
        for (m in cellViews) m.release()
        listOf(small4, cur, prev, diff, bin, rowMax).forEach { it.release() }
    }
}
