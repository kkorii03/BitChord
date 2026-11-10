package dev.dunkblock.bench

import android.graphics.Bitmap
import android.util.Log
import dev.dunkblock.core.RgbaDownscaler
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.max

/**
 * GAME-mode recorder for learning block timing. No input is injected here.
 *  - Keeps a 4 s ring of 640-px grayscale frames at ~60 fps.
 *  - Saves a clip (JPEG frames named by their time relative to the event) when asked:
 *      "press" : the block button box visibly changed (the user's or the app's press), 1.5 s before .. 0.5 s after
 *      "ult"   : AutoBlocker saw the ultimate darkening, 1.0 s before .. 2.6 s after (the game's "블록" label shows up ~1.2 s later)
 *      "mark"  : the user tapped the on-screen 기록 button, 3.4 s before .. 0.4 s after
 *    Frames within -0.8..+1.0 s of the event are kept at full rate, the rest at half rate.
 *  - Logs every detected press and every clip in presses.csv / clips.csv.
 * The per-frame button-box luma is returned to the caller and logged as block_roi_luma_x100.
 */
class PressClipper(
    private val cfg: Bench.Config,
    private val io: ExecutorService,
    private val dir: File,
    private val startedNs: Long,
) {
    private class Req(val tNs: Long, val tag: String, val beforeNs: Long, val afterNs: Long)

    private val clipW = 640
    private var clipH = 0
    private var colOff = IntArray(0)
    private var rowOff = IntArray(0)
    private var mapKey = 0L
    private val ringN = 244
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
    private val requests = ConcurrentLinkedQueue<Req>()
    private val jobs = ArrayList<Req>()
    val pressLog = StringBuilder("t_ns,t_s,luma,baseline,delta\n")
    val clipLog = StringBuilder("clip,tag,event_t_ns,event_t_s,frames,folder\n")
    private val tagCount = HashMap<String, Int>()
    private val accepted = HashMap<String, Int>()

    /** Any thread. [tNs] = System.nanoTime() of the event. */
    fun request(tag: String, tNs: Long, beforeS: Double, afterS: Double) {
        requests.add(Req(tNs, tag, (beforeS * 1e9).toLong(), (afterS * 1e9).toLong()))
    }

    fun countsText(): String = synchronized(tagCount) { tagCount.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "없음" } }

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
            while (true) {
                val r = requests.poll() ?: break
                // Per-kind quota so practice-mode ult clips cannot use up the budget before the match (11-10 bundle).
                val g = clipGroup(r.tag)
                val used = accepted[g] ?: 0
                if (clips + jobs.size < MAX_CLIPS && used < groupCap(g)) { accepted[g] = used + 1; jobs.add(r) }
            }
            val it = jobs.iterator()
            while (it.hasNext()) {
                val j = it.next()
                if (tNs >= j.tNs + j.afterNs) { it.remove(); saveClip(j) }
            }
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
            // The slot also flips 전면 블록 <-> 슛 on every change of possession; tag the side so those can be told apart.
            request("press_" + ShotWatcher.sideName(Bench.shotSide), tNs, 1.5, 0.5)
            pressLog.append(tNs).append(',').append(String.format(Locale.ROOT, "%.3f,%.1f,%.1f,%.1f\n", (tNs - startedNs) / 1e9, l, base, d))
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

    private fun saveClip(j: Req) {
        clips++
        val n = clips
        synchronized(tagCount) { tagCount[j.tag] = (tagCount[j.tag] ?: 0) + 1 }
        val frames = ArrayList<Pair<Long, ByteArray>>()
        var k = 0
        for (i in 0 until filled) {
            val idx = (pos - filled + i + ringN) % ringN
            val t = ringT[idx]
            if (t < j.tNs - j.beforeNs || t > j.tNs + j.afterNs) continue
            // Trigger clips are only read for the game's verdict label (stays ~1 s) and the lift/jump: ~30 fps is
            // plenty and halves their size, so the match gets clips too (vc1035: 40 clips were used up by 227 s).
            // 기록-button clips cover 3.8 s to find a shot the trigger missed: ~15 fps keeps them near 1 MB so 40 fit.
            val mark = j.tag.startsWith("mark")
            val near = !mark && !j.tag.startsWith("trig") && t >= j.tNs - 800_000_000L && t <= j.tNs + 1_000_000_000L
            if (near || k % (if (mark) 4 else 2) == 0) frames.add(t to ring[idx].copyOf())
            k++
        }
        val cw = clipW; val ch = clipH
        val at = (j.tNs - startedNs) / 1e9
        val folder = String.format(Locale.ROOT, "clip_%02d_%s_%06.1fs", n, j.tag, at)
        clipLog.append(n).append(',').append(j.tag).append(',').append(j.tNs).append(',')
            .append(String.format(Locale.ROOT, "%.3f", at)).append(',').append(frames.size).append(',').append(folder).append('\n')
        io.execute {
            try {
                val d = File(dir, folder).apply { mkdirs() }
                val px = IntArray(cw * ch)
                for ((i, fr) in frames.withIndex()) {
                    val (t, g) = fr
                    for (q in px.indices) { val v = g[q].toInt() and 0xFF; px[q] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                    val bmp = Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888)
                    val rel = (t - j.tNs) / 1e6
                    FileOutputStream(File(d, String.format(Locale.ROOT, "f%03d_%+07.1fms.jpg", i, rel))).use { bmp.compress(Bitmap.CompressFormat.JPEG, 65, it) }
                    bmp.recycle()
                }
            } catch (t: Throwable) {
                Log.e("DunkBlockClip", "clip write failed", t)
            }
        }
    }

    companion object {
        const val PRESS_THR = 12f
        const val MAX_CLIPS = 140
        /** Each kind has its own share of [MAX_CLIPS] so one kind cannot use up the rest (vc1035: mark clips at 4 MB). */
        fun clipGroup(tag: String): String = when {
            tag.startsWith("ult") -> "ult"
            tag.startsWith("press") -> "press"
            tag.startsWith("trig") -> "trig"
            tag.startsWith("mark") -> "mark"
            else -> "other"
        }
        fun groupCap(g: String): Int = when (g) { "ult" -> 6; "press" -> 6; "trig" -> 70; "mark" -> 40; else -> 8 }
    }
}
