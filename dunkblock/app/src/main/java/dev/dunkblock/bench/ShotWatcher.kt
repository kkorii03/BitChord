package dev.dunkblock.bench

import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
 *   grid; accepted only when no second, similar blob exists (jersey outlines and court paint give several weak ones),
 *   except the ball handler's own red foot ring stacked 0.12-0.45 H below the disc (vc1031: then the upper blob is the
 *   disc). It is NOT my defender's ring (11-10 review): the pair says nothing about how close my player is.
 *   Prototype on the same 60 frames: 9 visible discs found (fill 0.47..0.94), 0 false accepts.
 *  EVENTS: "disc_fast" = the disc moved faster than a carried ball can (a shot, pass or steal). Upward ones on
 *   defence are shot candidates and get a clip + one colour screen, so the next bundle can tell what precedes them.
 *  COLOUR: a 3.2 s ring of full-resolution colour windows around the ball (~30 Hz) is saved to colour/ for shot
 *   candidates, 기록 marks and ultimates (not for block-slot changes).
 * Everything per frame goes to shot.csv for offline design of the real trigger.
 */
class ShotWatcher(private val cfg: Bench.Config, private val startedNs: Long, private val io: ExecutorService, private val colourDir: File) {
    private val main = Handler(Looper.getMainLooper())
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

    private val log = StringBuilder(1 shl 16).append("t_ns,t_s,side,disc,x,y,fill,second,dark,orange,slot2_luma,slot2_dark,us,ring_y,sec_x,sec_y,col_x,col_y,col_fill,top_dy,yc,yc_n\n")
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
        curBuf = buf; curW = w; curH = h; curStride = rowStride
        side = readSide(buf, w, h, rowStride, blockFx, blockFy)
        Bench.shotSide = side
        val found = findDisc(buf, w, h, rowStride)
        readSlot2(buf, w, h, rowStride, blockFx, blockFy)
        scans++
        if (side == SIDE_DEF) { defScans++; lastDefNs = tNs } else if (side == SIDE_OFF) offScans++
        colY = -1f; colX = -1f; colFill = 0f; topDy = -1f; ycY = -1f; ycN = 0
        if (found) {
            discScans++
            findColRing(buf, w, h, rowStride)
            findTop(buf, w, h, rowStride)
            findCentre(buf, w, h, rowStride)
            armsUp(tNs)
            // One colour screen of a marker on a wing every 4 s: shows what lies under it there and where my player is.
            if (side == SIDE_DEF && (discX < 0.30f || discX > 0.74f) && wingSnaps < 40 && tNs - lastWingSnapNs > 4_000_000_000L) {
                lastWingSnapNs = tNs; wingSnaps++; snapNow = true
            }
            onDisc(tNs)
            armsUpEvent(tNs)
            crouch(tNs)
        }
        checkPending(tNs, found)
        checkUpPending(tNs, found)
        colourFrame(buf, w, h, rowStride, tNs, found)
        val us = ((System.nanoTime() - t0) / 1000).toInt()
        usTotal += us; if (us > usMax) usMax = us
        if (logLines < MAX_LOG_LINES) synchronized(log) {
            logLines++
            log.append(tNs).append(',').append(String.format(Locale.ROOT, "%.3f,%s,%d,%.4f,%.4f,%.2f,%.2f,%.3f,%.3f,%.1f,%.3f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%.4f,%.5f,%d\n",
                (tNs - startedNs) / 1e9, sideName(side), if (found) 1 else 0, discX, discY, fill, second, sideDark, sideOrange, slot2Luma, slot2Dark, us, ringY, secX, secY,
                colX, colY, colFill, topDy, ycY, ycN))
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
    private val colourAccepted = HashMap<String, Int>()
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
            val g = PressClipper.clipGroup(r.tag)
            val used = colourAccepted[g] ?: 0
            val cap = if (g == "other") MAX_COLOUR_CLIPS else 3
            if (colourClips + colourJobs.size < MAX_COLOUR_CLIPS && used < cap) { colourAccepted[g] = used + 1; colourJobs.add(r) }
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
        var best = 0; var bx = 0; var by = 0
        for (gy in 0..gh - k) for (gx in 0..gw - k) {
            val c = sat[(gy + k) * stride + gx + k] - sat[gy * stride + gx + k] - sat[(gy + k) * stride + gx] + sat[gy * stride + gx]
            if (c > best) { best = c; bx = gx; by = gy }
        }
        // Second pass: the strongest window away from the best one, with its position.
        var sec = 0; var sx = 0; var sy = 0
        val far = 2 * k
        for (gy in 0..gh - k) for (gx in 0..gw - k) {
            if (Math.abs(gx - bx) <= far && Math.abs(gy - by) <= far) continue
            val c = sat[(gy + k) * stride + gx + k] - sat[gy * stride + gx + k] - sat[(gy + k) * stride + gx] + sat[gy * stride + gx]
            if (c > sec) { sec = c; sx = gx; sy = gy }
        }
        val kk = (k * k).toFloat()
        fill = best / kk; second = sec / kk
        val bX = ((bx + k / 2f) * s) / w; val bY = ((by + k / 2f) * s) / h
        secX = ((sx + k / 2f) * s) / w; secY = ((sy + k / 2f) * s) / h
        ringY = -1f
        var ok = fill >= FOUND_FILL && second <= 0.6f * fill
        discX = bX; discY = bY
        // vc1031: the defender's red foot ring (y ~0.80) is the second blob in almost every frame, 0.25-0.35 H below the
        // shooter's overhead disc (y ~0.50) and within 0.04 W of it. The old "no second blob" rule threw those frames
        // away (disc seen 16%) and lost the disc during the jump. Stacked like that: the upper blob is the disc.
        if (!ok && fill >= FOUND_FILL && Math.abs(bX - secX) <= 0.08f && Math.abs(bY - secY) in 0.12f..0.45f) {
            if (bY < secY) { ok = true; ringY = secY; ringX = secX }
            else if (second >= FOUND_FILL) { ok = true; discX = secX; discY = secY; ringY = bY; ringX = bX }
        }
        if (ringY >= 0f) lastRingNs = System.nanoTime()
        // The ring alone (disc hidden for a frame) must not pass for the disc: that jump of 0.3 H looked like a release.
        else if (ok && System.nanoTime() - lastRingNs < 1_500_000_000L && Math.abs(discY - ringYLast) < 0.06f && Math.abs(discX - ringX) < 0.08f) ok = false
        if (ringY >= 0f) ringYLast = ringY
        if (!ok) { discX = -1f; discY = -1f }
        return ok
    }

    private var secX = -1f; private var secY = -1f; private var ringY = -1f
    private var ringX = -1f; private var ringYLast = -1f; private var lastRingNs = 0L

    private var colX = -1f; private var colY = -1f; private var colFill = 0f
    private var colSat = IntArray(0)
    private var wingSnaps = 0; private var lastWingSnapNs = 0L

