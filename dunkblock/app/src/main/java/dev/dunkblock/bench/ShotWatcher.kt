package dev.dunkblock.bench

import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService

/**
 * Regular-shot data collector (no input is injected here). Capture thread, every 2nd landscape frame (~60 Hz).
 *
 *  SIDE: the block-button slot shows "전면 블록" (blue fill + dark silhouettes) on defence and "슛" (orange ball above
 *   the centre) on offence. Measured on 60 vc1022 match frames: dark-pixel share 0.24 on defence vs <= 0.08 on
 *   offence; orange share ~0.02 when the user's team has the ball (0 on defence). Greyed buttons (준비 phase,
 *   cooldown) read as unknown.
 *  DISC: the game draws a solid red disc (ball icon inside) over the ball when the opponent team holds it, and it
 *   flies with the ball on a shot (frames 111.8 s / 117.8 s). Found as the densest window of disc-red cells on a coarse
 *   grid; accepted only when no second, similar blob exists (jersey outlines and court paint give several weak ones).
 *   Prototype on the same 60 frames: 9 visible discs found (fill 0.47..0.94), 0 false accepts.
 *  EVENTS: "disc_fast" = the disc moved faster than a carried ball can (a shot, pass or steal). Upward ones on
 *   defence are shot candidates and get a clip + one colour screen, so the next bundle can tell what precedes them.
 *  COLOUR: a 3.2 s ring of full-resolution colour windows around the ball (~30 Hz) is saved to colour/ for shot
 *   candidates, 기록 marks and ultimates (not for block-slot changes).
 * Everything per frame goes to shot.csv for offline design of the real trigger.
 */
class ShotWatcher(private val startedNs: Long, private val io: ExecutorService, private val colourDir: File) {
    @Volatile var side = SIDE_UNKNOWN; private set
    private var frameNo = 0
    private var mapKey = 0L
    private var step = 4
    private var gw = 0; private var gh = 0; private var k = 4
    private var sat = IntArray(0)
    private var aspect = 2.17f

    // last accepted disc positions (x / W, y / H)
    private val histN = 64
    private val hT = LongArray(histN); private val hX = FloatArray(histN); private val hY = FloatArray(histN)
    private var hPos = 0; private var hCount = 0
    private var lastFastNs = 0L
    private var shotClips = 0
    /** Set when a colour screen of this moment would help; FramePipeline clears it after saving one. */
    @Volatile var snapNow = false

    @Volatile var scans = 0; private set
    @Volatile var defScans = 0; private set
    @Volatile var offScans = 0; private set
    @Volatile var discScans = 0; private set
    @Volatile var fastEvents = 0; private set
    @Volatile var shotCandidates = 0; private set
    private var usTotal = 0L
    private var usMax = 0

    private val log = StringBuilder(1 shl 16).append("t_ns,t_s,side,disc,x,y,fill,second,dark,orange,slot2_luma,slot2_dark,us\n")
    private var logLines = 0
    private val events = StringBuilder("t_ns,t_s,event,side,x,y,dx,dy,dt_ms,detail\n")

    fun csv(): String = synchronized(log) { log.toString() }
    fun eventsCsv(): String = synchronized(events) { events.toString() }
    fun avgUs(): Int = if (scans > 0) (usTotal / scans).toInt() else 0
    fun maxUs(): Int = usMax

    fun onFrame(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, tNs: Long, blockFx: Float, blockFy: Float) {
        if (w <= h || pixelStride != 4) return
        if (++frameNo % 2 != 0) return
        val t0 = System.nanoTime()
        ensureGrid(w, h)
        side = readSide(buf, w, h, rowStride, blockFx, blockFy)
        Bench.shotSide = side
        val found = findDisc(buf, w, h, rowStride)
        readSlot2(buf, w, h, rowStride, blockFx, blockFy)
        scans++
        if (side == SIDE_DEF) defScans++ else if (side == SIDE_OFF) offScans++
        if (found) { discScans++; onDisc(tNs) }
        colourFrame(buf, w, h, rowStride, tNs, found)
        val us = ((System.nanoTime() - t0) / 1000).toInt()
        usTotal += us; if (us > usMax) usMax = us
        if (logLines < MAX_LOG_LINES) synchronized(log) {
            logLines++
            log.append(tNs).append(',').append(String.format(Locale.ROOT, "%.3f,%s,%d,%.4f,%.4f,%.2f,%.2f,%.3f,%.3f,%.1f,%.3f,%d\n",
                (tNs - startedNs) / 1e9, sideName(side), if (found) 1 else 0, discX, discY, fill, second, sideDark, sideOrange, slot2Luma, slot2Dark, us))
        }
    }

