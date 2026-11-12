package dev.dunkblock.bench

import dev.dunkblock.core.MatchupFinder
import dev.dunkblock.core.MatchupPicker
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * RECORD ONLY (HANDOFF 0-4v): who is my matchup and where are we both, about 9 times a second, into matchup.csv.
 * Nothing here presses; the auto-block behaviour does not read it (only the shot_trigger event text gets the
 * distance appended, see ShotWatcher.event). It steers only with the opt-in practice-court experiment [follow]
 * (default 0 = the follow rule's answer is logged and nothing else happens).
 * One more reader since HANDOFF 0-4z10: [near] (my ring against the ball) is logged at every trigger and DROPS the
 * trigger only with the opt-in switch Bench.Config.nearOnly (off by default).
 *
 * The finder was designed on 2340 x 1080 screen recordings scaled to 1170 x 540. Here the picture comes from the app's
 * own capture (about 1560 x 720) by nearest sampling. A FILLED blue joystick pad (the bench probe) is painted black
 * before the scan (its blue passes the "my ring" and the PG tag colour tests); the in-game pad is a thin frame only
 * since 11-12 (HANDOFF 0-4z6) and is left alone (csv column pad: 0 none, 1 filled and painted, 2 frame only). Whether the colours hold
 * on this capture is NOT known; this log plus the colour screens it asks for (snapNow) are how to find out.
 *
 * Cost: one copy of the frame on the capture thread (about 4.5 MB, every [PERIOD_NS]); the scan runs on its own
 * low-priority thread and a frame is skipped while the previous scan is still running. The csv keeps every scan
 * (tags, fans, ring) in mfollow.py's column format, so tools/analysis/mpick.py runs on it unchanged.
 */
class MatchupShadow(private val startedNs: Long, private val follow: Int = 0) {
    /** The latest scan, for the trigger text. [d] = feet-to-feet distance in px of the 1170 x 540 picture (dy * 1.6). */
    class Last(val tNs: Long, val src: Int, val dx: Float, val dy: Float, val d: Float)

    private val finder = MatchupFinder()
    // track = true: the identity track for a matchup of my position (HANDOFF 0-4z6). It changes this record and the
    // opt-in follow experiment only; nothing that presses reads the picker.
    // strict = true (HANDOFF 0-4z15): a target only where the game's own marker (the fan) confirms it. The old rule took
    // a team mate of the matchup's position as the target when the matchup's tag was hidden (phone match of 11-12 11:13).
    private val picker = MatchupPicker(track = true, strict = true)
    // The ONE thing here that is not record-only, and only with the opt-in experiment [Bench.Config.follow] (default 0).
    private val rule = dev.dunkblock.core.FollowRule()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile var pushes = 0; private set
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "matchup").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val busy = AtomicBoolean(false)
    private var rows = ByteArray(0)
    private val rgb = ByteArray(MatchupFinder.W * MatchupFinder.H * 3)
    private val xmap = IntArray(MatchupFinder.W)
    private var mapW = 0
    private var lastNs = 0L
    private var lastSnapNs = 0L
    private var snaps = 0
    private var lines = 0
    private val log = StringBuilder(1 shl 16).append("t_s,side,def,me_x,me_y,me_conf,tags,fans,btn_label,my_cls,cls,src,tgt_x,tgt_y,my_x,my_y,why,us,pad,push_deg,push_why,trk\n")

    @Volatile var last: Last? = null; private set
    /** Set by the scan thread when a colour screen of this moment would help; the capture thread clears it. */
    @Volatile var wantSnap = false
    @Volatile var scans = 0; private set
    @Volatile var withTarget = 0; private set
    @Volatile var defScans = 0; private set
    @Volatile var skipped = 0; private set
    @Volatile var myCls = -1; private set
    @Volatile var cls = -1; private set
    @Volatile var usMax = 0; private set
    private var usTotal = 0L

    fun csv(): String = synchronized(log) { log.toString() }

    fun summary(): String {
        fun name(c: Int) = if (c >= 0) MatchupFinder.CLASSES[c] else "모름"
        return String.format(Locale.ROOT, "매치업 찾기(기록만): 훑기 %d회(수비 %d회, 그중 대상 찾음 %d회), 내 포지션 %s, 매치업 포지션 %s, 평균 %.1f ms, 최대 %.1f ms, 건너뜀 %d회\n  따라가기 규칙이 밀라고 한 훑기 %d회(%s)",
            scans, defScans, withTarget, name(myCls), name(cls), if (scans > 0) usTotal / scans / 1000.0 else 0.0, usMax / 1000.0, skipped,
            pushes, when (follow) { 1 -> "연습장 시험 켬: 엄지를 올려 둔 동안 실제로 밂"; 2 -> "연습장 시험 켬: 손 없이 실제로 밂"; else -> "기록만, 조이스틱은 건드리지 않음" })
    }

    /** Capture thread. [side] = ShotWatcher's reading (the block button: 1 defence, 2 offence), [def] = on defence now. */
    fun onFrame(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, tNs: Long, side: Int, def: Boolean) {
        if (tNs - lastNs < PERIOD_NS) return
        // Only a screen of the recordings' shape (2340 : 1080); anything else would shift every position.
        if (Math.abs(w.toLong() * MatchupFinder.H - h.toLong() * MatchupFinder.W) > 0.03 * w * MatchupFinder.H) return
        if (!busy.compareAndSet(false, true)) { skipped++; return }
        lastNs = tNs
        val n: Int
        try {
            val d = buf.duplicate(); d.rewind()
            if (rows.size < rowStride * h) rows = ByteArray(rowStride * h)
            n = minOf(d.remaining(), rowStride * h)
            d.get(rows, 0, n)
        } catch (t: Throwable) { busy.set(false); return }
        val svc = BlockAccessibilityService.instance
        val pad = svc?.padFrac
        val padFilled = svc?.padFilled ?: true
        try {
            worker.execute {
                try { scan(w, h, rowStride, n, tNs, side, def, pad, padFilled) } catch (t: Throwable) { android.util.Log.e("DunkBlockMatchup", "scan", t) } finally { busy.set(false) }
            }
        } catch (t: Throwable) { busy.set(false) }
    }

    private fun scan(w: Int, h: Int, rowStride: Int, n: Int, tNs: Long, side: Int, def: Boolean, pad: FloatArray?, padFilled: Boolean) {
        val t0 = System.nanoTime()
        val W = MatchupFinder.W; val H = MatchupFinder.H
        if (mapW != w) { mapW = w; for (x in 0 until W) xmap[x] = minOf(w - 1, ((x + 0.5) * w / W).toInt()) * 4 }
        var o = 0
        for (y in 0 until H) {
            val rb = minOf(h - 1, ((y + 0.5) * h / H).toInt()) * rowStride
            for (x in 0 until W) {
                val s = rb + xmap[x]
                if (s + 2 < n) { rgb[o] = rows[s]; rgb[o + 1] = rows[s + 1]; rgb[o + 2] = rows[s + 2] } else { rgb[o] = 0; rgb[o + 1] = 0; rgb[o + 2] = 0 }
                o += 3
            }
        }
        if (pad != null && padFilled) {
            val x0 = ((pad[0] * W).toInt() - 4).coerceIn(0, W); val x1 = ((pad[2] * W).toInt() + 5).coerceIn(0, W)
            val y0 = ((pad[1] * H).toInt() - 4).coerceIn(0, H); val y1 = ((pad[3] * H).toInt() + 5).coerceIn(0, H)
            for (y in y0 until y1) java.util.Arrays.fill(rgb, (y * W + x0) * 3, (y * W + x1) * 3, 0.toByte())
        }
        val s = finder.scan(rgb)
        if (s.meX >= 0f) synchronized(meT) {
            if (meN == meT.size) { for (i in 1 until meN) { meT[i - 1] = meT[i]; meXs[i - 1] = meXs[i]; meYs[i - 1] = meYs[i] }; meN-- }
            meT[meN] = tNs; meXs[meN] = s.meX; meYs[meN] = s.meY; meN++
        }
        val p = picker.step((tNs - startedNs) / 1e9, def, s)
        // What the follow rule would do is logged always; it reaches the joystick only with the experiment switched on.
        val deg = rule.step((tNs - startedNs) / 1e9, p)
        if (!deg.isNaN()) pushes++
        if (follow > 0) main.post { BlockAccessibilityService.instance?.inGame?.steer(deg, follow) }
        val us = ((System.nanoTime() - t0) / 1000).toInt()
        scans++; usTotal += us; if (us > usMax) usMax = us
        if (def) defScans++
        myCls = p.myCls; cls = p.cls
        if (p.src > 0) {
            withTarget++
            last = if (p.myX >= 0) {
                val dx = p.x - p.myX; val dy = p.y - p.myY
                Last(tNs, p.src, dx, dy, hypot(dx.toDouble(), dy * 1.6).toFloat())
            } else Last(tNs, p.src, Float.NaN, Float.NaN, -1f)
            // By-eye check of the colours on this capture: a colour screen every 8 s while a target is given.
            if (snaps < MAX_SNAPS && tNs - lastSnapNs > 8_000_000_000L) { lastSnapNs = tNs; snaps++; wantSnap = true }
        } else last = Last(tNs, 0, Float.NaN, Float.NaN, -1f)
        if (lines >= MAX_LINES) return
        lines++
        val sb = StringBuilder(256)
        sb.append(String.format(Locale.ROOT, "%.3f,%d,%d,%.1f,%.1f,%.4f,", (tNs - startedNs) / 1e9, side, if (def) 1 else 0, s.meX, s.meY, s.meConf))
        for ((i, q) in s.tags.withIndex()) { if (i > 0) sb.append(';'); sb.append(String.format(Locale.ROOT, "%s:%.1f:%.1f:%.3f", MatchupFinder.CLASSES[q.cls], q.x, q.y, q.grey)) }
        sb.append(',')
        for ((i, q) in s.fans.withIndex()) { if (i > 0) sb.append(';'); sb.append(String.format(Locale.ROOT, "%.1f:%.1f:%d:%.2f", q.x, q.y, q.area, q.rim)) }
        // btn_label: mpick.py reads "defence" from this column (0.20-0.27 = the block label); here it carries [def].
        sb.append(String.format(Locale.ROOT, ",%s,%s,%s,%d,%.1f,%.1f,%.1f,%.1f,%s,%d,%d,%s,%s,%d\n", if (def) "0.23" else "0.00",
            if (p.myCls >= 0) MatchupFinder.CLASSES[p.myCls] else "", if (p.cls >= 0) MatchupFinder.CLASSES[p.cls] else "", p.src,
            if (p.src > 0) p.x else -1f, if (p.src > 0) p.y else -1f, p.myX, p.myY, p.why, us, if (pad == null) 0 else if (padFilled) 1 else 2,
            if (deg.isNaN()) "" else String.format(Locale.ROOT, "%.0f", deg), rule.why, if (p.trk) 1 else 0))
        synchronized(log) { log.append(sb) }
    }

    /** Text for a trigger event: where the matchup stood relative to my player at the latest scan. */
    fun triggerText(tNs: Long): String {
        val l = last ?: return " mu=none"
        val age = (tNs - l.tNs) / 1_000_000L
        if (l.src == 0 || age > 400) return " mu=none age=$age"
        if (l.d < 0) return String.format(Locale.ROOT, " mu=%s me=none age=%d", if (l.src == 1) "tag" else "fan", age)
        // deg: 0 = the matchup is to the right of my player on the screen, 90 = above (further from the camera).
        return String.format(Locale.ROOT, " mu=%s d=%.0f dx=%.0f dy=%.0f deg=%.0f age=%d", if (l.src == 1) "tag" else "fan", l.d, l.dx, l.dy,
            Math.toDegrees(atan2(-l.dy * 1.6, l.dx.toDouble())), age)
    }

    // My ring of the last three scans (px of the 1170 x 540 picture; one wrong blob in three does not move the median).
    private val meT = LongArray(3); private val meXs = FloatArray(3); private val meYs = FloatArray(3); private var meN = 0

    /** [near]: 1 = the ball at ([x], [y]) (screen fractions) is next to my ring, 0 = it is not, -1 = no ring in the last 0.4 s. */
    class Near(val near: Int, val up: Float, val side: Float, val n: Int)

    /** core NearGate on the median of my ring over the last scans. Read by ShotWatcher.notMine (capture thread). */
    fun near(tNs: Long, x: Float, y: Float): Near {
        val xs = FloatArray(3); val ys = FloatArray(3); var n = 0
        synchronized(meT) { for (i in 0 until meN) if (tNs - meT[i] <= NEAR_AGE_NS) { xs[n] = meXs[i]; ys[n] = meYs[i]; n++ } }
        if (n == 0) return Near(-1, 0f, 0f, 0)
        java.util.Arrays.sort(xs, 0, n); java.util.Arrays.sort(ys, 0, n)
        val up = ys[n / 2] / MatchupFinder.H - y; val side = Math.abs(xs[n / 2] / MatchupFinder.W - x)
        return Near(if (dev.dunkblock.core.NearGate.near(up, side)) 1 else 0, up, side, n)
    }

    fun free() { worker.shutdownNow() }

    companion object {
        const val PERIOD_NS = 105_000_000L
        const val MAX_LINES = 14000
        const val MAX_SNAPS = 40
        const val NEAR_AGE_NS = 400_000_000L
    }
}
