package dev.dunkblock.bench

import android.graphics.Bitmap
import android.util.Log
import dev.dunkblock.core.RgbaDownscaler
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.max

/**
 * GAME mode helper for learning BLOCK TIMING from the user's own play (no input is ever injected here):
 *  - watches a small box on the game's block button (position from the ⑦ calibration) every frame;
 *    a sudden change in its brightness is taken as "the user pressed block" (verified offline: the per-frame
 *    value is logged as block_roi_luma_x100),
 *  - keeps a ring of 480-px grayscale frames at ~60 fps; on a press it saves 1.5 s before to 0.5 s after as
 *    JPEGs, so each clip shows the shooter's motion leading up to the press and whether the block worked.
 */
class PressClipper(
    private val cfg: Bench.Config,
    private val io: ExecutorService,
    private val dir: File,
    private val startedNs: Long,
) {
    private val clipW = 480
    private var clipH = 0
    private var colOff = IntArray(0)
    private var rowOff = IntArray(0)
    private var mapKey = 0L
    private val ringN = 150
    private var ring: Array<ByteArray> = emptyArray()
    private val ringT = LongArray(ringN)
    private var pos = 0
    private var filled = 0
    private var frameNo = 0

    private var base = -1f
    private var pressed = false
    private var lastPressNs = 0L
    @Volatile var presses = 0; private set
    @Volatile var clips = 0; private set
    private var clipEndNs = 0L
    private var clipPressNs = 0L
    val pressLog = StringBuilder("t_ns,t_s,luma,baseline,delta,clip\n")

    /** Returns the block-button box luma x100, or -1 when not applicable (portrait). Capture thread only. */
    fun onFrame(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, tNs: Long): Int {
        if (w <= h || pixelStride != 4) return -1
        val luma = roiLuma(buf, w, h, rowStride)
        detectPress(luma, tNs)
        frameNo++
        if (cfg.pressClips && frameNo % 2 == 0) {
            ensureRing(w, h, rowStride)
            RgbaDownscaler.toGray(buf, colOff, rowOff, ring[pos], clipW, clipH)
            ringT[pos] = tNs
            pos = (pos + 1) % ringN
            if (filled < ringN) filled++
            if (clipEndNs != 0L && tNs >= clipEndNs) { saveClip(clipPressNs); clipEndNs = 0L }
        }
        return (luma * 100).toInt()
    }

    private fun roiLuma(buf: ByteBuffer, w: Int, h: Int, rowStride: Int): Float {
        val cx = (cfg.blockFx * w).toInt()
        val cy = (cfg.blockFy * h).toInt()
        val half = max(4, (0.012f * w).toInt())
        var sum = 0; var n = 0
        for (gy in -3..3) for (gx in -3..3) {
            val x = (cx + gx * half / 3).coerceIn(0, w - 1)
            val y = (cy + gy * half / 3).coerceIn(0, h - 1)
            val o = y * rowStride + x * 4
            val r = buf.get(o).toInt() and 0xFF; val g = buf.get(o + 1).toInt() and 0xFF; val b = buf.get(o + 2).toInt() and 0xFF
            sum += (r * 77 + g * 151 + b * 28) shr 8; n++
        }
        return sum.toFloat() / n
    }

    private fun detectPress(l: Float, tNs: Long) {
        if (base < 0) { base = l; return }
        val d = l - base
        if (!pressed && abs(d) > PRESS_THR && tNs - lastPressNs > 700_000_000L) {
            pressed = true
            lastPressNs = tNs
            presses++
            val startClip = cfg.pressClips && clipEndNs == 0L && clips < MAX_CLIPS && filled > 30
            if (startClip) { clipPressNs = tNs; clipEndNs = tNs + 500_000_000L }
            pressLog.append(tNs).append(',').append(String.format(Locale.ROOT, "%.3f,%.1f,%.1f,%.1f,%d\n",
                (tNs - startedNs) / 1e9, l, base, d, if (startClip) clips + 1 else 0))
        } else if (pressed && abs(d) < PRESS_THR / 2) {
            pressed = false
        }
        if (!pressed) base += 0.05f * (l - base)
    }

    private fun ensureRing(w: Int, h: Int, rowStride: Int) {
        val key = (w.toLong() shl 40) or (h.toLong() shl 20) or rowStride.toLong()
        if (key == mapKey) return
        mapKey = key
        clipH = (clipW.toLong() * h / w).toInt()
        colOff = RgbaDownscaler.colOffsets(w, clipW, 4)
        rowOff = RgbaDownscaler.rowOffsets(h, clipH, rowStride)
        ring = Array(ringN) { ByteArray(clipW * clipH) }
        pos = 0; filled = 0
    }

    private fun saveClip(pressNs: Long) {
        clips++
        val n = clips
        val frames = ArrayList<Pair<Long, ByteArray>>()
        for (i in 0 until filled) {
            val idx = (pos - filled + i + ringN) % ringN
            val t = ringT[idx]
            if (t >= pressNs - 1_500_000_000L && t <= pressNs + 500_000_000L) frames.add(t to ring[idx].copyOf())
        }
        val cw = clipW; val ch = clipH
        val pressAt = (pressNs - startedNs) / 1e9
        io.execute {
            try {
                val d = File(dir, String.format(Locale.ROOT, "clip_%02d_%06.1fs", n, pressAt)).apply { mkdirs() }
                val px = IntArray(cw * ch)
                for ((i, fr) in frames.withIndex()) {
                    val (t, g) = fr
                    for (j in px.indices) { val v = g[j].toInt() and 0xFF; px[j] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                    val bmp = Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888)
                    val rel = (t - pressNs) / 1e6
                    FileOutputStream(File(d, String.format(Locale.ROOT, "f%03d_%+06.1fms.jpg", i, rel))).use { bmp.compress(Bitmap.CompressFormat.JPEG, 75, it) }
                    bmp.recycle()
                }
            } catch (t: Throwable) {
                Log.e("DunkBlockClip", "clip write failed", t)
            }
        }
    }

    companion object {
        const val PRESS_THR = 12f
        const val MAX_CLIPS = 15
    }
}