    // ------------------------------------------------------------------ side

    private fun readSide(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, fx: Float, fy: Float): Int {
        val cx = (fx * w).toInt(); val cy = (fy * h).toInt()
        val r = (0.045f * w).toInt()
        val st = maxOf(2, (0.0028f * w).toInt())
        var n = 0; var dark = 0; var orange = 0
        var y = cy - r
        while (y <= cy + r) {
            if (y in 0 until h) {
                var x = cx - r
                while (x <= cx + r) {
                    val dx = x - cx; val dy = y - cy
                    if (x in 0 until w && dx * dx + dy * dy <= r * r) {
                        val o = y * rowStride + x * 4
                        val rr = buf.get(o).toInt() and 0xFF; val gg = buf.get(o + 1).toInt() and 0xFF; val bb = buf.get(o + 2).toInt() and 0xFF
                        n++
                        if (rr < 70 && gg < 70 && bb < 90) dark++
                        if (rr > 190 && gg in 80..190 && bb < 90) orange++
                    }
                    x += st
                }
            }
            y += st
        }
        if (n == 0) return SIDE_UNKNOWN
        val df = dark.toFloat() / n; val of = orange.toFloat() / n
        sideDark = df; sideOrange = of
        return when {
            df > 0.15f && of < 0.005f -> SIDE_DEF
            of > 0.01f -> SIDE_OFF
            else -> SIDE_UNKNOWN
        }
    }

    private var sideDark = 0f; private var sideOrange = 0f
    private var slot2Luma = -1f; private var slot2Dark = -1f

    /**
     * The "수비" button slot, which turns into "박스아웃" when an opponent's shot leaves the hand (3/3 clips) — the
     * release label. Position read off vc1022 screens: block slot + (-0.09 W, -0.21 H). Logged only.
     */
    private fun readSlot2(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, fx: Float, fy: Float) {
        val cx = ((fx - 0.09f) * w).toInt(); val cy = ((fy - 0.21f) * h).toInt()
        val r = (0.03f * w).toInt()
        val st = maxOf(2, (0.0028f * w).toInt())
        var n = 0; var sum = 0; var dark = 0
        var y = cy - r
        while (y <= cy + r) {
            if (y in 0 until h) {
                var x = cx - r
                while (x <= cx + r) {
                    if (x in 0 until w) {
                        val o = y * rowStride + x * 4
                        val rr = buf.get(o).toInt() and 0xFF; val gg = buf.get(o + 1).toInt() and 0xFF; val bb = buf.get(o + 2).toInt() and 0xFF
                        n++; sum += (rr * 77 + gg * 151 + bb * 28) shr 8
                        if (rr < 70 && gg < 70 && bb < 90) dark++
                    }
                    x += st
                }
            }
            y += st
        }
        slot2Luma = if (n > 0) sum.toFloat() / n else -1f
        slot2Dark = if (n > 0) dark.toFloat() / n else -1f
    }

    // ------------------------------------------------------------------ colour crops around the ball

    private class Crop(var tNs: Long = 0, var x0: Int = 0, var y0: Int = 0, var disc: Boolean = false, var px: ByteArray = ByteArray(0))
    private class ColourReq(val tag: String, val tNs: Long, val beforeNs: Long, val afterNs: Long)
    private val cropN = 96                      // ~3.2 s at 30 Hz
    private val crops = Array(cropN) { Crop() }
    private var cropPos = 0; private var cropFilled = 0
    private var cw = 0; private var ch = 0
    private var lastCx = -1; private var lastCy = -1; private var lastSeenNs = 0L
    private val colourReqs = ConcurrentLinkedQueue<ColourReq>()
    private val colourJobs = ArrayList<ColourReq>()
    @Volatile var colourClips = 0; private set

    /** Any thread: save the colour crops from [beforeS] before now to [afterS] after now. */
    fun requestColour(tag: String, tNs: Long, beforeS: Double, afterS: Double) {
        colourReqs.add(ColourReq(tag, tNs, (beforeS * 1e9).toLong(), (afterS * 1e9).toLong()))
    }