    /**
     * Shadow measurement (vc1051, logged only, col_x/col_y/col_fill in shot.csv): the strongest red window straight under
     * the disc (x +-0.05 W, 0.12-0.45 H below), read WITHOUT the HUD mask. The normal ring is the second-strongest blob
     * of the masked screen, so it is missing on 60 % of centre frames and on 98 % of wing frames (ring under the buttons
     * or the joystick), which is where the three marked wing shots of the vc1044 match were. The next bundle tells
     * whether this column finds the foot ring there or the HUD's own red; nothing acts on it yet.
     */
    private fun findColRing(buf: ByteBuffer, w: Int, h: Int, rowStride: Int) {
        val s = step
        val gx0 = ((discX - 0.05f) * w / s).toInt().coerceIn(0, gw - 1); val gx1 = ((discX + 0.05f) * w / s).toInt().coerceIn(0, gw - 1)
        val gy0 = ((discY + 0.12f) * h / s).toInt().coerceIn(0, gh - 1); val gy1 = minOf(((discY + 0.45f) * h / s).toInt(), (0.90f * gh).toInt()).coerceIn(0, gh - 1)
        val nx = gx1 - gx0 + 1; val ny = gy1 - gy0 + 1
        if (nx < k || ny < k) return
        val stride = nx + 1
        if (colSat.size < stride * (ny + 1)) colSat = IntArray(stride * (ny + 1))
        for (i in 0 until stride) colSat[i] = 0
        for (j in 0 until ny) {
            var row = 0
            val py = (gy0 + j) * s
            colSat[(j + 1) * stride] = 0
            for (i in 0 until nx) {
                val o = py * rowStride + (gx0 + i) * s * 4
                val r = buf.get(o).toInt() and 0xFF
                if (r > 160) {
                    val g = buf.get(o + 1).toInt() and 0xFF
                    val b = buf.get(o + 2).toInt() and 0xFF
                    if (g < 25 && b in 30..90 && r - b > 95) row++
                }
                colSat[(j + 1) * stride + i + 1] = colSat[j * stride + i + 1] + row
            }
        }
        var best = 0; var bi = 0; var bj = 0
        for (j in 0..ny - k) for (i in 0..nx - k) {
            val c = colSat[(j + k) * stride + i + k] - colSat[j * stride + i + k] - colSat[(j + k) * stride + i] + colSat[j * stride + i]
            if (c > best) { best = c; bi = i; bj = j }
        }
        if (best == 0) return
        colFill = best / (k * k).toFloat()
        colX = ((gx0 + bi + k / 2f) * s) / w; colY = ((gy0 + bj + k / 2f) * s) / h
    }

    private var topDy = -1f
    private var upRun = 0; private var upSinceNs = 0L; private var lastLowNs = 0L; private var lastUpEventNs = 0L
    private var upSnaps = 0; private var upClips = 0

    /**
     * Shadow measurement (vc1055, logged only: top_dy in shot.csv, "arms_up" events, top=/topage= in the trigger detail).
     * top_dy = how far below the marker's red the first red-outline row lies (x +-26 px at 720p), as a share of H; -1 = none
     * within 90 px. The game outlines the ball handler in red, so this is the top of his silhouette: head when standing,
     * hands and ball when he lifts them to shoot. Colour clips of four sessions (tools/analysis/armsup.py): standing
     * 27-35 px (55-60 px close to the camera), lifted 3-14 px. In 15 of 17 practice trigger clips it dropped 25-75 ms BEFORE
     * the gap trigger with no drop earlier in the clip (the other 2, vc1035 60.4 / 65.1 s: 0.36-0.58 s before, the app
     * triggered late there); in match clips it marked the real jump shot at 175.8 s (vc1038)
     * that stood in the joystick corner where the foot ring is masked, 0.33 s after a dribble trigger the app rightly
     * dropped. It needs no foot ring, no history and no ball colour (match balls have other skins), and it is relative to
     * the marker, so camera moves cancel. Unknown until a bundle with this column: players far from the camera (smaller
     * on screen), pump fakes and overhead passes. Nothing acts on it unless the experimental option is on ([checkUpPending]).
     */
    private fun findTop(buf: ByteBuffer, w: Int, h: Int, rowStride: Int) {
        val u = h / 720f
        val cx = (discX * w).toInt(); val cy = (discY * h).toInt()
        var xa = maxOf(0, cx - (10 * u).toInt()); var xb = minOf(w - 1, cx + (10 * u).toInt())
        var bot = cy
        var y = cy
        val yEnd = minOf(h - 1, cy + (30 * u).toInt())
        while (y <= yEnd) {
            var n = 0
            var x = xa
            while (x <= xb) {
                val o = y * rowStride + x * 4
                val r = buf.get(o).toInt() and 0xFF
                if (r > 160) {
                    val g = buf.get(o + 1).toInt() and 0xFF
                    val b = buf.get(o + 2).toInt() and 0xFF
                    if (g < 25 && b in 30..90 && r - b > 95) n++
                }
                x++
            }
            if (n >= 3) bot = y else if (y > bot + 2) break
            y++
        }
        markBot = bot
        xa = maxOf(0, cx - (26 * u).toInt()); xb = minOf(w - 1, cx + (26 * u).toInt())
        y = bot + (4 * u).toInt()
        val yLast = minOf(h - 1, bot + (90 * u).toInt())
        while (y <= yLast) {
            var n = 0
            var x = xa
            while (x <= xb) {
                val o = y * rowStride + x * 4
                val r = buf.get(o).toInt() and 0xFF
                if (r >= 140) {
                    val g = buf.get(o + 1).toInt() and 0xFF
                    val b = buf.get(o + 2).toInt() and 0xFF
                    // The opponent's outline red. My giraffe's brown patches are G >= 0.30 R and stay out.
                    if (g * 100 <= r * 28 && b * 100 <= r * 55 && r - g >= 90) n++
                }
                x++
            }
            if (n >= 3) { topDy = (y - bot) / h.toFloat(); return }
            y++
        }
    }

    private var markBot = -1
    private var ycY = -1f; private var ycN = 0
    private val cT = LongArray(CR_N); private val cY = FloatArray(CR_N); private val cNn = IntArray(CR_N)
    private var cPos = 0; private var cCount = 0; private var cRunNs = 0L
    private var lastCrouchNs = 0L; private var crouchSeq = 0; private var crouchClips = 0
    @Volatile var crouchEvents = 0; private set
    @Volatile var crouchTriggers = 0; private set

    /**
     * Precise marker height (vc1060; yc / yc_n in shot.csv): centroid of the marker's disc-red pixels in the 45 rows up
     * from its bottom row ([findTop]) and +-40 px around the grid centre. The grid centre ([discY]) is the best 16 px
     * window on a 4 px grid, so it moves in 4 px steps and floats inside the 26-36 px marker (idle noise +-1..3 steps);
     * this one stood within ~0.2 px on a standing shooter (tools/analysis/crouch.py on the colour clips, 360-600 red
     * pixels per marker). -1 when fewer than 150 red pixels (marker covered or cut).
     */
    private fun findCentre(buf: ByteBuffer, w: Int, h: Int, rowStride: Int) {
        val u = h / 720f
        val bot = markBot
        val cx = (discX * w).toInt()
        val ya = maxOf(0, bot - (44 * u).toInt()); val xa = maxOf(0, cx - (40 * u).toInt()); val xb = minOf(w - 1, cx + (40 * u).toInt())
        var n = 0; var sy = 0L
        var y = ya
        while (y <= bot) {
            var o = y * rowStride + xa * 4
            var x = xa
            while (x <= xb) {
                val r = buf.get(o).toInt() and 0xFF
                if (r > 160) {
                    val g = buf.get(o + 1).toInt() and 0xFF
                    val b = buf.get(o + 2).toInt() and 0xFF
                    if (g < 25 && b in 30..90 && r - b > 95) { n++; sy += y }
                }
                x++; o += 4
            }
            y++
        }
        ycN = n
        if (n >= (150 * u * u).toInt()) ycY = (sy.toDouble() / n / h).toFloat()
    }

