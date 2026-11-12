package dev.dunkblock.core

import kotlin.math.abs
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
 * Is this shot next to MY player? (HANDOFF 0-4z10.) No identity: only where the ball is against my own ring.
 *  up   = (my ring y - ball y) / screen height: how far above my feet the ball is on the screen
 *  side = |my ring x - ball x| / screen width
 * A shooter I am standing next to has the ball about one body above my feet. A dunk or lay-up at the rim while I guard
 * somebody further out is much higher above my ring (the rim is further from the camera = higher on the screen).
 * Designed on the 12 auto presses of the phone match of 11-12 11:13 (vc1100, matchup Curry; who shot was looked up by
 * eye): my matchup or a shooter right next to me up 0.20-0.35, somebody else's rim play 0.39-0.49. [MAX_UP] = the
 * middle. Applied unchanged to the 8 presses of the match of 11-12 08:30 (vc1092, matchup Paul George): my matchup
 * 0.19-0.33 (6), somebody else 0.41-0.46 (2). 20 presses, two matches: small. Never run on the phone.
 * [MAX_SIDE] is loose (the widest "mine" was 0.115); it only stops a ball far to the side.
 */
object NearGate {
    const val MAX_UP = 0.36f
    const val MAX_SIDE = 0.14f
    fun near(up: Float, side: Float): Boolean = up <= MAX_UP && side <= MAX_SIDE
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
 *  [track]  = identity track for a matchup of MY position (two tags of one colour; tools/analysis/mtrack.py -track is
 *             the twin). When no tag of my class sits under my ring, the 1-2 tags of my class are told apart by where
 *             "mine" and "his" were at most [AGE] s ago, and only within [CAP] s of the last ring-confirmed "mine".
 *             One tag: mine if within reach of my last place and [MARGIN] px closer to it than to his; his only when
 *             my own place is also known. Two tags: the cheaper pairing, if each is within reach, "mine" is not the
 *             greyer one ([GVETO]) and the swapped pairing is [MARGIN] px worse. Designed on one phone match and
 *             recording C (both SF v SF, both looked at): HANDOFF 0-4z6; no independent test yet.
 *  [strict] = believe only what the game itself marks (tools/analysis/mstrict.py is the twin; HANDOFF 0-4z15). The tag
 *             colour says the POSITION only, and a team mate or an opponent can share it. Phone match of 11-12 11:13
 *             (my SF against Curry = PG, my team mate LaMelo Ball = PG too): with Curry's tag hidden the rule above
 *             took LaMelo's tag as the target for seconds (frames looked at), and an opponent SF as "me".
 *             me     : the tag under my ring, its track, or the ring alone when it continues my last place; never
 *                      "the only tag of my class".
 *             target : a tag of the matchup's position with the fan at its feet in this scan; without a fan in this
 *                      scan only the tag that continues that confirmed one (within reach of its last place, at most
 *                      [CONF_S] after the last fan); a fan under no tag only when a fan stood within [FAN_NEAR] px in
 *                      the scan before. Fans of a row of three or more (+-[ROW] px) are scenery.
 *             Replayed on that match and on the one of 11-12 08:30 (not used for the design): target jumps 46 -> 0
 *             and 5 -> 0, tag targets away from a visible fan 120 -> 25 and 36 -> 17, defence scans with a target
 *             45 -> 36 % and 42 -> 32 %. Never run on the phone.
 * All positions are feet positions in px of the 1170 x 540 picture.
 */
class MatchupPicker(private val fixedMyCls: Int = -1, private val track: Boolean = false, private val strict: Boolean = false) {
    class Pick {
        var defence = false
        /** 0 none, 1 the matchup's tag, 2 the matchup's fan (tag not on screen). */
        var src = 0
        var x = -1f; var y = -1f
        var myX = -1f; var myY = -1f
        var cls = -1; var myCls = -1
        var owner = -1
        var why = ""
        /** The identity track named my tag or his in this scan (no ring on a tag of my class). */
        var trk = false
    }

    private val voteT = ArrayList<Double>(); private val voteC = ArrayList<Int>()
    private val meVotes = IntArray(ME_KEEP) { -1 }; private var mePos = 0; private var meN = 0
    var myCls = fixedMyCls; private set
    // identity track: the last place of my tag and of the matchup's tag when it has my class, and the last ring-confirmed time
    private var lmX = 0f; private var lmY = 0f; private var lmT = -1e9
    private var lhX = 0f; private var lhY = 0f; private var lhT = -1e9
    private var ringT = -1e9
    // strict: the last place of the fan-confirmed target (tag coordinates), when it was seen, when a fan last confirmed it
    private var aX = 0f; private var aY = 0f; private var aT = -1e9; private var aConf = -1e9
    private var prevFans: List<MatchupFinder.Fan> = emptyList(); private var prevT = -1e9

