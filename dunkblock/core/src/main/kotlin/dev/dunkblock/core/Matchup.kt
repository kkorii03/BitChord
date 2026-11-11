package dev.dunkblock.core

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Who is my matchup, and where are we both? (HANDOFF 0-4t .. 0-4v.)
 *
 * [MatchupFinder] = tools/analysis/mfollow.py (find_tags, body_grey, find_me) and mscan.py (find_fans), line for line,
 * on a 1170 x 540 RGB picture (3 bytes per pixel, R G B). [MatchupPicker] = tools/analysis/mpick.py (-fan glob,
 * -fantgt, -autome). MatchupTest replays both against the Python outputs.
 *
 * What the game draws (five colour match recordings of 11-11/11-12, all five matchups confirmed by the user):
 *  - under every player a name tag whose box has the colour of his position (SF, C, PF, SG, PG);
 *  - on defence a grey-red fan with a bright red rim on the floor at the feet of MY matchup;
 *  - my own player stands on a cyan ring.
 * Measured on those recordings: the picked target was the matchup's position in 2,754 of 2,754 scans; a target was
 * given in 30-65 % of the defence scans (tag off screen or covered the rest of the time).
 * NOT measured: the colours on the phone's own capture (1560 x 720, scaled by the compositor, our own overlays in
 * the picture). That is what the app's shadow log is for.
 */
class MatchupFinder {
    class Tag(val cls: Int, val x: Float, val y: Float, val grey: Float)
    class Fan(val x: Float, val y: Float, val area: Int, val rim: Float)
    class Scan(val tags: List<Tag>, val fans: List<Fan>, val meX: Float, val meY: Float, val meConf: Float)

    private val cls = ByteArray(GW * GH)
    private val cyan = BooleanArray(GW * GH)
    private val ii = IntArray((GW + 1) * (GH + 1))
    private val win = IntArray(GW * GH)
    private val fill = ByteArray(W * H)
    private val tmp = ByteArray(W * H)
    private val red = BooleanArray(W * H)
    private val lab = IntArray(W * H)
    private val stack = IntArray(W * H)
    private var ringA = ByteArray(0)
    private var ringB = ByteArray(0)

    fun scan(rgb: ByteArray): Scan {
        val count = IntArray(6); var nCyan = 0
        for (gy in 0 until GH) for (gx in 0 until GW) {
            val o = (gy * S * W + gx * S) * 3; val i = gy * GW + gx
            val r = rgb[o].toInt() and 0xFF; val g = rgb[o + 1].toInt() and 0xFF; val b = rgb[o + 2].toInt() and 0xFF
            val c = if (TAG_HUD[i]) 0 else when {
                r in 185..235 && g in 95..145 && b <= 50 -> 1
                r in 172..215 && g <= 45 && b <= 55 -> 2
                r in 10..90 && g in 198..235 && b in 85..140 -> 3
                r in 105..180 && g <= 45 && b in 150..225 -> 4
                r <= 70 && g in 110..160 && b in 160..212 -> 5
                else -> 0
            }
            cls[i] = c.toByte(); count[c]++
            val cy = g in 90..215 && b > 165 && b - r > 80 && !ME_HUD[i]
            cyan[i] = cy; if (cy) nCyan++
        }
        val tags = ArrayList<Tag>()
        for (c in 1..5) if (count[c] >= 16) findTags(rgb, c, tags)
        var meX = -1f; var meY = -1f; var meConf = 0f
        if (nCyan >= 8) {
            integral { cyan[it] }
            var best = -1e9
            for (a in ME_SIZES) {
                val hw = a / 2; val q = a / 4; val hh = maxOf(2, (0.39 * a * 0.35).toInt()); val cw = maxOf(1, a / 6); val ch = maxOf(1, hh / 2)
                val nSide = ((hw - q) * (2 * hh + 1)).toDouble(); val nMid = ((2 * cw + 1) * (2 * ch + 1)).toDouble()
                for (y in hh until GH - hh) for (x in hw until GW - hw) {
                    val l = box(x - hw, y - hh, x - q, y + hh + 1) / nSide
                    val r = box(x + q, y - hh, x + hw, y + hh + 1) / nSide
                    val v = sqrt(l * r) - box(x - cw, y - ch, x + cw + 1, y + ch + 1) / nMid
                    if (v > best) { best = v; meX = (x + 0.5f) * S; meY = (y + 0.5f) * S }
                }
            }
            meConf = best.toFloat()
        }
        return Scan(tags, findFans(rgb), meX, meY, meConf)
    }