    /**
     * Crouch cue (vc1060). Quick-release shooters let the ball go before a jump started at the gap trigger can reach it:
     * LaMelo Ball in practice (vc1056 bundle, 144-208 s): 10 shots, 8 presses, 0 blocks, all '강한 컨테스트'; the ball left
     * his hands ~30-40 ms after the trigger and my player's arms are up only 200-240 ms after a press (Curry, same bundle:
     * release 310-330 ms after the trigger, 6 '블록'). The only earlier sign is the knee bend: his marker sinks 13-18 px
     * (720p) from about -450 to -220 ms before the trigger, after standing still. The grid y cannot show that, the
     * precise centre can. Rule, replayed by tools/analysis/crouch.py: centre track unbroken 0.7 s, y range <= 9 px over
     * the 200-700 ms before, now >= 7 px below the lowest point of the last 170 ms and not rising, red pixel count
     * within 0.8-1.25 of the calm mean (marker whole), outline top at standing height. On a sub-pixel track of the
     * LaMelo clips it fired 217-417 ms (median 350) before the gap trigger on every shot the clips cover, nowhere else.
     * Every hit is logged as "crouch". It presses only with [Bench.Config.crouchPress] (off by default), because it is
     * WRONG for ordinary shooters: on the older practice clips it also fires 220-430 ms before their gap trigger, and
     * their release comes 300-500 ms after that trigger (the press would be '타이밍 빠름'), and it fired on the passer
     * 0.8 s before two of Curry's catch-and-shoot shots (the jump would be wasted). Match rate unknown.
     * The press is held CROUCH_DELAYS[n % 3] ms (cd= in the detail) so one practice session shows which wait the game
     * accepts; nothing about the tolerance is measured yet.
     */
    private fun crouch(tNs: Long) {
        if (ycY < 0f) return
        val li = (cPos - 1 + CR_N) % CR_N
        // The track starts over with the marker's run, after a pause or a step (the run's own limits).
        if (cCount == 0 || cRunNs < runNs || tNs - cT[li] > 100_000_000L || Math.abs(ycY - cY[li]) > 0.06f) { cCount = 0; cRunNs = tNs }
        val prevY = if (cCount > 0) cY[li] else ycY
        cT[cPos] = tNs; cY[cPos] = ycY; cNn[cPos] = ycN
        cPos = (cPos + 1) % CR_N
        if (cCount < CR_N) cCount++
        if (tNs - cRunNs < 700_000_000L || tNs - lastCrouchNs < 1_500_000_000L) return
        if (side == SIDE_OFF || tNs - lastDefNs > 1_000_000_000L) return
        var lo = ycY; var loNs = tNs
        var cMin = Float.MAX_VALUE; var cMax = -1f; var cn = 0; var nSum = 0L; var cOld = tNs; var cNew = 0L
        for (i in 0 until cCount) {
            val idx = (cPos - 1 - i + 2 * CR_N) % CR_N
            val age = tNs - cT[idx]
            if (age > 700_000_000L) break
            if (age <= CROUCH_WIN_NS && cY[idx] < lo) { lo = cY[idx]; loNs = cT[idx] }
            if (age >= 200_000_000L) {
                if (cn == 0) cNew = cT[idx]
                cn++; cOld = cT[idx]; nSum += cNn[idx]
                cMin = minOf(cMin, cY[idx]); cMax = maxOf(cMax, cY[idx])
            }
        }
        if (cn < 10 || cNew - cOld < 400_000_000L || cMax - cMin > CROUCH_CALM) return
        val sink = ycY - lo
        if (sink < CROUCH_SINK || ycY < prevY) return
        val nMean = nSum.toFloat() / cn
        if (ycN < 0.8f * nMean || ycN > 1.25f * nMean) return
        if (lastStandNs != tNs) return      // outline top not at standing height: hands already up, or no outline read
        lastCrouchNs = tNs; crouchEvents++
        val ringKnown = tNs - gapNs < 150_000_000L && gapRing - discY in 0.12f..0.6f
        val d = String.format(Locale.ROOT, "crouch sink=%.4f in=%d calm=%.4f n=%d/%.0f top=%.4f run=%d gap=%.3f xspan=%.3f",
            sink, (tNs - loNs) / 1_000_000L, cMax - cMin, ycN, nMean, topDy, (tNs - runNs) / 1_000_000L,
            if (ringKnown) gapRing - discY else -1f, runXMax - runXMin)
        event(tNs, "crouch", 0f, 0f, 0f, d)
        if (crouchClips < 10) { crouchClips++; requestColour("crouch_def", tNs, 0.8, 0.8) }
        if (!cfg.shotBlock || !cfg.crouchPress) return
        val why = when {
            pendNs != 0L -> "gap_pending"
            tNs - lastPressNs < 1_500_000_000L -> "pressed"
            tNs - lastShotFireNs < 1_500_000_000L -> "lockout"
            else -> ""
        }
        if (why.isNotEmpty()) { event(tNs, "crouch_dropped", 0f, 0f, 0f, why); return }
        val live = cfg.autoBlockLive
        val waitMs = CROUCH_DELAYS[crouchSeq++ % CROUCH_DELAYS.size]
        lastShotFireNs = tNs
        shotTriggers++; crouchTriggers++
        synchronized(pendingTriggers) { pendingTriggers.add(tNs) }
        // The verdict shows 0.4-1.4 s after the release, and this press is ~0.35 s earlier than a gap press: 2.3 s after.
        if (shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_crouch", 0.6, 2.3 + cfg.shotDelayMs / 1000.0) }
        var meDist = findMe(discX, if (ringKnown) gapRing else minOf(0.95f, discY + 0.3f))
        if (!ringKnown && meDist >= 0f) meDist = Math.abs(meX - discX)
        val d2 = String.format(Locale.ROOT, "%s cd=%d sens=%d delay=%d me=%.3f/%.3f d=%.3f", d, waitMs, cfg.shotSens, cfg.shotDelayMs, meX, meY, meDist)
        event(tNs, "shot_trigger", 0f, 0f, 0f, d2)
        snapNow = true
        if (meDist > ME_FAR) { shotFar++; reject(tNs, "rejected_far", d2); return }
        if (live) main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) }
        press(tNs, live, d2, -waitMs)
    }

    private var lastStandNs = 0L; private var upPendNs = 0L; private var lastPressNs = 0L
    @Volatile var upEvents = 0; private set
    @Volatile var upTriggers = 0; private set

    /** Per scan, before [onDisc] (the trigger detail reads it): how long the outline top has been up at the marker. */
    private fun armsUp(tNs: Long) {
        if (topDy < 0f || topDy > UP_DY + 0.011f) lastLowNs = tNs
        if (topDy > UP_DY + 0.011f) lastStandNs = tNs
        if (topDy in 0f..UP_DY) { if (upRun == 0) upSinceNs = tNs; upRun++ } else upRun = 0
    }

    /**
     * After [onDisc]. Logs "arms_up" when the outline top comes up to the marker (3 scans in a row, not up within the
     * 0.3 s before) and keeps a few colour pictures of it. same=1 when this same player was seen standing (outline top
     * 22-90 px under the marker) within the 0.3 s before: a marker that lands on hands already up (a pass being caught),
     * or sits on a defender's foot ring (vc1038 183.4 / 417.4 s clips), has no such reading. Replay of that condition on
     * the colour clips (armsup.py, '*'): all 15 practice clips keep their arms-up, the two foot-ring stretches lose it.
     * With [Bench.Config.upPress] (experimental, off by default) a same=1 event becomes a press, see [checkUpPending].
     */
    private fun armsUpEvent(tNs: Long) {
        if (upRun == 3 && side == SIDE_DEF && upSinceNs - lastLowNs < 300_000_000L && tNs - lastUpEventNs > 1_000_000_000L) {
            lastUpEventNs = tNs; upEvents++
            val same = lastStandNs >= runNs && upSinceNs - lastStandNs < 300_000_000L
            event(tNs, "arms_up", 0f, 0f, 0f, String.format(Locale.ROOT, "top=%.4f ring=%.3f run=%d xspan=%.3f stand=%d same=%d",
                topDy, if (tNs - gapNs < 150_000_000L) gapRing - discY else -1f, (tNs - runNs) / 1_000_000L, runXMax - runXMin,
                (upSinceNs - lastStandNs) / 1_000_000L, if (same) 1 else 0))
            if (upSnaps < 25) { upSnaps++; snapNow = true }
            if (upClips < 8) { upClips++; requestColour("up_def", tNs, 0.5, 0.5) }
            if (same && cfg.shotBlock && cfg.upPress) upPendNs = tNs
        }
    }

    /**
     * Experimental arms-up press (vc1056, only with [Bench.Config.upPress]). The gap rules keep their measured timing:
     * the event is held UP_HOLD_NS (two scans) and pressed only if no gap rule pressed or is waiting for its
     * confirmation and the arms are still up. In the practice clips the arms-up run began 25-75 ms before the gap
     * trigger, the event comes ~33 ms into the run, so this press lands about -10..+40 ms around where the gap trigger
     * would have been. It is the only rule that works without a foot ring (vc1044 practice 18.8 s: ring hidden, no
     * trigger, arms up; vc1038 match 176.2 s in the joystick corner, 270.3 s at the bottom edge). Known from the match
     * clips: a dribbler straightening up reads as arms-up too (vc1044 471.3 s, 0.44 s before his real shot), and a
     * shooter seen from behind lifts the ball beside the marker, not under it (vc1038 207.4 s: seen only after the
     * release). The rate of such presses over a whole match is not measured yet, so this stays off by default.
     */
    private fun checkUpPending(tNs: Long, found: Boolean) {
        if (upPendNs == 0L) return
        val age = tNs - upPendNs
        if (age < UP_HOLD_NS) return
        upPendNs = 0L
        val why = when {
            !found || age > 100_000_000L -> "lost"
            upRun == 0 -> "down"
            pendNs != 0L -> "gap_pending"
            tNs - lastPressNs < 1_500_000_000L -> "pressed"
            else -> ""
        }
        if (why.isNotEmpty()) { event(tNs, "up_dropped", 0f, 0f, 0f, why); return }
        val live = cfg.autoBlockLive
        val ringKnown = tNs - gapNs < 150_000_000L && gapRing - discY in 0.12f..0.6f
        lastShotFireNs = tNs
        shotTriggers++; upTriggers++
        synchronized(pendingTriggers) { pendingTriggers.add(tNs) }
        if (shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_up", 0.6, 1.8 + cfg.shotDelayMs / 1000.0) }
        // Without a foot ring the feet height is a guess (typical gap 0.3 H), so only the sideways distance counts then.
        var meDist = findMe(discX, if (ringKnown) gapRing else minOf(0.95f, discY + 0.3f))
        if (!ringKnown && meDist >= 0f) meDist = Math.abs(meX - discX)
        val d2 = String.format(Locale.ROOT, "up gap=%.3f top=%.4f upage=%d run=%d sens=%d delay=%d xspan=%.3f me=%.3f/%.3f d=%.3f",
            if (ringKnown) gapRing - discY else -1f, topDy, (tNs - upSinceNs) / 1_000_000L, (tNs - runNs) / 1_000_000L,
            cfg.shotSens, cfg.shotDelayMs, runXMax - runXMin, meX, meY, meDist)
        event(tNs, "shot_trigger", 0f, 0f, 0f, d2)
        snapNow = true
        if (meDist > ME_FAR) { shotFar++; reject(tNs, "rejected_far", d2); return }
        if (live) main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) }
        press(tNs, live, "$d2 up", 0L)
    }

    /** " top=<top_dy> topage=<ms since the arms went up, -1 = not up>" for the trigger detail. */
    private fun topText(tNs: Long): String =
        String.format(Locale.ROOT, " top=%.4f topage=%d", topDy, if (upRun > 0) (tNs - upSinceNs) / 1_000_000L else -1L)

    private fun onDisc(tNs: Long) {
        // A step of 0.15 H+ in one scan is the tracker switching between the foot ring and the disc (vc1033: every
        // "release" trigger was a 0.28-0.29 H ring->disc switch), never a real move: start the track over.
        if (hCount > 0) { val li = (hPos - 1 + histN) % histN; if (Math.abs(discY - hY[li]) > 0.15f) hCount = 0 }
        // A "run" = one unbroken sighting of the marker on one player (see the fresh rule in checkShot). It starts over
        // after 0.1 s without a marker, an x jump (another player) or a y step > 0.06 H in one scan (real lifts move
        // <= 0.03 per scan; vc1035 116.65 / 207.99 s: the tracker sat on a lower blob for one scan, then stepped 0.13-0.15).
        if (runLastNs == 0L || tNs - runLastNs > 100_000_000L || Math.abs(discX - runLastX) > 0.08f || Math.abs(discY - runLastY) > 0.06f) {
            runNs = tNs; runY0 = discY; runXMin = discX; runXMax = discX; runG = 0
        }
        runLastNs = tNs; runLastX = discX; runLastY = discY
        runXMin = minOf(runXMin, discX); runXMax = maxOf(runXMax, discX)
        if (ringY >= 0f) { gapNs = tNs; gapRing = ringY }
        if (tNs - gapNs < 150_000_000L && gapRing - discY in 0.12f..0.6f) {
            val g = gapRing - discY
            if (runG == 0) { runG0 = g; runG1 = g; runRMin = gapRing; runRMax = gapRing }
            else { if (runG == 1) runG1 = g; runRMin = minOf(runRMin, gapRing); runRMax = maxOf(runRMax, gapRing) }
            runG++
            // A step of 0.07 H in one scan (real lifts: <= 0.03) or a pause is the marker moving to another player or the
            // tracker switching blobs: the early rule must not compare across it (vc1035 116.87 s: 0.161 -> 0.283).
            if (gCount > 0) {
                val li = (gPos - 1 + histN) % histN
                if (tNs - gT[li] > 60_000_000L || Math.abs(gapRing - discY - gV[li]) >= 0.07f) gJumpNs = tNs
            }
            gT[gPos] = tNs; gV[gPos] = gapRing - discY
            gPos = (gPos + 1) % histN
            if (gCount < histN) gCount++
        }
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
                val shotLike = side != SIDE_OFF && tNs - lastDefNs < 1_000_000_000L && upward
                if (shotLike) {
                    shotCandidates++
                    onRelease(tNs)
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
        checkShot(tNs)
    }

    // ------------------------------------------------------------------ live shot trigger (v1, unmeasured)

    /**
     * SHOT trigger v1. A shot is the only time the disc suddenly starts going UP on defence: the shooter gathers and
     * jumps (ball/head rise) a few hundred ms before the ball leaves (clip 14: arms up ~300 ms before 박스아웃). Running
     * up-court also moves the disc up the screen, but steadily, so the rule needs an upward *change* of speed, not just
     * upward speed. If the gather is missed, the release itself (disc flying up) still fires as a late fallback.
     * All three thresholds are guesses until the vc1026+ bundles are measured; [cfg.shotSens] scales them.
     */
    private val gT = LongArray(histN); private val gV = FloatArray(histN)
    private var gPos = 0; private var gCount = 0
    private var gapNs = 0L; private var gapRing = -1f
    private var gJumpNs = 0L
    // current run of the marker on one player: start, disc y at the start, x range, last point, gap samples (count,
    // first two values, ring y range)
    private var runNs = 0L; private var runY0 = 0f; private var runXMin = 0f; private var runXMax = 0f
    private var runLastNs = 0L; private var runLastX = 0f; private var runLastY = 0f
    private var runG = 0; private var runG0 = 0f; private var runG1 = 0f; private var runRMin = 0f; private var runRMax = 0f
    private var lastShotFireNs = 0L
    private var lastDefNs = 0L
    @Volatile var shotTriggers = 0; private set
    @Volatile var shotFired = 0; private set
    @Volatile var shotSkipped = 0; private set
    private val pendingTriggers = ArrayList<Long>()        // trigger times not yet followed by a release
    private val leadsMs = ArrayList<Int>()                 // trigger -> following release
    @Volatile var releasesWithoutTrigger = 0; private set
    @Volatile var triggersWithoutRelease = 0; private set

    private fun ref(tNs: Long, lo: Long, hi: Long): Int {
        var best = -1; var bestErr = Long.MAX_VALUE; val mid = (lo + hi) / 2
        for (i in 1 until hCount) {
            val idx = (hPos - 1 - i + 2 * histN) % histN
            val age = tNs - hT[idx]
            if (age > hi) break
            if (age >= lo && Math.abs(age - mid) < bestErr) { bestErr = Math.abs(age - mid); best = idx }
        }
        return best
    }

    private fun checkShot(tNs: Long) {
        expireTriggers(tNs)
        // The slot can turn grey or into 리바운드 around the shot itself, so accept defence seen within the last second.
        if (side == SIDE_OFF || tNs - lastDefNs > 1_000_000_000L || tNs - lastShotFireNs < 1_500_000_000L) return
        val live = cfg.shotBlock && cfg.autoBlockLive
        // v6 (vc1052) fresh rule: pass -> quick shot. The marker lands on a player who is already going up, so the 0.2-0.6 s
        // of history that v2 / the early rule need never exists (practice marks with no trigger at all: vc1035 73.5, 81.6,
        // vc1044 45.4, 53.3: marker visible 0.3-0.77 s before the shot). Inside the first 50-350 ms of a run: gap grown
        // >= FRESH_RISE above the lower of the run's first two gaps, last three gaps not falling, the disc itself up
        // >= 0.02 H since the run began, foot ring still (<= 0.012 H) and no sideways sway. Replay (replay9.py, F 0.04) on
        // four bundles: + 6 practice presses, all real shots, 50-70 ms after the marker landed; + 3 match presses
        // (vc1035 383.6, vc1038 270.6, vc1044 503.0), each a 0.07-0.13 H jump arc; F 0.03 added 6 more unchecked ones.
        val gLast = (gPos - 1 + histN) % histN
        if (runG >= 2 && tNs - gT[gLast] <= 1_000_000L && tNs - runNs in 50_000_000L..350_000_000L) {
            val fNeed = FRESH_RISE[cfg.shotSens.coerceIn(0, FRESH_RISE.size - 1)]
            val g0 = gV[gLast]
            val grown = g0 - minOf(runG0, runG1)
            val up = runY0 - discY
            val still = runRMax - runRMin <= 0.012f && runXMax - runXMin < CALM_XSPAN
            if (live && still && grown >= 0.5f * fNeed && up >= 0.0099f && tNs - lastArmNs > 300_000_000L) {
                lastArmNs = tNs; shotArms++
                main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) }
                event(tNs, "arm", 0f, 0f, 0f, "half_fresh")
            }
            if (runG >= 3 && still && grown >= fNeed - 0.001f && up >= 0.0199f) {
                val g1 = gV[(gPos - 2 + histN) % histN]; val g2 = gV[(gPos - 3 + histN) % histN]
                if (g0 >= g1 - 0.006f && g1 >= g2 - 0.006f) {
                    lastShotFireNs = tNs
                    shotTriggers++
                    synchronized(pendingTriggers) { pendingTriggers.add(tNs) }
                    if (shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_fresh", 0.6, 1.8 + cfg.shotDelayMs / 1000.0) }
                    val meDist = findMe(discX, discY + g0)
                    val d2 = String.format(Locale.ROOT, "fresh gap=%.3f first=%.3f up=%.3f run=%d n=%d sens=%d delay=%d xspan=%.3f me=%.3f/%.3f d=%.3f",
                        g0, minOf(runG0, runG1), up, (tNs - runNs) / 1_000_000L, runG, cfg.shotSens, cfg.shotDelayMs, runXMax - runXMin, meX, meY, meDist) + topText(tNs)
                    event(tNs, "shot_trigger", 0f, 0f, 0f, d2)
                    snapNow = true
                    if (meDist > ME_FAR) { shotFar++; reject(tNs, "rejected_far", d2); return }
                    if (live) main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) }
                    press(tNs, live, d2 + " fresh", 0L)
                    return
                }
            }
        }
        val a = ref(tNs, 60_000_000L, 130_000_000L); if (a < 0) return
        val b = ref(tNs, 200_000_000L, 330_000_000L); if (b < 0) return
        val dtA = (tNs - hT[a]) / 1e9f; val dtB = (hT[a] - hT[b]) / 1e9f
        if (dtA <= 0f || dtB <= 0f) return
        val vy = (hY[a] - discY) / dtA                       // screen-heights/s, + = up
        val vx = (discX - hX[a]) * aspect / dtA
        val vyPrev = (hY[b] - hY[a]) / dtB
        val c = ref(tNs, 120_000_000L, 200_000_000L)
        val rise = (if (c >= 0) hY[c] else hY[a]) - discY
        // Dribbling (if the disc follows the ball) bounces up and down: count direction changes over 600 ms.
        var flips = 0; var lastSign = 0; var pts = 0
        var prevY = discY
        for (i in 1 until hCount) {
            val idx = (hPos - 1 - i + 2 * histN) % histN
            if (tNs - hT[idx] > 600_000_000L) break
            pts++
            val d = hY[idx] - prevY; prevY = hY[idx]
            if (Math.abs(d) > 0.004f) { val sgn = if (d > 0) 1 else -1; if (lastSign != 0 && sgn != lastSign) flips++; lastSign = sgn }
        }
        if (pts < 5) return
        // v2 (vc1034): fire on the GAP between the shooter's disc and the defender's foot ring growing fast. The camera
        // moves both together, so only the shooter's own rise (ball lifted / jump) opens the gap, and the stacked pair
        // exists only when our player stands under the shooter (in block range). Offline on vc1033 practice it fired
        // on every ~7 s shot incl. 4 the v1 rule missed (172.6, 179.7, 196.0, 202.9) and never on a ring->disc switch.
        val need = GAP_RISE[cfg.shotSens.coerceIn(0, GAP_RISE.size - 1)]
        if (gCount < 6 || tNs - gT[(gPos - 1 + histN) % histN] > 1_000_000L) return     // no gap this scan
        val gNow = gV[(gPos - 1 + histN) % histN]
        var gOld = Float.MAX_VALUE; val base = ArrayList<Float>(16)
        var lo0 = Float.MAX_VALUE; var lo1 = Float.MAX_VALUE; var loN = 0     // two lowest gaps aged 60-400 ms since gJumpNs
        for (i in 1 until gCount) {
            val idx = (gPos - 1 - i + 2 * histN) % histN
            val age = tNs - gT[idx]
            if (age > 600_000_000L) break
            if (age in 60_000_000L..140_000_000L) gOld = minOf(gOld, gV[idx])
            if (age >= 250_000_000L) base.add(gV[idx])
            if (age in 60_000_000L..400_000_000L && gT[idx] >= gJumpNs) {
                loN++
                val v = gV[idx]
                if (v < lo0) { lo1 = lo0; lo0 = v } else if (v < lo1) lo1 = v
            }
        }
        val v2Ready = gOld != Float.MAX_VALUE && base.size >= 5
        base.sort()
        val gBase = if (v2Ready) base[base.size / 2] else 0f
        // v5 (vc1050) early rule. Practice shooters crouch 0.04-0.06 H for ~0.3 s before the jump, so the 250-600 ms
        // median sits above the crouch and v2 fired only 0.06-0.07 above the bottom: press 47-129 ms after the disc
        // left the crouch (three practice bundles, timing verdicts only: <= 72 ms 8/8 blocked, 73-100 ms 6/11,
        // > 100 ms 1/6). Measure the rise from the bottom itself: lowest gap of the last 60-400 ms, one outlier allowed.
        val earlyNeed = EARLY_RISE[cfg.shotSens.coerceIn(0, EARLY_RISE.size - 1)]
        val gLow = if (loN >= 5) maxOf(lo0, lo1 - 0.006f) else Float.MAX_VALUE
        val early = loN >= 5 && gNow - gLow >= earlyNeed - 0.001f
        val v2 = v2Ready && gNow - gOld >= need && gNow - gBase >= need
        // vc1038: the press reached the game 40-56 ms after the trigger, 40 of that waiting for the block tile to turn
        // pass-through. Open the tile already at half the rise (offline: 0-34 ms before the trigger in practice).
        val halfV2 = v2Ready && gNow - gOld >= 0.5f * need && gNow - gBase >= 0.5f * need
        val halfEarly = loN >= 5 && gNow - gLow >= 0.6f * earlyNeed - 0.001f
        if (live && (halfV2 || halfEarly) && tNs - lastArmNs > 300_000_000L) {
            lastArmNs = tNs; shotArms++
            main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) }
            event(tNs, "arm", 0f, 0f, 0f, if (halfV2) "half" else "half_early")
        }
        if (!v2 && !early) return
        // v3 (vc1038 match: 0/14, 8 presses on dribbles/moves). Sideways sway of the disc over the last 600 ms: practice
        // shots <= 0.026 W, real match shots 0.023-0.059, phantoms 0.036-0.223. Above 0.1 = dribbling/running, not a shot.
        var xMin = discX; var xMax = discX; var spanNs = 0L
        for (i in 1 until hCount) {
            val idx = (hPos - 1 - i + 2 * histN) % histN
            if (tNs - hT[idx] > 600_000_000L) break
            xMin = minOf(xMin, hX[idx]); xMax = maxOf(xMax, hX[idx]); spanNs = tNs - hT[idx]
        }
        val xspan = xMax - xMin
        val calm = xspan < CALM_XSPAN && spanNs >= 400_000_000L
        // The early rule alone is trusted only on a calm shooter (replay on the vc1038/vc1044 matches: +2 / +0 presses);
        // a moving one still needs v2 and the confirmation below. No lockout here, so v2 can fire a few scans later.
        if (!v2 && !calm) return
        val kind = "gap"
        lastShotFireNs = tNs
        shotTriggers++
        synchronized(pendingTriggers) { pendingTriggers.add(tNs) }
        val detail = String.format(Locale.ROOT, "%s gap=%.3f old=%.3f base=%.3f low=%.3f early=%d vy=%.2f vyPrev=%.2f vx=%.2f rise=%.3f flips=%d sens=%d delay=%d",
            kind, gNow, if (v2Ready) gOld else -1f, if (v2Ready) gBase else -1f, if (loN >= 5) gLow else -1f, if (v2) 0 else 1,
            vy, vyPrev, vx, rise, flips, cfg.shotSens, cfg.shotDelayMs)
        // 0.6 s before shows the lift; the game's verdict ('블록' / '블록 위치 부족' / '블록 타이밍 늦음') shows at
        // +0.4..+1.4 s (vc1035), so keep 1.8 s after.
        if (shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_$kind", 0.6, 1.8 + cfg.shotDelayMs / 1000.0) }
        // Where is my own player? (vc1038: all three '블록 위치 부족' presses had him 0.12-0.21 W from the shooter.)
        val meDist = findMe(discX, discY + gNow)
        val d2 = String.format(Locale.ROOT, "%s xspan=%.3f track=%d me=%.3f/%.3f d=%.3f", detail, xspan, spanNs / 1_000_000L, meX, meY, meDist) + topText(tNs)
        event(tNs, "shot_trigger", 0f, 0f, 0f, d2)
        snapNow = true     // one colour screen per trigger: checks the my-player finder against the game's verdict
        // vc1053: a shooter on the move (xspan >= 0.1) is no longer dropped at once. He is held like any moving
        // shooter but must rise MOVE_RISE (0.06 H) more within the window. Replay on three matches (replay9.py with
        // MRISE 0.06 = replay10.py): 13 dribble/drive triggers stay dropped; three come back: vc1035 349.9 and 382.4 (real shots,
        // rose 0.12-0.14) and vc1044 562.4 (drive to the rim, gap 0.24 -> 0.44), pressed 66-92 ms after the trigger.
        val moving = xspan >= CONFIRM_XSPAN
        if (meDist > ME_FAR) { shotFar++; reject(tNs, "rejected_far", d2); return }
        if (live) main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) }
        // Calm shooter (practice: xspan <= 0.026, 10/15 blocked with an immediate press; waiting for the confirmation
        // would add 66-167 ms and practice '늦음' was already ~100 ms late): press now.
        // "Calm" needs a track long enough to show sway: right after a ring<->disc switch the history restarts and a
        // moving shooter read as xspan 0.02 (vc1035 330.0 s, really 0.138). Replay: all 15 practice presses had >= 0.57 s.
        // An early-only press is held EARLY_DELAY_MS so it lands 35-80 ms after the disc left the crouch (replay), inside
        // the range that was blocked; nothing earlier than 47 ms has been tried yet, so do not go below it blindly.
        if (calm) { press(tNs, live, d2 + " calm", if (v2) 0L else -EARLY_DELAY_MS); return }
        // Moving shooter: wait (<= 200 ms) for the disc to keep rising: real shots rose 0.039-0.139 H, phantoms at most
        // 0.028 (vc1038), and the two in-range match shots pressed immediately were both '타이밍 빠름'.
        pendNs = tNs; pendY = discY; pendDetail = if (moving) "$d2 move" else d2; pendLive = live
        pendRise = if (moving) MOVE_RISE else CONFIRM_RISE
        pendDrop = if (moving) "rejected_xspan" else "rejected_norise"
        Bench.shotLive = liveText()
    }

    private var pendNs = 0L; private var pendY = 0f; private var pendDetail = ""; private var pendLive = false
    private var pendRise = CONFIRM_RISE; private var pendDrop = "rejected_norise"
    private var lastArmNs = 0L
    @Volatile var shotRejected = 0; private set
    @Volatile var shotFar = 0; private set
    @Volatile var shotArms = 0; private set

    /** A dropped trigger must not block the real shot that follows a fake (vc1038: 394.5 -> 396.4): 0.4 s, not 1.5 s. */
    private fun reject(tNs: Long, name: String, detail: String) {
        shotRejected++
        lastShotFireNs = tNs - 1_100_000_000L
        event(tNs, name, 0f, 0f, 0f, detail)
        Bench.shotLive = liveText()
    }

    /** [ageMs] = time already spent since the trigger; the user's extra delay counts from the trigger. */
    private fun press(tNs: Long, live: Boolean, detail: String, ageMs: Long) {
        lastPressNs = tNs
        if (!live) { shotSkipped++; event(tNs, "would_press", 0f, 0f, 0f, detail); Bench.shotLive = liveText(); return }
        val delay = (cfg.shotDelayMs.toLong() - ageMs).coerceAtLeast(0L)
        main.postDelayed({
            val ok = BlockAccessibilityService.instance?.inGame?.fireBlock("SHOT") ?: false
            if (ok) shotFired++ else shotSkipped++
            event(System.nanoTime(), if (ok) "pressed" else "not_pressed", 0f, 0f, 0f, detail)
            Bench.shotLive = liveText()
        }, delay)
    }

    // ------------------------------------------------------------------ my own player

    private var curBuf: ByteBuffer? = null; private var curW = 0; private var curH = 0; private var curStride = 0
    private var meX = -1f; private var meY = -1f

    /**
     * My player stands on a dark navy disc with a bright blue rim (team-mates have only a shadow, the ball handler a red
     * ring). Looks for it around the shooter's feet ([sx], [sy]) on the coarse grid: a 16x6-cell window at least half
     * navy, blue rim cells on both sides, and not part of a larger dark area. Returns the feet-to-feet distance in
     * screen widths, or -1 when not found. Prototype on the vc1035/vc1038 colour screens (36 with a tracked disc):
     * found 4 times in play, all correct and all with my player standing apart (d 0.10-0.17); never found when he
     * stands against the shooter (rims overlap, feet cover the disc) or in practice. So it can only say "far".
     * Called once per trigger (capture thread).
     */
    private fun findMe(sx: Float, sy: Float): Float {
        meX = -1f; meY = -1f
        val buf = curBuf ?: return -1f
        val w = curW; val h = curH; val s = step
        val nw = 16; val nh = 6
        val ax = ((sx - 0.3f) * gw).toInt().coerceIn(0, gw - 1); val bx = ((sx + 0.3f) * gw).toInt().coerceIn(0, gw)
        val ay = ((discY + 0.05f) * gh).toInt().coerceIn(0, gh - 1); val by = (minOf(0.95f, sy + 0.3f) * gh).toInt().coerceIn(0, gh)
        val rw = bx - ax; val rh = by - ay
        if (rw <= nw || rh <= nh) return -1f
        val st = rw + 1
        val sn = IntArray(st * (rh + 1)); val sb = IntArray(st * (rh + 1))
        for (gy in 0 until rh) {
            var rn = 0; var rb = 0
            val py = (ay + gy) * s
            for (gx in 0 until rw) {
                val px = (ax + gx) * s
                if (!isHud(px, py, w, h)) {
                    val o = py * curStride + px * 4
                    val r = buf.get(o).toInt() and 0xFF
                    if (r < 120) {
                        val g = buf.get(o + 1).toInt() and 0xFF; val b = buf.get(o + 2).toInt() and 0xFF
                        if (r < 75 && g < 70 && b in 25..120 && b - r >= 8) rn++
                        else if (g in 90..200 && b > 165 && b - r > 80) rb++
                    }
                }
                sn[(gy + 1) * st + gx + 1] = sn[gy * st + gx + 1] + rn
                sb[(gy + 1) * st + gx + 1] = sb[gy * st + gx + 1] + rb
            }
        }
        fun box(a: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val xa = x0.coerceIn(0, rw); val xb = x1.coerceIn(0, rw); val ya = y0.coerceIn(0, rh); val yb = y1.coerceIn(0, rh)
            return a[yb * st + xb] - a[ya * st + xb] - a[yb * st + xa] + a[ya * st + xa]
        }
        var best = 0; var bxc = -1; var byc = -1
        for (y in 0..rh - nh) for (x in 0..rw - nw) {
            val n = box(sn, x, y, x + nw, y + nh)
            if (n < nw * nh / 2) continue
            val l = box(sb, x - 5, y - 2, x + 3, y + nh + 2); if (l < 3) continue
            val r = box(sb, x + nw - 3, y - 2, x + nw + 5, y + nh + 2); if (r < 3) continue
            if (box(sn, x - nw, y - nh, x + 2 * nw, y + 2 * nh) > 2.2f * nw * nh) continue
            val sc = n + minOf(l, 12) + minOf(r, 12)
            if (sc > best) { best = sc; bxc = x; byc = y }
        }
        if (bxc < 0) return -1f
        meX = (ax + bxc + nw / 2f) * s / w; meY = (ay + byc + nh / 2f) * s / h
        val dx = meX - sx; val dy = (meY - sy) / aspect
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /** Capture thread, every scan. Confirms or drops the trigger held by [checkShot]. */
    private fun checkPending(tNs: Long, found: Boolean) {
        if (pendNs == 0L) return
        val age = tNs - pendNs
        val up = if (found) pendY - discY else 0f
        // A step of 0.15 H+ is the tracker switching ring <-> disc, not a rise (see onDisc).
        if (found && up >= pendRise && up < 0.15f) {
            val detail = String.format(Locale.ROOT, "%s up=%.3f after=%d", pendDetail, up, age / 1_000_000L)
            val live = pendLive
            pendNs = 0L
            event(tNs, "confirmed", 0f, 0f, 0f, detail)
            press(tNs, live, detail, age / 1_000_000L)
        } else if (age > CONFIRM_WINDOW_NS) {
            pendNs = 0L
            reject(tNs, pendDrop, String.format(Locale.ROOT, "%s up=%.3f", pendDetail, up))
        }
    }

    private fun onRelease(tNs: Long) {
        synchronized(pendingTriggers) {
            val t = pendingTriggers.lastOrNull { tNs - it in 0..1_500_000_000L }
            if (t != null) { leadsMs.add(((tNs - t) / 1_000_000L).toInt()); pendingTriggers.remove(t) }
            else if (tNs - lastShotFireNs > 1_500_000_000L || lastShotFireNs == 0L) releasesWithoutTrigger++
        }
    }

    private fun expireTriggers(tNs: Long) = synchronized(pendingTriggers) {
        val it = pendingTriggers.iterator()
        while (it.hasNext()) if (tNs - it.next() > 1_500_000_000L) { it.remove(); triggersWithoutRelease++ }
    }

    private fun event(tNs: Long, name: String, dx: Float, dy: Float, dtMs: Float, detail: String) = synchronized(events) {
        events.append(tNs).append(',').append(String.format(Locale.ROOT, "%.3f,%s,%s,%.4f,%.4f,%.4f,%.4f,%.0f,%s\n",
            (tNs - startedNs) / 1e9, name, sideName(side), discX, discY, dx, dy, dtMs, detail))
    }

    private fun liveText() = "일반 슛 감지 ${shotTriggers}회 · 헛블락 거름 ${shotRejected}회(멀어서 ${shotFar}회) · 블록 누름 ${shotFired}회 · 건너뜀 ${shotSkipped}회"

    fun shotSummary(): String {
        val leads = synchronized(pendingTriggers) { leadsMs.sorted() }
        val med = if (leads.isEmpty()) "-" else "${leads[leads.size / 2]}ms"
        return "일반 슛 발동 ${shotTriggers}회(${if (cfg.shotBlock && cfg.autoBlockLive) "실제 누름" else "기록만"}, 민감도 ${cfg.shotSens}, 지연 ${cfg.shotDelayMs}ms) → 거름 ${shotRejected}회(그중 내 선수가 멀어서 ${shotFar}회), 버튼 미리 열기 ${shotArms}회, 누름 ${shotFired}회, 건너뜀 ${shotSkipped}회\n" +
            "  팔 올림 감지 ${upEvents}회(팔 올림으로 누르기 ${if (cfg.upPress) "켬" else "끔"}, 그걸로 발동 ${upTriggers}회)\n" +
            "  웅크림 감지 ${crouchEvents}회(웅크릴 때 누르기 ${if (cfg.crouchPress) "켬" else "끔"}, 그걸로 발동 ${crouchTriggers}회)\n" +
            "  모으기 감지 뒤 공이 떠남 ${leads.size}회(앞섬 중앙값 $med, 전체 ${leads.joinToString(" ")}) · 감지했는데 공이 안 떠남(오발 추정) ${triggersWithoutRelease}회 · 공이 떠났는데 미리 감지 못함 ${releasesWithoutTrigger}회"
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
        const val MAX_SHOT_CLIPS = 80
        const val MAX_COLOUR_CLIPS = 38
        /** Crouch cue ([crouch]): history size, sink needed (7 px at 720p) within the window, calm y range (9 px). */
        const val CR_N = 96
        const val CROUCH_SINK = 0.0097f
        const val CROUCH_WIN_NS = 170_000_000L
        const val CROUCH_CALM = 0.0125f
        /**
         * vc1061: 80/120/160 (was 0/40/80). The box-out step (tools/analysis/boxstep.py) is a release clock: on the LaMelo
         * track the crouch fired 340-460 ms (median ~420) before it, and Curry's blocks in the same bundle were press
         * requests 282-313 ms before it ('무리한 블록' at 231). So the waits aim at 340 / 300 / 260 ms before the release.
         */
        val CROUCH_DELAYS = longArrayOf(80L, 120L, 160L)
        /** Outline top this close under the marker (share of H; 14 px at 720p) = hands or ball lifted. Shadow only. */
        const val UP_DY = 0.0195f
        /** Experimental arms-up press: wait this long for a gap rule to fire first (two scans at 60 Hz). */
        const val UP_HOLD_NS = 30_000_000L
        const val MAX_LOG_LINES = 120_000
        /** per sensitivity 0 (fewer presses) .. 2 (more): min upward speed (H/s), min speed-up vs the 200 ms before (H/s), min rise (H). */
        /** v2 gap rule: min growth of (foot ring y - disc y) vs ~100 ms ago and vs the 250-600 ms median, per sensitivity. */
        val GAP_RISE = floatArrayOf(0.04f, 0.03f, 0.02f)
        /** v5 early rule: min growth of the gap above its lowest value of the last 60-400 ms, per sensitivity. */
        val EARLY_RISE = floatArrayOf(0.06f, 0.05f, 0.04f)
        /** v6 fresh rule: min growth of the gap above the first two gaps of a run that began 50-350 ms ago, per sensitivity
         *  (0.03 was replayed and added 6 unchecked match presses, so the highest setting stays at 0.04). */
        val FRESH_RISE = floatArrayOf(0.05f, 0.04f, 0.04f)
        /** Extra wait before an early-only press (see checkShot). */
        const val EARLY_DELAY_MS = 25L
        /** v3 confirmation (vc1038 offline, 6 real shots / 8 phantoms): x sway limit (W), extra rise needed (H), wait. */
        const val CONFIRM_XSPAN = 0.1f
        const val CONFIRM_RISE = 0.025f
        /** Extra rise needed from a shooter whose marker swayed >= CONFIRM_XSPAN (replay10.py: none of the 13 dropped match triggers reached it). */
        const val MOVE_RISE = 0.06f
        // 200 ms (not 133): phantoms stay flat (<= 0.028 H even at 200 ms) while slower practice rises reach 0.028 by
        // 200 ms: vc1035 successes 9/9 kept, vc1033 practice 16/26, vc1038 match real 4/6, phantoms 0/8. The press
        // goes as soon as the rise is reached; trigger->jump was 301-524 ms, so 200 + 75 ms is still before the jump.
        const val CONFIRM_WINDOW_NS = 200_000_000L
        /** Below this sway the press goes at once (practice shots <= 0.026 W; every match phantom was >= 0.036). */
        const val CALM_XSPAN = 0.03f
        /** No press when my player is found this far (screen widths) from the shooter's feet. vc1038, read off the
         *  trigger frames by eye: '위치 부족' at 0.12 / 0.15 / 0.21, a timing verdict (so in reach) at 0.07 / 0.11. */
        const val ME_FAR = 0.14f
        /** How long the block tile stays open to injection after a pre-arm. */
        const val ARM_MS = 300L
        val SENS = arrayOf(floatArrayOf(0.45f, 0.35f, 0.035f), floatArrayOf(0.35f, 0.25f, 0.025f), floatArrayOf(0.25f, 0.18f, 0.018f))
        fun sideName(s: Int) = when (s) { SIDE_DEF -> "def"; SIDE_OFF -> "off"; else -> "unk" }
    }
}