    /**
     * Every 2nd scan (~30 Hz): copy a full-resolution colour window around the ball (the disc sits in the top quarter,
     * the carrier's body below it) into a ring. Window follows the last disc position for 1 s, else the screen centre.
     */
    private fun colourFrame(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, tNs: Long, found: Boolean) {
        if (cw == 0 || crops[0].px.size != cw * ch * 4) {
            cw = (0.16f * w).toInt() and 1.inv(); ch = (0.34f * h).toInt() and 1.inv()
            for (c in crops) c.px = ByteArray(cw * ch * 4)
            cropPos = 0; cropFilled = 0
        }
        if (found) { lastCx = (discX * w).toInt(); lastCy = (discY * h).toInt(); lastSeenNs = tNs }
        if (scans % 2 == 0) {
            val useDisc = lastCx >= 0 && tNs - lastSeenNs < 1_000_000_000L
            val cx = if (useDisc) lastCx else w / 2
            val cy = if (useDisc) lastCy else h / 2
            val x0 = (cx - cw / 2).coerceIn(0, w - cw)
            val y0 = (cy - ch / 4).coerceIn(0, h - ch)
            val c = crops[cropPos]
            c.tNs = tNs; c.x0 = x0; c.y0 = y0; c.disc = found
            val d = buf.duplicate()
            val rowBytes = cw * 4
            for (r in 0 until ch) {
                d.position((y0 + r) * rowStride + x0 * 4)
                d.get(c.px, r * rowBytes, rowBytes)
            }
            cropPos = (cropPos + 1) % cropN
            if (cropFilled < cropN) cropFilled++
        }
        while (true) {
            val r = colourReqs.poll() ?: break
            if (colourClips + colourJobs.size < MAX_COLOUR_CLIPS) colourJobs.add(r)
        }
        val it = colourJobs.iterator()
        while (it.hasNext()) {
            val j = it.next()
            if (tNs >= j.tNs + j.afterNs) { it.remove(); saveColour(j) }
        }
    }