    private inline fun integral(on: (Int) -> Boolean) {
        val st = GW + 1
        for (y in 0 until GH) {
            var row = 0
            for (x in 0 until GW) {
                if (on(y * GW + x)) row++
                ii[(y + 1) * st + x + 1] = ii[y * st + x + 1] + row
            }
        }
    }

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val st = GW + 1
        return ii[y1 * st + x1] - ii[y0 * st + x1] - ii[y1 * st + x0] + ii[y0 * st + x0]
    }

    /** Up to three 8 x 4 cell windows with >= 16 cells of the class colour; position = centre of those cells. */
    private fun findTags(rgb: ByteArray, c: Int, out: ArrayList<Tag>) {
        val cb = c.toByte()
        integral { cls[it] == cb }
        val wn = GW - 7; val hn = GH - 3
        for (y in 0 until hn) for (x in 0 until wn) win[y * wn + x] = box(x, y, x + 8, y + 4)
        repeat(3) {
            var best = -1; var bj = 0
            for (j in 0 until wn * hn) if (win[j] > best) { best = win[j]; bj = j }
            if (best < 16) return
            val y = bj / wn; val x = bj % wn
            var sx = 0; var sy = 0; var n = 0
            for (yy in 0 until 4) for (xx in 0 until 8) if (cls[(y + yy) * GW + x + xx] == cb) { sx += xx; sy += yy; n++ }
            val tx = ((x + sx.toDouble() / n + 0.5) * S).toFloat(); val ty = ((y + sy.toDouble() / n + 0.5) * S).toFloat()
            out.add(Tag(c - 1, tx, ty, bodyGrey(rgb, tx, ty)))
            for (yy in maxOf(0, y - 6) until minOf(hn, y + 7)) for (xx in maxOf(0, x - 12) until minOf(wn, x + 13)) win[yy * wn + xx] = 0
        }
    }

    /** Share of grey-dark cells in the box above the tag (opponents are drawn grey with a red outline). */
    private fun bodyGrey(rgb: ByteArray, tx: Float, ty: Float): Float {
        var x0 = (tx / S).toInt() - 4; var x1 = x0 + 34; var y1 = (ty / S).toInt() - 20; var y0 = y1 - 40
        x0 = maxOf(0, x0); y0 = maxOf(0, y0); x1 = minOf(GW, x1); y1 = minOf(GH, y1)
        if (x1 - x0 < 8 || y1 - y0 < 8) return -1f
        var n = 0
        for (gy in y0 until y1) for (gx in x0 until x1) {
            val o = (gy * S * W + gx * S) * 3
            val r = rgb[o].toInt() and 0xFF; val g = rgb[o + 1].toInt() and 0xFF; val b = rgb[o + 2].toInt() and 0xFF
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx - mn <= 22 && mx <= 135) n++
        }
        return n.toFloat() / ((x1 - x0) * (y1 - y0))
    }

    /**
     * mscan.find_fans: areas of the fan fill colour (court teal under translucent red-grey), opened 3 x 3, lying on the
     * floor (wider than tall), whose surroundings (3 px) are to >= 22 % bright red. Best (area * rim) first, at most 6.
     */
    private fun findFans(rgb: ByteArray): List<Fan> {
        val y0 = FAN_TOP
        for (y in y0 until H) for (x in 0 until W) {
            val i = y * W + x; val o = i * 3
            val r = rgb[o].toInt() and 0xFF; val g = rgb[o + 1].toInt() and 0xFF; val b = rgb[o + 2].toInt() and 0xFF
            red[i] = r >= 125 && g <= 45 && b <= 90
            fill[i] = if (r in 62..125 && g in 60..105 && b in 78..125 && r - g <= 28 && g - r <= 28 && b - g in 0..40 && !FAN_HUD[i]) 1 else 0
            lab[i] = 0
        }
        // 3 x 3 opening (pixels outside the picture are ignored, as in OpenCV); rows above FAN_TOP + 1 are HUD = empty.
        for (y in y0 until H) for (x in 0 until W) {
            val i = y * W + x
            tmp[i] = if (fill[i].toInt() == 1 && (x == 0 || fill[i - 1].toInt() == 1) && (x == W - 1 || fill[i + 1].toInt() == 1)) 1 else 0
        }
        for (y in y0 until H) for (x in 0 until W) {
            val i = y * W + x
            fill[i] = if (tmp[i].toInt() == 1 && (y == y0 || tmp[i - W].toInt() == 1) && (y == H - 1 || tmp[i + W].toInt() == 1)) 1 else 0
        }
        for (y in y0 until H) for (x in 0 until W) {
            val i = y * W + x
            tmp[i] = if (fill[i].toInt() == 1 || (x > 0 && fill[i - 1].toInt() == 1) || (x < W - 1 && fill[i + 1].toInt() == 1)) 1 else 0
        }
        for (y in y0 until H) for (x in 0 until W) {
            val i = y * W + x
            fill[i] = if (tmp[i].toInt() == 1 || (y > y0 && tmp[i - W].toInt() == 1) || (y < H - 1 && tmp[i + W].toInt() == 1)) 1 else 0
        }
        val out = ArrayList<Fan>(); val score = ArrayList<Double>()
        var next = 0
        for (start in y0 * W until W * H) {
            if (fill[start].toInt() != 1 || lab[start] != 0) continue
            next++
            var sp = 0; stack[sp++] = start; lab[start] = next
            var a = 0; var sx = 0L; var sy = 0L; var xa = W; var xb = -1; var ya = H; var yb = -1
            while (sp > 0) {
                val i = stack[--sp]; val x = i % W; val y = i / W
                a++; sx += x; sy += y
                if (x < xa) xa = x; if (x > xb) xb = x; if (y < ya) ya = y; if (y > yb) yb = y
                for (dy in -1..1) {
                    val yy = y + dy; if (yy < y0 || yy >= H) continue
                    for (dx in -1..1) {
                        val xx = x + dx; if (xx < 0 || xx >= W) continue
                        val j = yy * W + xx
                        if (fill[j].toInt() == 1 && lab[j] == 0) { lab[j] = next; stack[sp++] = j }
                    }
                }
            }
            val w = xb - xa + 1; val h = yb - ya + 1
            if (a < 120 || a > 4500 || w < 1.2 * h || h < 6) continue
            // ring = within 3 px (Chebyshev) of the area, inside the box grown by 3, minus the area itself
            val cx0 = maxOf(0, xa - 3); val cy0 = maxOf(0, ya - 3); val cx1 = minOf(W, xb + 4); val cy1 = minOf(H, yb + 4)
            val cw = cx1 - cx0; val ch = cy1 - cy0
            if (ringA.size < cw * ch) { ringA = ByteArray(cw * ch); ringB = ByteArray(cw * ch) }
            for (y in 0 until ch) for (x in 0 until cw) {
                var on = false
                for (xx in maxOf(0, x - 3)..minOf(cw - 1, x + 3)) if (lab[(cy0 + y) * W + cx0 + xx] == next) { on = true; break }
                ringA[y * cw + x] = if (on) 1 else 0
            }
            var k = 0; var kr = 0
            for (y in 0 until ch) for (x in 0 until cw) {
                var on = false
                for (yy in maxOf(0, y - 3)..minOf(ch - 1, y + 3)) if (ringA[yy * cw + x].toInt() == 1) { on = true; break }
                val i = (cy0 + y) * W + cx0 + x
                if (on && lab[i] != next) { k++; if (red[i]) kr++ }
            }
            if (k == 0) continue
            val rim = kr.toDouble() / k
            if (rim < 0.22 || a < 0.45 * w * h * 0.5) continue
            val s = a * rim
            var at = 0
            while (at < score.size && score[at] >= s) at++
            score.add(at, s); out.add(at, Fan((sx.toDouble() / a).toFloat(), (sy.toDouble() / a).toFloat(), a, rim.toFloat()))
        }
        return if (out.size > 6) out.subList(0, 6) else out
    }

    companion object {
        const val W = 1170
        const val H = 540
        const val S = 3
        const val GW = W / S
        const val GH = H / S
        val CLASSES = arrayOf("SF", "C", "PF", "SG", "PG")
        private val ME_SIZES = intArrayOf(16, 20, 24, 28, 32)
        /** Rows above this are HUD for the fan finder (y / 540 < 0.30); a few more are kept for the red rim test. */
        private const val FAN_TOP = 150

        private val TAG_HUD = BooleanArray(GW * GH) { i ->
            val gx = i % GW; val gy = i / GW
            (gx < 0.25 * GW && gy < 0.30 * GH) || gy < 0.20 * GH || (gx > 0.72 * GW && gy > 0.40 * GH)
        }
        private val ME_HUD = BooleanArray(GW * GH) { i ->
            val gx = i % GW; val gy = i / GW
            TAG_HUD[i] || (gx < 0.26 * GW && gy > 0.52 * GH && gy < 0.95 * GH) || gy < 0.30 * GH
        }
        private val FAN_HUD = BooleanArray(W * H) { i ->
            val fx = (i % W) / W.toDouble(); val fy = (i / W) / H.toDouble()
            (fx < 0.25 && fy < 0.30) || (fx > 0.60 && fy < 0.14) || (fx > 0.70 && fy > 0.36) || (fx < 0.26 && fy > 0.52 && fy < 0.95) || fy < 0.30
        }
    }
}