    private fun dist(q: MatchupFinder.Tag, x: Float, y: Float) = hypot((q.x - x).toDouble(), (q.y - y) * 1.6)
    private fun reach(q: MatchupFinder.Tag, x: Float, y: Float, age: Double) = age <= AGE && dist(q, x, y) <= G0 + GV * age
    /** The fan lies in the feet window of the tag. */
    private fun owns(q: MatchupFinder.Tag, f: MatchupFinder.Fan) = f.x - q.x >= -30 && f.x - q.x <= 150 && f.y - q.y >= -92 && f.y - q.y <= -28

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
        val pf = if (t - prevT <= FAN_GAP) prevFans else emptyList()
        prevFans = s.fans; prevT = t
        if (me < 0) { p.why = "my class unknown"; return p }
        var mine: MatchupFinder.Tag? = null
        if (ring) {
            var bd = 1e9
            for (q in s.tags) if (q.cls == me) { val d = hypot((q.x + 31 - s.meX).toDouble(), (q.y - 24 - s.meY) * 1.6); if (d < bd) { bd = d; mine = q } }
            if (bd > 45) mine = null
        }
        val mt = s.tags.filter { it.cls == me }
        var his: MatchupFinder.Tag? = null
        var mineByTrack = false
        if (mine != null) ringT = t
        else if (track && t - ringT <= CAP && (mt.size == 1 || mt.size == 2)) {
            val rm = t - lmT <= AGE; val rh = t - lhT <= AGE
            if (mt.size == 1) {
                val q = mt[0]
                val dm = if (rm) dist(q, lmX, lmY) else 1e9; val dh = if (rh) dist(q, lhX, lhY) else 1e9
                if (rm && reach(q, lmX, lmY, t - lmT) && dm + MARGIN <= dh) mine = q
                else if (rh && rm && reach(q, lhX, lhY, t - lhT) && dh + MARGIN <= dm) his = q
            } else if (rm || rh) {
                fun cost(m: MatchupFinder.Tag, h: MatchupFinder.Tag) = (if (rm) dist(m, lmX, lmY) else 0.0) + (if (rh) dist(h, lhX, lhY) else 0.0)
                fun ok(m: MatchupFinder.Tag, h: MatchupFinder.Tag) =
                    (!rm || reach(m, lmX, lmY, t - lmT)) && (!rh || reach(h, lhX, lhY, t - lhT)) && m.grey - h.grey < GVETO
                val c0 = cost(mt[0], mt[1]); val c1 = cost(mt[1], mt[0])
                val m = if (c0 <= c1) mt[0] else mt[1]; val h = if (c0 <= c1) mt[1] else mt[0]
                if (ok(m, h) && minOf(c0, c1) + MARGIN <= maxOf(c0, c1)) { mine = m; his = h }
            }
            mineByTrack = mine != null
            p.trk = mine != null || his != null
        }
        val opp = s.tags.filter { it !== mine && (it.cls != me || ring || it === his || mineByTrack) }
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
        val my = if (strict) {
            // The ring alone (its tag hidden) counts only where my tag would continue my last place.
            mine ?: if (ring) MatchupFinder.Tag(me, s.meX - 31, s.meY + 24, 0f).takeIf { reach(it, lmX, lmY, t - lmT) } else null
        } else mine ?: if (cls >= 0 && cls != me && mt.size == 1 && mt[0] !== his) mt[0] else null
        if (my != null) { p.myX = my.x + 45; p.myY = my.y - 52; lmX = my.x; lmY = my.y; lmT = t }
        if (!defence) { p.why = "not defence"; return p }
        if (cls < 0) { p.why = "no class"; return p }
        var c = opp.filter { it.cls == cls }
        if (strict) {
            val fs = s.fans.filter { f ->
                s.fans.count { abs(it.y - f.y) <= ROW } < 3 &&
                    !(ring && hypot((f.x - s.meX).toDouble(), (f.y - s.meY) * 1.6) < 70) &&
                    !(my != null && hypot((f.x - my.x - 45).toDouble(), (f.y - my.y + 52) * 1.6) < 70)
            }
            var tgt: MatchupFinder.Tag? = null; var best = -1.0
            for (q in c) for (f in fs) if (owns(q, f) && f.area * f.rim.toDouble() > best) { best = f.area * f.rim.toDouble(); tgt = q }
            if (tgt != null) { aConf = t; p.why = "fan" }
            else if (t - aConf <= CONF_S && t - aT <= AGE) {
                var d0 = 1e9; var d1 = 1e9; var q0: MatchupFinder.Tag? = null
                for (q in c) if (reach(q, aX, aY, t - aT)) { val d = dist(q, aX, aY); if (d < d0) { d1 = d0; d0 = d; q0 = q } else if (d < d1) d1 = d }
                if (q0 != null && d0 + MARGIN <= d1) { tgt = q0; p.why = "track" }
            }
            if (tgt != null) {
                p.src = 1; p.x = tgt.x + 45; p.y = tgt.y - 52; aX = tgt.x; aY = tgt.y; aT = t
                if (tgt.cls == me) { lhX = tgt.x; lhY = tgt.y; lhT = t }
                return p
            }
            var bf: MatchupFinder.Fan? = null; best = -1.0
            for (f in fs) {
                if (s.tags.any { owns(it, f) }) continue
                if (pf.none { hypot((f.x - it.x).toDouble(), (f.y - it.y) * 1.6) <= FAN_NEAR }) continue
                if (f.area * f.rim.toDouble() > best) { best = f.area * f.rim.toDouble(); bf = f }
            }
            if (bf != null) { p.src = 2; p.x = bf.x; p.y = bf.y; p.why = "fan alone"; aX = bf.x - 45; aY = bf.y + 52; aT = t; aConf = t }
            else p.why = if (c.isEmpty()) "class not on screen" else "not confirmed"
            return p
        }
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
            else -> {
                p.src = 1; p.x = c[0].x + 45; p.y = c[0].y - 52
                if (c[0].cls == me) { lhX = c[0].x; lhY = c[0].y; lhT = t }
            }
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
        const val AGE = 0.6
        const val CAP = 2.0
        const val MARGIN = 25.0
        const val G0 = 12.0
        const val GV = 250.0
        const val GVETO = 0.08f
        const val CONF_S = 2.0
        const val FAN_NEAR = 40.0
        const val FAN_GAP = 0.35
        const val ROW = 8f
    }
}