    private fun saveColour(j: ColourReq) {
        colourClips++
        val n = colourClips
        val picked = ArrayList<Crop>()
        for (i in 0 until cropFilled) {
            val c = crops[(cropPos - cropFilled + i + cropN) % cropN]
            if (c.tNs >= j.tNs - j.beforeNs && c.tNs <= j.tNs + j.afterNs)
                picked.add(Crop(c.tNs, c.x0, c.y0, c.disc, c.px.copyOf()))
        }
        val w = cw; val h = ch
        val at = (j.tNs - startedNs) / 1e9
        io.execute {
            try {
                val d = File(colourDir, String.format(Locale.ROOT, "colour_%02d_%s_%06.1fs", n, j.tag, at)).apply { mkdirs() }
                val ints = IntArray(w * h)
                val index = StringBuilder("file,t_rel_ms,x0,y0,disc_found\n")
                for ((i, c) in picked.withIndex()) {
                    for (q in ints.indices) {
                        val o = q * 4
                        ints[q] = (0xFF shl 24) or ((c.px[o].toInt() and 0xFF) shl 16) or ((c.px[o + 1].toInt() and 0xFF) shl 8) or (c.px[o + 2].toInt() and 0xFF)
                    }
                    val bmp = Bitmap.createBitmap(ints, w, h, Bitmap.Config.ARGB_8888)
                    val rel = (c.tNs - j.tNs) / 1e6
                    val name = String.format(Locale.ROOT, "c%03d_%+07.1fms.jpg", i, rel)
                    FileOutputStream(File(d, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    bmp.recycle()
                    index.append(name).append(',').append(String.format(Locale.ROOT, "%.1f", rel)).append(',')
                        .append(c.x0).append(',').append(c.y0).append(',').append(if (c.disc) 1 else 0).append('\n')
                }
                File(d, "crops.csv").writeText(index.toString())
            } catch (t: Throwable) {
                Log.e("DunkBlockShot", "colour clip write failed", t)
            }
        }
    }

    // ------------------------------------------------------------------ disc

    private var discX = -1f; private var discY = -1f; private var fill = 0f; private var second = 0f

    private fun ensureGrid(w: Int, h: Int) {
        val key = (w.toLong() shl 32) or h.toLong()
        if (key == mapKey) return
        mapKey = key
        aspect = w.toFloat() / h
        step = maxOf(2, w / 390)
        gw = w / step; gh = h / step
        k = maxOf(3, Math.round(0.0104f * w / step))
        sat = IntArray((gw + 1) * (gh + 1))
        hCount = 0
    }

    private fun isHud(x: Int, y: Int, w: Int, h: Int): Boolean =
        (x < 0.30f * w && y < 0.22f * h) ||   // scoreboard
        (x > 0.62f * w && y < 0.14f * h) ||   // top-right icons and the app's own bubble
        (x > 0.74f * w && y > 0.40f * h) ||   // action buttons
        (x < 0.32f * w && y > 0.50f * h)      // joystick

    private fun findDisc(buf: ByteBuffer, w: Int, h: Int, rowStride: Int): Boolean {
        val s = step; val stride = gw + 1
        val y0 = (0.06f * gh).toInt(); val y1 = (0.88f * gh).toInt()
        for (gy in 0 until gh) {
            var row = 0
            val py = gy * s
            val active = gy in y0 until y1
            for (gx in 0 until gw) {
                if (active) {
                    val px = gx * s
                    if (!isHud(px, py, w, h)) {
                        val o = py * rowStride + px * 4
                        val r = buf.get(o).toInt() and 0xFF
                        if (r > 160) {
                            val g = buf.get(o + 1).toInt() and 0xFF
                            val b = buf.get(o + 2).toInt() and 0xFF
                            if (g < 25 && b in 30..90 && r - b > 95) row++
                        }
                    }
                }
                sat[(gy + 1) * stride + gx + 1] = sat[gy * stride + gx + 1] + row
            }
        }
        var best = 0; var bx = 0; var by = 0; var sec = 0
        val far = 2 * k
        for (gy in 0..gh - k) for (gx in 0..gw - k) {
            val c = sat[(gy + k) * stride + gx + k] - sat[gy * stride + gx + k] - sat[(gy + k) * stride + gx] + sat[gy * stride + gx]
            if (c > best) {
                if (Math.abs(gx - bx) > far || Math.abs(gy - by) > far) sec = best
                best = c; bx = gx; by = gy
            } else if (c > sec && (Math.abs(gx - bx) > far || Math.abs(gy - by) > far)) sec = c
        }
        val kk = (k * k).toFloat()
        fill = best / kk; second = sec / kk
        val ok = fill >= FOUND_FILL && second <= 0.6f * fill
        if (ok) {
            discX = ((bx + k / 2f) * s) / w
            discY = ((by + k / 2f) * s) / h
        } else { discX = -1f; discY = -1f }
        return ok
    }

    private fun onDisc(tNs: Long) {
        // Compare with the oldest point in the last 160 ms: a carried ball moves with a running player
        // (well under 0.5 screen-heights per second); a shot or pass leaves much faster.
        var refI = -1
        for (i in 1..minOf(hCount, histN - 1)) {
            val idx = (hPos - i + histN) % histN
            val dt = tNs - hT[idx]
            if (dt > 160_000_000L) break
            if (dt >= 60_000_000L) refI = idx
        }
        if (refI >= 0 && tNs - lastFastNs > 700_000_000L) {
            val dtS = (tNs - hT[refI]) / 1e9f
            val dx = discX - hX[refI]; val dy = discY - hY[refI]
            val speed = Math.sqrt((dx * dx * aspect * aspect + dy * dy).toDouble()).toFloat() / dtS   // screen-heights per second
            if (speed > FAST_SPEED) {
                lastFastNs = tNs
                fastEvents++
                val upward = dy < 0 && -dy > 0.5f * Math.abs(dx) * aspect
                val shotLike = side == SIDE_DEF && upward
                if (shotLike) {
                    shotCandidates++
                    if (shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("shot", 1.6, 0.6) }
                    snapNow = true
                }
                synchronized(events) {
                    events.append(tNs).append(',').append(String.format(Locale.ROOT, "%.3f,disc_fast,%s,%.4f,%.4f,%.4f,%.4f,%.0f,%s\n",
                        (tNs - startedNs) / 1e9, sideName(side), discX, discY, dx, dy, dtS * 1000, if (shotLike) "shot_candidate" else if (upward) "upward" else "sideways"))
                }
            }
        }
        hT[hPos] = tNs; hX[hPos] = discX; hY[hPos] = discY
        hPos = (hPos + 1) % histN
        if (hCount < histN) hCount++
    }

    fun summary(): String = String.format(Locale.ROOT,
        "검사 %d회(평균 %dus, 최대 %dus) · 수비 화면 %.0f%% · 공격 화면 %.0f%% · 빨간 공 표시 보임 %.0f%% · 빠른 공 이동 %d회(수비 중 위로 = 슛 후보 %d회) · 컬러 확대 클립 %d개",
        scans, avgUs(), usMax, pct(defScans), pct(offScans), pct(discScans), fastEvents, shotCandidates, colourClips)

    private fun pct(n: Int) = if (scans > 0) 100.0 * n / scans else 0.0

    companion object {
        const val SIDE_UNKNOWN = 0
        const val SIDE_DEF = 1
        const val SIDE_OFF = 2
        const val FOUND_FILL = 0.45f
        /** screen-heights per second; set from data later. */
        const val FAST_SPEED = 0.9f
        const val MAX_SHOT_CLIPS = 12
        const val MAX_COLOUR_CLIPS = 20
        const val MAX_LOG_LINES = 120_000
        fun sideName(s: Int) = when (s) { SIDE_DEF -> "def"; SIDE_OFF -> "off"; else -> "unk" }
    }
}