/**
 * mpick.py as a causal state machine: one [step] per scan (about 10 Hz).
 *  my class = the class of the tag under my cyan ring (ring conf 0.2-0.38, within 45 px of ring - (31, -24)), by
 *             majority over the last [ME_KEEP] such scans (>= [ME_MIN], >= 70 %); nothing is picked before that.
 *             (All five recordings were played with an SF; in each the vote was right from 30 s on.)
 *  owner    = the tag (not mine) with a red-rimmed fan in its feet window (tag + (-30..150, -92..-28)); fans within
 *             70 px of my ring are skipped (the game draws a wedge at MY feet too)
 *  class    = the position that owned the fan most often in the last 60 s (>= 6 scans, >= 70 %)
 *  target   = the tag of that class (two on screen -> the grey-dark one if exactly one is, else none). Tag not on
 *             screen but a fan visible away from me and under no other tag -> the fan itself (designed on the five
 *             recordings, 24 of 24 by eye; needs new recordings to count as tested).
 *  my feet  = the tag under my ring, or - when the matchup has another position - the only tag of my class.
 * All positions are feet positions in px of the 1170 x 540 picture.
 */
class MatchupPicker(private val fixedMyCls: Int = -1) {
    class Pick {
        var defence = false
        /** 0 none, 1 the matchup's tag, 2 the matchup's fan (tag not on screen). */
        var src = 0
        var x = -1f; var y = -1f
        var myX = -1f; var myY = -1f
        var cls = -1; var myCls = -1
        var owner = -1
        var why = ""
    }

