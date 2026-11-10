package dev.dunkblock.bench

import java.nio.ByteBuffer
import java.util.Locale

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
 * Everything per frame goes to shot.csv for offline design of the real trigger.
 */
class ShotWatcher(private val startedNs: Long) {
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

    private val log = StringBuilder(1 shl 16).append("t_ns,t_s,side,disc,x,y,fill,second,us\n")
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
        val us = ((System.nanoTime() - t0) / 1000).toInt()
        scans++; usTotal += us; if (us > usMax) usMax = us
        if (side == SIDE_DEF) defScans++ else if (side == SIDE_OFF) offScans++
        if (found) { discScans++; onDisc(tNs) }
        if (logLines < MAX_LOG_LINES) synchronized(log) {
            logLines++
            log.append(tNs).append(',').append(String.format(Locale.ROOT, "%.3f,%s,%d,%.4f,%.4f,%.2f,%.2f,%d\n",
                (tNs - startedNs) / 1e9, sideName(side), if (found) 1 else 0, discX, discY, fill, second, us))
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
        return when {
            df > 0.15f && of < 0.005f -> SIDE_DEF
            of > 0.01f -> SIDE_OFF
            else -> SIDE_UNKNOWN
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
        "검사 %d회(평균 %dus, 최대 %dus) · 수비 화면 %.0f%% · 공격 화면 %.0f%% · 빨간 공 표시 보임 %.0f%% · 빠른 공 이동 %d회(수비 중 위로 = 슛 후보 %d회)",
        scans, avgUs(), usMax, pct(defScans), pct(offScans), pct(discScans), fastEvents, shotCandidates)

    private fun pct(n: Int) = if (scans > 0) 100.0 * n / scans else 0.0

    companion object {
        const val SIDE_UNKNOWN = 0
        const val SIDE_DEF = 1
        const val SIDE_OFF = 2
        const val FOUND_FILL = 0.45f
        /** screen-heights per second; set from data later. */
        const val FAST_SPEED = 0.9f
        const val MAX_SHOT_CLIPS = 12
        const val MAX_LOG_LINES = 120_000
        fun sideName(s: Int) = when (s) { SIDE_DEF -> "def"; SIDE_OFF -> "off"; else -> "unk" }
    }
}