    private val voteT = ArrayList<Double>(); private val voteC = ArrayList<Int>()
    private val meVotes = IntArray(ME_KEEP) { -1 }; private var mePos = 0; private var meN = 0
    var myCls = fixedMyCls; private set

    fun step(t: Double, defence: Boolean, s: MatchupFinder.Scan): Pick {
        val p = Pick(); p.defence = defence
        val ring = s.meConf >= 0.2f && s.meConf < 0.38f
        if (fixedMyCls < 0) {
            if (ring) {
                var bd = 1e9; var bc = -1
                for (q in s.tags) { val d = hypot((q.x + 31 - s.meX).toDouble(), (q.y - 24 - s.meY) * 1.6); if (d < bd) { bd = d; bc = q.cls } }
                if (bd <= 45) { meVotes[mePos] = bc; mePos = (mePos + 1) % ME_KEEP; if (meN < ME_KEEP) meN++ }
            }
            myCls = -1
            if (meN >= ME_MIN) {
                val cnt = IntArray(5); for (i in 0 until meN) cnt[meVotes[i]]++
                val c = cnt.indices.maxByOrNull { cnt[it] }!!
                if (cnt[c] >= 0.7 * meN) myCls = c
            }
        }
        val me = myCls; p.myCls = me
        if (me < 0) { p.why = "my class unknown"; return p }
        var mine: MatchupFinder.Tag? = null
        if (ring) {
            var bd = 1e9
            for (q in s.tags) if (q.cls == me) { val d = hypot((q.x + 31 - s.meX).toDouble(), (q.y - 24 - s.meY) * 1.6); if (d < bd) { bd = d; mine = q } }
            if (bd > 45) mine = null
        }
        val opp = s.tags.filter { it !== mine && (it.cls != me || ring) }
        var o: MatchupFinder.Tag? = null
        if (defence) {
            var best = -1.0
            for (f in s.fans) {
                if (ring && hypot((f.x - s.meX).toDouble(), (f.y - s.meY) * 1.6) < 70) continue
                var bd = 1e9; var bq: MatchupFinder.Tag? = null
                for (q in opp) if (f.x - q.x >= -30 && f.x - q.x <= 150 && f.y - q.y >= -92 && f.y - q.y <= -28) {
                    val d = hypot((q.x + 45 - f.x).toDouble(), (q.y - 52 - f.y) * 1.6); if (d < bd) { bd = d; bq = q }
                }
                if (bq != null && f.area * f.rim.toDouble() > best) { best = f.area * f.rim.toDouble(); o = bq }
            }
        }
        if (o != null) { voteT.add(t); voteC.add(o.cls); p.owner = o.cls }
        while (voteT.isNotEmpty() && t - voteT[0] > KEEP_S) { voteT.removeAt(0); voteC.removeAt(0) }
        var cls = -1
        if (voteC.size >= MIN_VOTES) {
            val cnt = IntArray(5); for (c in voteC) cnt[c]++
            val c = cnt.indices.maxByOrNull { cnt[it] }!!
            if (cnt[c] >= SHARE * voteC.size) cls = c
        }
        p.cls = cls
        val mt = s.tags.filter { it.cls == me }
        val my = mine ?: if (cls >= 0 && cls != me && mt.size == 1) mt[0] else null
        if (my != null) { p.myX = my.x + 45; p.myY = my.y - 52 }
        if (!defence) { p.why = "not defence"; return p }
        if (cls < 0) { p.why = "no class"; return p }
        var c = opp.filter { it.cls == cls }
        if (c.size > 1) { val g = c.filter { it.grey >= GREY }; if (g.size == 1) c = g }
        when {
            c.isEmpty() -> {
                var best = -1.0; var bf: MatchupFinder.Fan? = null
                if (my != null) for (f in s.fans) {
                    if (hypot((f.x - my.x - 45).toDouble(), (f.y - my.y + 52) * 1.6) < 70) continue
                    if (s.tags.any { f.x - it.x >= -30 && f.x - it.x <= 150 && f.y - it.y >= -92 && f.y - it.y <= -28 }) continue
                    if (f.area * f.rim.toDouble() > best) { best = f.area * f.rim.toDouble(); bf = f }
                }
                if (bf != null) { p.src = 2; p.x = bf.x; p.y = bf.y } else p.why = "class not on screen"
            }
            c.size > 1 -> p.why = "two of the class"
            else -> { p.src = 1; p.x = c[0].x + 45; p.y = c[0].y - 52 }
        }
        return p
    }

    companion object {
        const val KEEP_S = 60.0
        const val MIN_VOTES = 6
        const val SHARE = 0.7
        const val GREY = 0.15f
        const val ME_KEEP = 200
        const val ME_MIN = 15
    }
}
