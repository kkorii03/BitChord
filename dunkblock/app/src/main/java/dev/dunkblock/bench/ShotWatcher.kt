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
    private var sat = IntArray(0); private var sat2 = IntArray(0); private var lowRed = 0
    private var aspect = 2.17f

    // last accepted disc positions (x / W, y / H)
    private val histN = 64
    private val hT = LongArray(histN); private val hX = FloatArray(histN); private val hY = FloatArray(histN)
    private var hPos = 0; private var hCount = 0
    private var lastFastNs = 0L
    private var shotClips = 0
    /** Set when a colour screen of this moment would help; FramePipeline clears it after saving one. */
    @Volatile var snapNow = false
    /** Record-only matchup log (matchup.csv); never read by the press rules. */
    // Holding '수비' (follow 5) changes the look of that slot, which is the beat block's own clock (rep start and
    // release = slot2 luma): 16:36 practice found 2 releases in 10 reps while held (plus one fake at the let-go), 4 in 4 before. Beat block wins.
    val matchup = MatchupShadow(startedNs, if (cfg.follow == 5 && cfg.sweepPress) 0 else cfg.follow)

    @Volatile var scans = 0; private set
    @Volatile var defScans = 0; private set
    @Volatile var offScans = 0; private set
    @Volatile var discScans = 0; private set
    @Volatile var fastEvents = 0; private set
    @Volatile var shotCandidates = 0; private set
    private var usTotal = 0L
    private var usMax = 0

    private val log = StringBuilder(1 shl 16).append("t_ns,t_s,side,disc,x,y,fill,second,dark,orange,slot2_luma,slot2_dark,us,ring_y,sec_x,sec_y,col_x,col_y,col_fill,top_dy,yc,yc_n,d2,x2,y2,ring2,n2,cand2,low2\n")
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
        findDisc2(buf, w, h, rowStride)
        rise2(tNs, w, h)
        // One colour screen when the two finders disagree on defence (at most 25 of the 150 trigger screens, 4 s apart).
        if (side == SIDE_DEF && found != d2 && d2Snaps < 25 && tNs - lastD2SnapNs > 4_000_000_000L) {
            lastD2SnapNs = tNs; d2Snaps++; snapNow = true
        }
        readSlot2(buf, w, h, rowStride, blockFx, blockFy)
        scans++
        if (side == SIDE_DEF) { defScans++; lastDefNs = tNs } else if (side == SIDE_OFF) { offScans++; lastOffNs = tNs }
        matchup.onFrame(buf, w, h, rowStride, tNs, side, tNs - lastDefNs < 300_000_000L && side != SIDE_OFF)
        if (matchup.wantSnap) { matchup.wantSnap = false; snapNow = true }
        sweep(tNs)
        skillDark(tNs)
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
            log.append(tNs).append(',').append(String.format(Locale.ROOT, "%.3f,%s,%d,%.4f,%.4f,%.2f,%.2f,%.3f,%.3f,%.1f,%.3f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%.4f,%.5f,%d,%d,%.4f,%.4f,%.2f,%d,%d,%d\n",
                (tNs - startedNs) / 1e9, sideName(side), if (found) 1 else 0, discX, discY, fill, second, sideDark, sideOrange, slot2Luma, slot2Dark, us, ringY, secX, secY,
                colX, colY, colFill, topDy, ycY, ycN, if (d2) 1 else 0, d2X, d2Y, d2Ring, d2N, d2Cand, lowRed))
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

    // ------------------------------------------------------------------ sweep (practice court, option ⑩)

    private val swL = FloatArray(16); private var swN = 0
    private var swAway = 0; private var swIn = 0; private var swFirstNs = 0L
    private var swRepNs = 0L; private var swRepT = 0L; private var swDueNs = 0L; private var swArmed = false
    private var swLeadMs = 0; private var swSeq = 0; private var swRelNs = 0L; private var swPressNs = 0L
    private var swRepTPrev = 0L; private var swRelSinceRep = false
    @Volatile var sweepReps = 0; private set
    @Volatile var sweepReleases = 0; private set
    @Volatile var sweepPresses = 0; private set

    /**
     * Calibration for the PRACTICE COURT only (defence practice repeats the same shot every ~7 s). It needs no shot rule:
     *  - rep start = the "수비" slot back at its normal look (slot2 luma 100..110.5 for 3 scans after >= 10 scans outside
     *    96..112): the opponent has the ball again;
     *  - release = the box-out step of the same slot (tools/analysis/boxstep.py);
     *  - repT = release - rep start of the last shot. Replayed with tools/analysis/sweepsim.py on the vc1056 and vc1035
     *    practice bundles: repT is constant per opponent (LaMelo 1.61-1.66 s, Curry 2.15-2.22 s, others 1.63-1.84 s),
     *    so "rep start + last repT" hits the next release within 25 ms (median), 50 ms (90 %).
     * Both are always logged (sweep_rep / sweep_rel). With [Bench.Config.sweepPress] the block is pressed at
     * rep start + repT - lead, lead stepping through [SWEEP_LEADS], and every other press is held back ([press]); the
     * trigger clip then shows whether the block landed (tools/analysis/shots.py), which gives the block window in ms
     * before the release for that opponent. It also presses on the game's own demonstration shots and ~3 s after a
     * block (an extra "normal" look there); shots.py tells those apart. Never meant for matches: there is no rep clock.
     */
    private fun sweep(tNs: Long) {
        val l = slot2Luma
        if (l < 96f || l > 112f) { swAway++; swIn = 0 }
        else if (l in 100f..110.5f) {
            if (swIn == 0) swFirstNs = tNs
            if (++swIn == 3) {
                if (swAway >= 10) {
                    swRepNs = swFirstNs; sweepReps++
                    // Beat block (fixed lead): only on a steady beat = the last rep ended in a release and the last two
                    // repT agree within SWEEP_TOL_NS (25/50 ms median/90 % per opponent on vc1056/vc1035). 17:00 match
                    // with vc1128: 18 beat presses, none before a shot; guarded replay: 3 presses over 7 match bundles,
                    // practice 161312 12 of 15 in the window (HANDOFF 0-4z30).
                    val steady = cfg.sweepLeadMs == 0 || (swRelSinceRep && swRepTPrev > 0L && Math.abs(swRepT - swRepTPrev) < SWEEP_TOL_NS)
                    val plan = cfg.sweepPress && cfg.shotBlock && swRepT > 0L && side == SIDE_DEF && steady
                    swRelSinceRep = false
                    if (plan) {
                        swLeadMs = if (cfg.sweepLeadMs > 0) cfg.sweepLeadMs else SWEEP_LEADS[swSeq++ % SWEEP_LEADS.size]
                        swDueNs = swRepNs + swRepT - swLeadMs * 1_000_000L; swArmed = false
                    }
                    event(tNs, "sweep_rep", 0f, 0f, 0f, String.format(Locale.ROOT, "repT=%d lead=%d", swRepT / 1_000_000L, if (plan) swLeadMs else -1))
                }
                swAway = 0
            }
        } else swIn = 0
        System.arraycopy(swL, 1, swL, 0, 15); swL[15] = l
        if (++swN >= 16) {
            var mn = swL[0]; var mx = swL[0]
            for (i in 1..12) { mn = minOf(mn, swL[i]); mx = maxOf(mx, swL[i]) }
            val b = swL[12]; val d = l - b
            if (mx - mn < 1.5f && Math.abs(swL[14] - b) < 5f && d in 6f..12f && b in 98f..112f && tNs - swRelNs > 300_000_000L) {
                swRelNs = tNs; sweepReleases++
                val rt = tNs - swRepNs
                val got = swRepNs != 0L && rt in 1_000_000_000L..3_500_000_000L
                if (got) { swRepTPrev = swRepT; swRepT = rt }
                swRelSinceRep = got
                // A shot the sweep did not press on (the first one against a new opponent = the game's demonstration):
                // keep a clip of it anyway, it shows when the game itself presses (tools/analysis/btnlit.py).
                if (cfg.sweepPress && (swPressNs == 0L || tNs - swPressNs > 1_500_000_000L) && shotClips < MAX_SHOT_CLIPS) {
                    shotClips++; Bench.clipRequester?.invoke("trig_rel", 1.0, 1.2)
                }
                event(tNs, "sweep_rel", 0f, 0f, 0f, String.format(Locale.ROOT, "repT=%d lead=%d press_to_rel=%d", if (got) rt / 1_000_000L else -1L,
                    swLeadMs, if (swPressNs != 0L && tNs - swPressNs in 0L..1_500_000_000L) (tNs - swPressNs) / 1_000_000L else -1L))
            }
        }
        if (swDueNs == 0L) return
        val live = cfg.autoBlockLive
        // Open the block tile 60 ms ahead, so the tap leaves at the planned moment (no pass-through wait in fireBlock).
        if (!swArmed && tNs + 60_000_000L >= swDueNs) { swArmed = true; if (live) main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) } }
        if (tNs + 8_000_000L < swDueNs) return
        val rem = ((swDueNs - tNs) / 1_000_000L).coerceAtLeast(0L)
        swDueNs = 0L
        if (side == SIDE_OFF || tNs - lastDefNs > 1_000_000_000L) { event(tNs, "sweep_dropped", 0f, 0f, 0f, "not_def"); return }
        val beat = cfg.sweepLeadMs > 0
        // Beat block runs beside the shot rules: no second jump right after one of theirs, and lock them out after ours.
        if (beat && tNs - lastPressNs < 1_000_000_000L) { event(tNs, "sweep_dropped", 0f, 0f, 0f, "pressed"); return }
        if (beat) { lastPressNs = tNs; lastShotFireNs = tNs }
        shotTriggers++; sweepPresses++
        swPressNs = tNs + rem * 1_000_000L
        if (shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_sweep", 0.6, 1.8) }
        val d = String.format(Locale.ROOT, "sweep lead=%d repT=%d rem=%d", swLeadMs, swRepT / 1_000_000L, rem)
        event(tNs, "shot_trigger", 0f, 0f, 0f, d)
        snapNow = true
        press(tNs, live, d, cfg.shotDelayMs - rem)
    }

    // ------------------------------------------------------------------ signature-skill dark (vc1067)

    private val dkL = FloatArray(DK_N); private val dkT = LongArray(DK_N); private var dkN = 0
    private val dkS = FloatArray(DK_N); private var dkJump = false; private var dkScrBase = 0f
    /** Mean luma of the whole frame (FramePipeline sets it before every [onFrame]); -1 = not known. */
    @Volatile var screenLuma = -1f
    private var dkDark = false; private var dkBase = 0f; private var dkLow = 0f; private var dkOnNs = 0L; private var dkEndNs = 0L
    private var dkPeak = 0f; private var dkPeakNs = 0L; private var dkNth = 0
    private var dkDueNs = 0L; private var dkArmed = false; private var dkDelayMs = 0; private var dkSeq = 0; private var dkPlanNth = 0
    private var lastOffNs = 0L
    // Only the stepping calibration sweep holds the shot rules back; the beat block (fixed lead) runs beside them.
    private val holdOthers get() = (cfg.sweepPress && cfg.sweepLeadMs == 0) || cfg.darkSweep
    @Volatile var darkOnsets = 0; private set
    @Volatile var darkPresses = 0; private set

    /**
     * A signature skill ("선수 스킬") darkens the whole screen, HUD included, before the ball leaves (found 11-11 on
     * Nowitzki in the practice court: the slot2 luma fades 116 -> 71 in ~0.27 s, the game's own demonstration block
     * jumps ~0.2 s after the fade starts; a fake skill gives a short dark (~0.33 s), then the real one ~40 ms later).
     * The rule is tools/analysis/darksim.py line for line (replayed on the recordings of 11-11 and on every bundle:
     * each of the 20 darks of nowitzki_r1 seen 50-110 ms after its start; 0 onsets on defence in six match bundles,
     * 2 in a seventh, both real darks):
     *  - onset #1: two falling scans that are a fade, not a jump (<= 0.24 luma per ms over the two), >= 8 under the
     *    mean of the scans 125-335 ms ago (which must be flat, < 5), >= 6 under their minimum, no more than 20 under.
     *    By the clock since vc1068: a hot phone scans at 30-45 Hz, not 60, and the per-scan form of vc1067 then missed
     *    4 of 108 darks (session 11-11 02:46; replay of the clock form: 108 of 108, and 20 of 20 at 60/40/30/24 Hz);
     *  - onset #2..: within 0.7 s after a dark ended, a fade of >= 6 from the highest level since;
     *  - end: back up by 60 % of the depth ("up"), or never deeper than 20 % of the base in 0.3 s ("shallow": a label
     *    change, not a dark), or 3 s.
     * Always logged (skill_dark / skill_dark_end). Presses only on defence and only with an experiment switch:
     * [Bench.Config.darkSweep] (practice court) = onset of dark number darkNth + darkAddMs + a stepped delay
     * ([DARK_DELAYS]), every other press held back; [Bench.Config.darkPress] = onset + darkDelayMs (dark #1, only if
     * it is still dark and deep then) or dark2DelayMs (dark #2..).
     */
    private fun skillDark(tNs: Long) {
        val l = slot2Luma
        val n = DK_N
        System.arraycopy(dkL, 1, dkL, 0, n - 1); dkL[n - 1] = l
        System.arraycopy(dkT, 1, dkT, 0, n - 1); dkT[n - 1] = tNs
        val scr = screenLuma
        System.arraycopy(dkS, 1, dkS, 0, n - 1); dkS[n - 1] = scr
        if (++dkN < n || l < 0f) return
        val p1 = dkL[n - 2]; val p2 = dkL[n - 3]
        if (!dkDark) {
            var nth = 0; var onNs = 0L; var base = 0f; var jump = false; var scrBase = 0f
            val spanMs = (tNs - dkT[n - 3]) / 1e6f
            val falling = l < p1 - 0.8f && p1 < p2 - 0.8f && spanMs <= 100f && p2 - l <= 0.24f * spanMs
            val recent = dkEndNs != 0L && tNs - dkEndNs < 700_000_000L
            if (recent && l > dkPeak) { dkPeak = l; dkPeakNs = tNs }
            if (falling && recent) {
                if (l <= dkPeak - 6f) { nth = dkNth + 1; onNs = dkPeakNs; base = dkBase }
            } else if (falling) {
                var mn = 999f; var mx = -1f; var s = 0f; var c = 0
                for (i in 0 until n) {
                    val ago = tNs - dkT[i]
                    if (ago in 125_000_000L..335_000_000L) { mn = minOf(mn, dkL[i]); mx = maxOf(mx, dkL[i]); s += dkL[i]; c++ }
                }
                val b = if (c >= 4) s / c else 0f
                if (c >= 4 && mx - mn < 5f && l <= b - 8f && l <= mn - 6f && b - l <= 20f && b >= 60f) {
                    nth = 1; base = b; onNs = tNs
                    for (i in n - 1 downTo 0) { if (tNs - dkT[i] > 125_000_000L) break; if (dkL[i] < b - 1.5f) onNs = dkT[i] }
                }
            }
            if (nth == 0 && !recent && scr >= 0f) {
                // The jump form (아일라 "기습 스텝백": slot 105 -> 67 and the whole screen 93 -> 12 in one frame; the fade
                // rule saw 2 of 26, session 11-11 03:43). The slot alone also jumps when a button is pressed or a label
                // changes, so the whole screen has to go dark with it.
                var mn = 999f; var mx = -1f; var s = 0f; var ss = 0f; var c = 0
                for (i in 0 until n) {
                    val ago = tNs - dkT[i]
                    if (ago in 125_000_000L..335_000_000L && dkS[i] >= 0f) { mn = minOf(mn, dkL[i]); mx = maxOf(mx, dkL[i]); s += dkL[i]; ss += dkS[i]; c++ }
                }
                if (c >= 4) {
                    val b = s / c; val sb = ss / c
                    if (mx - mn < 5f && b >= 60f && l <= b - JUMP_UNDER && sb >= 40f && scr <= JUMP_SCREEN * sb) {
                        nth = 1; base = b; onNs = tNs; jump = true; scrBase = sb
                        for (i in n - 1 downTo 0) { if (tNs - dkT[i] > 125_000_000L) break; if (dkL[i] < b - 1.5f) onNs = dkT[i] }
                    }
                }
            }
            if (nth > 0) {
                dkJump = jump; dkScrBase = scrBase
                dkDark = true; dkBase = base; dkLow = l; dkOnNs = onNs; dkNth = nth; darkOnsets++
                val def = tNs - lastDefNs < 200_000_000L && tNs - lastOffNs > 2_000_000_000L
                var plan = -1
                if (cfg.shotBlock && def) {
                    if (cfg.darkSweep) { if (nth == cfg.darkNth) plan = cfg.darkAddMs + DARK_DELAYS[dkSeq++ % DARK_DELAYS.size] }
                    else if (cfg.darkPress) plan = if (jump) cfg.darkJumpDelayMs else if (nth == 1) cfg.darkDelayMs else cfg.dark2DelayMs
                }
                if (plan >= 0) { dkDueNs = onNs + plan * 1_000_000L; dkArmed = false; dkDelayMs = plan; dkPlanNth = nth }
                if (plan >= 0 && cfg.darkSweep && cfg.darkJoyDeg >= 0 && cfg.autoBlockLive) {
                    val deg = cfg.darkJoyDeg; val ms = (if (cfg.darkJoyMs > 0) cfg.darkJoyMs else plan + 300).toLong()
                    main.post { BlockAccessibilityService.instance?.inGame?.nudgeJoy(deg, ms) }
                }
                if (nth == 1 && shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_dark", 0.6, 2.2) }
                event(tNs, "skill_dark", 0f, 0f, 0f, String.format(Locale.ROOT, "nth=%d base=%.1f luma=%.1f on_ago=%d plan=%d def=%d jump=%d",
                    nth, base, l, (tNs - onNs) / 1_000_000L, plan, if (def) 1 else 0, if (jump) 1 else 0))
            }
        } else {
            if (l < dkLow) dkLow = l
            val depth = dkBase - dkLow
            // The slot may brighten in the middle of a jump dark (a label lights up): there the whole screen says when it ends.
            val why = if (dkJump) when {
                scr >= 0.8f * dkScrBase -> "up"
                tNs - dkOnNs > 3_000_000_000L -> "timeout"
                else -> ""
            } else when {
                depth >= 0.2f * dkBase && l >= dkLow + 0.6f * depth -> "up"
                depth < 0.2f * dkBase && tNs - dkOnNs > 300_000_000L -> "shallow"
                tNs - dkOnNs > 3_000_000_000L -> "timeout"
                else -> ""
            }
            if (why.isNotEmpty()) {
                dkDark = false
                if (why == "up") { dkEndNs = tNs; dkPeak = l; dkPeakNs = tNs } else dkEndNs = 0L
                event(tNs, "skill_dark_end", 0f, 0f, 0f, String.format(Locale.ROOT, "nth=%d dur=%d low=%.1f base=%.1f %s", dkNth, (tNs - dkOnNs) / 1_000_000L, dkLow, dkBase, why))
            }
        }
        if (dkDueNs == 0L) return
        val live = cfg.autoBlockLive
        if (!dkArmed && tNs + 60_000_000L >= dkDueNs) { dkArmed = true; if (live) main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) } }
        if (tNs + 8_000_000L < dkDueNs) return
        val rem = ((dkDueNs - tNs) / 1_000_000L).coerceAtLeast(0L)
        dkDueNs = 0L
        val why = when {
            cfg.darkSweep -> ""
            !dkDark || dkNth != dkPlanNth -> "over"                                     // a fake: its dark is already gone
            dkDelayMs >= 150 && dkBase - dkLow < 0.2f * dkBase && !(l < p1 - 0.8f) -> "shallow"
            tNs - lastPressNs < 500_000_000L -> "just_pressed"
            else -> ""
        }
        if (why.isNotEmpty()) { event(tNs, "dark_dropped", 0f, 0f, 0f, why); return }
        shotTriggers++; darkPresses++
        val d = String.format(Locale.ROOT, "%s d=%d nth=%d base=%.1f low=%.1f rem=%d", if (cfg.darkSweep) "sweep_dark" else "dark", dkDelayMs, dkPlanNth, dkBase, dkLow, rem)
        event(tNs, "shot_trigger", 0f, 0f, 0f, d)
        snapNow = true
        press(tNs, live, d, cfg.shotDelayMs - rem)
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
            // vc1131: the user's "this was a shot" marks get most of the budget (a mark comes ~0.9 s after the release, the
            // 3.1 s before covers the pick-up). Match cue search 11-12 (HANDOFF 0-4z34) needs the ball pick-up in colour.
            val cap = when (g) { "other" -> MAX_COLOUR_CLIPS; "mark" -> 24; else -> 3 }
            if (colourClips + colourJobs.size < MAX_COLOUR_CLIPS && used < cap) { colourAccepted[g] = used + 1; colourJobs.add(r) }
        }
        val it = colourJobs.iterator()
        while (it.hasNext()) {
            val j = it.next()
            if (tNs >= j.tNs + j.afterNs) { it.remove(); saveColour(j) }
        }
    }

    /** Capture thread: drop the colour ring (it is built again if another frame comes). */
    fun free() { for (c in crops) c.px = ByteArray(0); cropPos = 0; cropFilled = 0; colourJobs.clear(); matchup.free() }

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
        sat = IntArray((gw + 1) * (gh + 1)); sat2 = IntArray((gw + 1) * (gh + 1))
        hCount = 0
    }

    private fun isHudTop(x: Int, y: Int, w: Int, h: Int): Boolean =
        (x < 0.30f * w && y < 0.22f * h) ||   // scoreboard
        (x > 0.62f * w && y < 0.14f * h)      // top-right icons and the app's own bubble

    private fun isHudLow(x: Int, y: Int, w: Int, h: Int): Boolean =
        (x > 0.74f * w && y > 0.40f * h) ||   // action buttons
        (x < 0.32f * w && y > 0.50f * h)      // joystick

    private fun isHud(x: Int, y: Int, w: Int, h: Int): Boolean = isHudTop(x, y, w, h) || isHudLow(x, y, w, h)

    private fun findDisc(buf: ByteBuffer, w: Int, h: Int, rowStride: Int): Boolean {
        val s = step; val stride = gw + 1
        val y0 = (0.06f * gh).toInt(); val y1 = (0.88f * gh).toInt()
        // sat = this finder's grid, as before. sat2 (vc1096, for [findDisc2] only) also counts the red under the joystick
        // and the action buttons; lowRed = how many such cells this frame (0 = the two grids are the same).
        var low = 0
        for (gy in 0 until gh) {
            var row = 0; var row2 = 0
            val py = gy * s
            val active = gy in y0 until y1
            for (gx in 0 until gw) {
                if (active) {
                    val px = gx * s
                    if (!isHudTop(px, py, w, h)) {
                        val o = py * rowStride + px * 4
                        val r = buf.get(o).toInt() and 0xFF
                        if (r > 160) {
                            val g = buf.get(o + 1).toInt() and 0xFF
                            val b = buf.get(o + 2).toInt() and 0xFF
                            if (g < 25 && b in 30..90 && r - b > 95) {
                                row2++
                                if (isHudLow(px, py, w, h)) low++ else row++
                            }
                        }
                    }
                }
                sat[(gy + 1) * stride + gx + 1] = sat[gy * stride + gx + 1] + row
                sat2[(gy + 1) * stride + gx + 1] = sat2[gy * stride + gx + 1] + row2
            }
        }
        lowRed = low
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
        gBx = bx; gBy = by; gBest = best; gSx = sx; gSy = sy; gSec = sec
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

    private var gBx = 0; private var gBy = 0; private var gBest = 0; private var gSx = 0; private var gSy = 0; private var gSec = 0
    private var d2 = false; private var d2X = -1f; private var d2Y = -1f; private var d2Ring = 0f; private var d2N = 0; private var d2Cand = 0
    private val candX = IntArray(D2_CAND); private val candY = IntArray(D2_CAND); private val edge = IntArray(24)
    private var d2Snaps = 0; private var lastD2SnapNs = 0L

    private fun discRed(buf: ByteBuffer, o: Int): Boolean {
        val r = buf.get(o).toInt() and 0xFF
        if (r <= 160) return false
        val g = buf.get(o + 1).toInt() and 0xFF
        val b = buf.get(o + 2).toInt() and 0xFF
        return g < 25 && b in 30..90 && r - b > 95
    }

    /**
     * Shadow finder (vc1088, round rim since vc1089; logged only: d2 / x2 / y2 / ring2 / n2 / cand2 in shot.csv;
     * nothing acts on it).
     * [findDisc] drops the disc whenever another red blob on the screen is >= 0.6 of it: in the vc1069 match bundle the
     * disc was seen 72 of 149 defence seconds, in one-frame flickers, and in >= 73 % of the 250 ms before a release for
     * only 7 of 16 shots (HANDOFF 0-4x). Here the up to [D2_CAND] strongest windows with fill >= [FOUND_FILL] (the grid
     * of [findDisc], > 2 k apart) are candidates; the red around each gives centre and radius; ring2 = share of 24
     * directions where a grey pixel (the disc's rim) lies within 5 px outside the last red one AND that edge is within a
     * quarter of the median edge distance (round). The disc is the candidate with the highest ring2 >= [D2_RING_MIN];
     * other red on the screen does not matter. Replay on the five recorded matches at capture size
     * (tools/analysis/fdtest.py, video pixels, not the raw capture): disc right in >= 73 % of -0.35..-0.10 s before the
     * release for 32 of 45 shots, [findDisc] 25. Without "round" and with a minimum of 0.4 (vc1088) the matchup mark on
     * the floor (a red ellipse with white digits and a white rim) passed as the disc in about 80 frames of 3 shots;
     * with them in none, same 32 of 45. Lengths are for a 540-high capture.
     * vc1096: the candidates also come from under the joystick and the action buttons (sat2 of [findDisc]; that
     * finder's own grid and everything that presses by default are unchanged). In the recordings the marker of 4 of the
     * 9 shots this finder did not see stood in those two zones in plain view. Replay on the 3 s before each of the 44
     * recorded shots (10,087 frames, video pixels without the app's own pad over the joystick, so the gain on the
     * phone is not known): seen 6,682 -> 7,461 frames, 1 moved, 1 lost, right 3,966 -> 4,116 and wrong 2 -> 1 of the
     * 4,503 with a truth; shots seen in >= 50 % of -0.45..-0.10 s: 35 -> 40 of 44 (HANDOFF 0-4z3). shot.csv: low2.
     */
    private fun findDisc2(buf: ByteBuffer, w: Int, h: Int, rowStride: Int) {
        d2 = false; d2X = -1f; d2Y = -1f; d2Ring = 0f; d2N = 0; d2Cand = 0
        val kk = (k * k).toFloat()
        // No red under the joystick or the buttons: the two best windows of [findDisc] are also the best of sat2.
        val g = if (lowRed > 0) sat2 else sat
        var n = 0
        if (lowRed == 0) {
            if (gBest / kk < FOUND_FILL) return
            candX[0] = gBx; candY[0] = gBy; n = 1
            if (gSec / kk >= FOUND_FILL) { candX[1] = gSx; candY[1] = gSy; n = 2 }
        }
        if (n != 1) {                                       // n == 1: the second window was already too weak
            val stride = gw + 1; val far = 2 * k
            while (n < D2_CAND) {
                var top = 0; var tx = 0; var ty = 0
                for (gy in 0..gh - k) for (gx in 0..gw - k) {
                    val c = g[(gy + k) * stride + gx + k] - g[gy * stride + gx + k] - g[(gy + k) * stride + gx] + g[gy * stride + gx]
                    if (c <= top) continue
                    var near = false
                    for (i in 0 until n) if (Math.abs(gx - candX[i]) <= far && Math.abs(gy - candY[i]) <= far) { near = true; break }
                    if (!near) { top = c; tx = gx; ty = gy }
                }
                if (top / kk < FOUND_FILL) break
                candX[n] = tx; candY[n] = ty; n++
            }
        }
        if (n == 0) return
        d2Cand = n
        val u = h / 540f
        val half = Math.round(16 * u); val minN = (60 * u * u).toInt(); val rim = maxOf(2, Math.round(5 * u))
        var bestRing = -1f
        for (i in 0 until n) {
            var cx = ((candX[i] + k / 2f) * step).toInt(); var cy = ((candY[i] + k / 2f) * step).toInt()
            var cnt = 0
            for (pass in 0 until 2) {                       // centroid of the red around the window, twice
                val xa = maxOf(0, cx - half); val xb = minOf(w - 1, cx + half)
                val ya = maxOf(0, cy - half); val yb = minOf(h - 1, cy + half)
                var sx = 0L; var sy = 0L; cnt = 0
                var y = ya
                while (y <= yb) {
                    var o = y * rowStride + xa * 4
                    var x = xa
                    while (x <= xb) {
                        if (discRed(buf, o)) { cnt++; sx += x; sy += y }
                        x++; o += 4
                    }
                    y++
                }
                if (cnt < 20 * u * u) break
                cx = Math.round(sx.toFloat() / cnt); cy = Math.round(sy.toFloat() / cnt)
            }
            if (cnt < minN) continue
            val rad = Math.sqrt(cnt / Math.PI).toFloat().coerceIn(6 * u, 16 * u)
            val rMax = (1.6f * rad + 2 * u).toInt()
            var ne = 0
            for (a in 0 until 24) {
                val ca = COS24[a]; val sa = SIN24[a]
                var e = 0                                   // last red pixel on this ray = the edge of the disc
                var r = 3
                while (r <= rMax) {
                    val x = Math.round(cx + r * ca); val y = Math.round(cy + r * sa)
                    if (x < 0 || x >= w || y < 0 || y >= h) break
                    if (discRed(buf, y * rowStride + x * 4)) e = r
                    r++
                }
                if (e == 0) continue
                r = e + 1
                while (r <= e + rim) {                      // the grey rim sits right outside it
                    val x = Math.round(cx + r * ca); val y = Math.round(cy + r * sa)
                    if (x < 0 || x >= w || y < 0 || y >= h) break
                    val o = y * rowStride + x * 4
                    val pr = buf.get(o).toInt() and 0xFF; val pg = buf.get(o + 1).toInt() and 0xFF; val pb = buf.get(o + 2).toInt() and 0xFF
                    val lo = minOf(pr, pg, pb)
                    if (lo >= 150 && maxOf(pr, pg, pb) - lo <= 45) { edge[ne++] = e; break }
                    r++
                }
            }
            if (ne == 0) continue
            java.util.Arrays.sort(edge, 0, ne)              // round: the rim is at about the same distance all around
            val em = if (ne % 2 == 1) edge[ne / 2].toFloat() else (edge[ne / 2 - 1] + edge[ne / 2]) / 2f
            val tol = maxOf(2 * u, 0.25f * em)
            var hit = 0
            for (j in 0 until ne) if (Math.abs(edge[j] - em) <= tol) hit++
            val ring = hit / 24f
            if (ring > bestRing) { bestRing = ring; d2X = cx / w.toFloat(); d2Y = cy / h.toFloat(); d2Ring = ring; d2N = cnt }
        }
        d2 = bestRing >= D2_RING_MIN
    }

    /**
     * Shadow rule (vc1091, logged as "rise2" events; presses only through [rise2Press], off by default) =
     * tools/analysis/mvel.py W 0.08 D 1.6
     * on the track of [findDisc2]: on defence the disc rose [R2_D] disc radii (radius = median of the last 0.5 s) within
     * [R2_W_NS], moved sideways less than 1.5 x the rise, and no rise2 in the last 1.5 s. On the five recorded matches
     * (the data it was designed on) at capture size it falls in -330..-150 ms before the release for 15 of 44 shots
     * (HANDOFF 0-4z); on the app's own flickering track of the vc1069 bundle it failed (1 of 16, 0-4w). The rise2
     * events of a match bundle are its independent test: tools/analysis/sessrel.py <session> -ev rise2. First one
     * (vc1092 match, 15 releases, 0-4z3): on time 5, too early 5, none 5, 13 events with no shot.
     */
    private fun rise2(tNs: Long, w: Int, h: Int) {
        if (!d2 || side != SIDE_DEF) return
        val i = r2Head
        r2T[i] = tNs; r2X[i] = d2X * w; r2Y[i] = d2Y * h; r2R[i] = Math.sqrt(d2N / Math.PI).toFloat()
        r2Head = (i + 1) % R2_LEN; if (r2Count < R2_LEN) r2Count++
        var m = 0
        for (k in 0 until r2Count) {                        // newest first
            val q = (i - k + R2_LEN) % R2_LEN
            if (tNs - r2T[q] > 500_000_000L) break
            r2S[m++] = r2R[q]
        }
        java.util.Arrays.sort(r2S, 0, m)
        val rad = if (m % 2 == 1) r2S[m / 2] else (r2S[m / 2 - 1] + r2S[m / 2]) / 2f
        if (rad <= 0f || r2R[i] < 0.7f * rad || r2R[i] > 1.4f * rad) return
        var j = r2Ago(tNs, i, rad, R2_W_NS)
        if (j >= 0) {
            val rise = (r2Y[j] - r2Y[i]) / rad
            if (rise >= R2_D && Math.abs(r2X[i] - r2X[j]) / rad <= 1.5f * rise && tNs - lastRise2Ns >= 1_500_000_000L) {
                lastRise2Ns = tNs
                event(tNs, "rise2", (r2X[i] - r2X[j]) / w, (r2Y[i] - r2Y[j]) / h, (tNs - r2T[j]) / 1e6f,
                    String.format(Locale.ROOT, "rise=%.2f R=%.1f x2=%.4f y2=%.4f ring2=%.2f", rise, rad, d2X, d2Y, d2Ring))
            }
        }
        j = r2Ago(tNs, i, rad, R2_PRESS_W_NS)
        if (j < 0) return
        val rise = (r2Y[j] - r2Y[i]) / rad
        if (rise < R2_PRE_D || Math.abs(r2X[i] - r2X[j]) / rad > 1.5f * rise) return
        rise2PreArm(tNs)
        if (rise >= R2_PRESS_D)
            rise2Press(tNs, String.format(Locale.ROOT, "rise=%.2f w=%d R=%.1f x2=%.4f y2=%.4f ring2=%.2f", rise, R2_PRESS_W_NS / 1_000_000L, rad, d2X, d2Y, d2Ring))
    }

    /**
     * [Bench.Config.rise2Press] with [Bench.Config.fastPress] only (both off by default): open the block tile when the
     * rise is at [R2_PRE_D] of the [R2_PRESS_D] that presses, so the press does not wait for the tile's 40 ms
     * pass-through ([preArm] works on the gap track, which the rise press does not need). Replay on the d2 tracks of
     * the two phone matches of 11-12 (tools/analysis/risearm.py; the 1.5 was picked on them, so not independent): the
     * tile would have been open >= 40 ms before 12 of 22 and 10 of 13 rise presses, and is opened in about 5 % of
     * defence scans (111 and 79 openings). Never run on the phone. HANDOFF 0-4z12.
     */
    private fun rise2PreArm(tNs: Long) {
        if (!cfg.shotBlock || !cfg.autoBlockLive || !cfg.rise2Press || cfg.fastPress <= 0) return
        if (tNs - lastPreArmNs < 150_000_000L || pendNs != 0L || tNs - lastPressNs < 1_500_000_000L || tNs - lastShotFireNs < 1_500_000_000L) return
        lastPreArmNs = tNs; preArms++
        main.post { BlockAccessibilityService.instance?.inGame?.armBlock(PRE_ARM_MS) }
        event(tNs, "arm", 0f, 0f, 0f, "pre_rise2")
    }

    /** The sample of the rise2 track taken [wNs] ago (the oldest one not older; within 20 ms of it, the track unbroken
     *  there, its radius within 0.7..1.4 of [rad]), or -1. [i] = the newest sample. As mvel.py presses(). */
    private fun r2Ago(tNs: Long, i: Int, rad: Float, wNs: Long): Int {
        var j = -1; var older = -1
        for (k in 1 until r2Count) {                        // newest first
            val q = (i - k + R2_LEN) % R2_LEN
            if (tNs - r2T[q] <= wNs) j = q else { older = q; break }
        }
        if (j < 0 || wNs - (tNs - r2T[j]) > 20_000_000L) return -1           // no sample close to W ago
        if (tNs - r2T[if (older >= 0) older else j] > wNs + 60_000_000L) return -1   // the track was broken there
        if (r2R[j] < 0.7f * rad || r2R[j] > 1.4f * rad) return -1
        return j
    }

    /**
     * Experimental press on the rise of the ring-checked disc (vc1095, only with [Bench.Config.rise2Press], off by
     * default): the rule of [rise2] over [R2_PRESS_W_NS] with D = [R2_PRESS_D] (mvel.py W 0.12 D 4.0; vc1095 had
     * W 0.08 D 2.2). Never pressed on the phone yet. Replays (press - release in -330..-150 ms = "on time", which is
     * the timing only, not a block; HANDOFF 0-4z2, 0-4z3):
     *   five recorded matches, 44 shots (design data): on time 15, too early 2, late 2, 3 presses in the 2 s before
     *     with no shot; with 70 ms of added delay on time 7-10. W 0.08 D 2.2: on time 12, 8 such presses.
     *   the app's own d2 track of the vc1092 match of 11-12 08:30, 15 releases (11 from the slot label, 4 more from
     *     the user's record marks): on time 7, too early 3, none 5, 3 presses with no shot. W 0.08 D 2.2: on time 5,
     *     10 presses with no shot. The app's own presses in that match: on time 4, late 1, 3 with no shot.
     * The height was changed after seeing that bundle, so the next bundle is its test. Slow lifts are not caught.
     * Same guards as the other experimental presses: not while a gap trigger waits for its
     * confirmation, not within 1.5 s of a press or trigger, not when my player is seen far from the shooter.
     */
    private fun rise2Press(tNs: Long, d: String) {
        if (!cfg.shotBlock || !cfg.rise2Press) return
        val why = when {
            pendNs != 0L -> "gap_pending"
            tNs - lastPressNs < 1_500_000_000L -> "pressed"
            tNs - lastShotFireNs < 1_500_000_000L -> "lockout"
            else -> ""
        }
        if (why.isNotEmpty()) {
            if (tNs - lastRise2DropNs >= 1_500_000_000L) { lastRise2DropNs = tNs; event(tNs, "rise2_dropped", 0f, 0f, 0f, "$why $d") }
            return
        }
        val live = cfg.autoBlockLive
        lastShotFireNs = tNs
        shotTriggers++; rise2Triggers++
        synchronized(pendingTriggers) { pendingTriggers.add(tNs) }
        if (!holdOthers && shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_rise2", 0.6, 1.8 + cfg.shotDelayMs / 1000.0) }
        // The feet are not known on this track: only the sideways distance to my player counts (as in crouch without a ring).
        var meDist = findMe(d2X, minOf(0.95f, d2Y + 0.3f), d2Y)
        if (meDist >= 0f) meDist = Math.abs(meX - d2X)
        val d2s = String.format(Locale.ROOT, "rise2 %s sens=%d delay=%d me=%.3f/%.3f d=%.3f", d, cfg.shotSens, cfg.shotDelayMs, meX, meY, meDist)
        nearX = d2X; nearY = d2Y
        event(tNs, "shot_trigger", 0f, 0f, 0f, d2s)
        snapNow = true
        if (meDist > ME_FAR) { shotFar++; reject(tNs, "rejected_far", d2s); return }
        if (notMine(tNs, d2s)) return
        if (live) main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) }
        press(tNs, live, d2s, 0L)
    }

    @Volatile var rise2Triggers = 0; private set
    private var lastRise2DropNs = 0L

    private val r2T = LongArray(R2_LEN); private val r2X = FloatArray(R2_LEN); private val r2Y = FloatArray(R2_LEN)
    private val r2R = FloatArray(R2_LEN); private val r2S = FloatArray(R2_LEN)
    private var r2Head = 0; private var r2Count = 0; private var lastRise2Ns = 0L

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
        if (!holdOthers && shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_crouch", 0.6, 2.3 + cfg.shotDelayMs / 1000.0) }
        var meDist = findMe(discX, if (ringKnown) gapRing else minOf(0.95f, discY + 0.3f))
        if (!ringKnown && meDist >= 0f) meDist = Math.abs(meX - discX)
        val d2 = String.format(Locale.ROOT, "%s cd=%d sens=%d delay=%d me=%.3f/%.3f d=%.3f", d, waitMs, cfg.shotSens, cfg.shotDelayMs, meX, meY, meDist)
        event(tNs, "shot_trigger", 0f, 0f, 0f, d2)
        snapNow = true
        if (meDist > ME_FAR) { shotFar++; reject(tNs, "rejected_far", d2); return }
        if (notMine(tNs, d2)) return
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
        if (!holdOthers && shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_up", 0.6, 1.8 + cfg.shotDelayMs / 1000.0) }
        // Without a foot ring the feet height is a guess (typical gap 0.3 H), so only the sideways distance counts then.
        var meDist = findMe(discX, if (ringKnown) gapRing else minOf(0.95f, discY + 0.3f))
        if (!ringKnown && meDist >= 0f) meDist = Math.abs(meX - discX)
        val d2 = String.format(Locale.ROOT, "up gap=%.3f top=%.4f upage=%d run=%d sens=%d delay=%d xspan=%.3f me=%.3f/%.3f d=%.3f",
            if (ringKnown) gapRing - discY else -1f, topDy, (tNs - upSinceNs) / 1_000_000L, (tNs - runNs) / 1_000_000L,
            cfg.shotSens, cfg.shotDelayMs, runXMax - runXMin, meX, meY, meDist)
        event(tNs, "shot_trigger", 0f, 0f, 0f, d2)
        snapNow = true
        if (meDist > ME_FAR) { shotFar++; reject(tNs, "rejected_far", d2); return }
        if (notMine(tNs, d2)) return
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
        if (live && cfg.fastPress > 0 && tNs - lastPreArmNs >= 150_000_000L) preArm(tNs, gLast)
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
                    if (!holdOthers && shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_fresh", 0.6, 1.8 + cfg.shotDelayMs / 1000.0) }
                    val meDist = findMe(discX, discY + g0)
                    val d2 = String.format(Locale.ROOT, "fresh gap=%.3f first=%.3f up=%.3f run=%d n=%d sens=%d delay=%d xspan=%.3f me=%.3f/%.3f d=%.3f",
                        g0, minOf(runG0, runG1), up, (tNs - runNs) / 1_000_000L, runG, cfg.shotSens, cfg.shotDelayMs, runXMax - runXMin, meX, meY, meDist) + topText(tNs)
                    event(tNs, "shot_trigger", 0f, 0f, 0f, d2)
                    snapNow = true
                    if (meDist > ME_FAR) { shotFar++; reject(tNs, "rejected_far", d2); return }
                    if (notMine(tNs, d2)) return
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
        if (!holdOthers && shotClips < MAX_SHOT_CLIPS) { shotClips++; Bench.clipRequester?.invoke("trig_$kind", 0.6, 1.8 + cfg.shotDelayMs / 1000.0) }
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
        if (notMine(tNs, d2)) return
        if (live) main.post { BlockAccessibilityService.instance?.inGame?.armBlock(ARM_MS) }
        // Calm shooter (practice: xspan <= 0.026, 10/15 blocked with an immediate press; waiting for the confirmation
        // would add 66-167 ms and practice '늦음' was already ~100 ms late): press now.
        // "Calm" needs a track long enough to show sway: right after a ring<->disc switch the history restarts and a
        // moving shooter read as xspan 0.02 (vc1035 330.0 s, really 0.138). Replay: all 15 practice presses had >= 0.57 s.
        // An early-only press is held EARLY_DELAY_MS so it lands 35-80 ms after the disc left the crouch (replay), inside
        // the range that was blocked; nothing earlier than 47 ms has been tried yet, so do not go below it blindly.
        if (calm) { press(tNs, live, d2 + " calm", if (v2 || cfg.fastPress >= 2) 0L else -EARLY_DELAY_MS); return }
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
    private var lastPreArmNs = 0L
    @Volatile var preArms = 0; private set

    /**
     * [Bench.Config.fastPress] only (off by default; the triggers and the number of presses stay the same). Measured on
     * practice bundles (latency.py, armlead.py): the trigger came 229-324 ms before the release but the tap left 40-60 ms
     * later, 40 of it the block tile's pass-through wait, because the half-rise arm above mostly fires in the trigger's
     * own frame (>= 40 ms ahead for 14/32, 1/12, 9/23 triggers). Taps < 181 ms before the release blocked 0 of 16,
     * taps at 211-265 ms 13 of 17. So open the tile on a much weaker sign: the marker landed on this player
     * <= 350 ms ago (covers the fresh rule), or the gap is PRE_RISE above its low of the last 60-400 ms (same low as the
     * early rule). Replay (prearm.py, same bundles, not independent): open >= 40 ms before the trigger for 27/32,
     * 12/12, 22/23 practice and 14/22, 25/37 match triggers; one of the two signs is true in roughly 11-25 % of defence
     * scans, and while the tile is open a real finger on that quarter of the screen goes straight to the game.
     */
    private fun preArm(tNs: Long, gLast: Int) {
        var why = if (tNs - runNs <= 350_000_000L) "pre_run" else ""
        if (why.isEmpty() && gCount >= 6 && tNs - gT[gLast] <= 1_000_000L) {
            var lo0 = Float.MAX_VALUE; var lo1 = Float.MAX_VALUE; var loN = 0
            for (i in 1 until gCount) {
                val idx = (gPos - 1 - i + 2 * histN) % histN
                val age = tNs - gT[idx]
                if (age > 400_000_000L || gT[idx] < gJumpNs) break
                if (age >= 60_000_000L) {
                    loN++
                    val v = gV[idx]
                    if (v < lo0) { lo1 = lo0; lo0 = v } else if (v < lo1) lo1 = v
                }
            }
            if (loN >= 5 && gV[gLast] - maxOf(lo0, lo1 - 0.006f) >= PRE_RISE - 0.001f) why = "pre"
        }
        if (why.isEmpty()) return
        lastPreArmNs = tNs; preArms++
        main.post { BlockAccessibilityService.instance?.inGame?.armBlock(PRE_ARM_MS) }
        event(tNs, "arm", 0f, 0f, 0f, why)
    }
    @Volatile var shotRejected = 0; private set
    @Volatile var shotFar = 0; private set
    @Volatile var shotArms = 0; private set
    @Volatile var shotNotMine = 0; private set
    @Volatile var nearNo = 0; private set
    @Volatile var nearUnknown = 0; private set
    // Ball of the trigger being logged when it is not the gap track's disc (rise2); -1 = use discX / discY.
    private var nearX = -1f; private var nearY = -1f
    private var lastNear = -1

    /** MatchupShadow.near for the trigger being logged: always written into the shot_trigger text, see [notMine]. */
    private fun nearText(tNs: Long): String {
        val n = matchup.near(tNs, if (nearX >= 0f) nearX else discX, if (nearX >= 0f) nearY else discY)
        nearX = -1f; lastNear = n.near
        return String.format(Locale.ROOT, " near=%d up=%.3f side=%.3f nme=%d", n.near, n.up, n.side, n.n)
    }

    /**
     * HANDOFF 0-4z10: is the ball of this trigger away from my own player (core NearGate), or my player not found?
     * Counted always; the trigger is dropped only with [Bench.Config.nearOnly] (off by default). Phone match of 11-12
     * 11:13: 6 of 12 auto presses were somebody else's dunk or lay-up at the rim while my player stood with his
     * matchup further out, and the jump left the matchup open. Call right after the shot_trigger event.
     */
    private fun notMine(tNs: Long, d: String): Boolean {
        if (lastNear == 1) return false
        if (lastNear == 0) nearNo++ else nearUnknown++
        // Practice-court tests (follow modes, beat block): the only shooter is my matchup; 16:13 and 16:36 practice
        // dropped 19/31 and 17/24 triggers here with the switch left on (HANDOFF 0-4z26, 0-4z28).
        if (!cfg.nearOnly || cfg.follow > 0 || (cfg.sweepPress && cfg.sweepLeadMs == 0)) return false
        shotNotMine++; reject(tNs, "rejected_notmine", d); return true
    }

    /** A dropped trigger must not block the real shot that follows a fake (vc1038: 394.5 -> 396.4): 0.4 s, not 1.5 s. */
    private fun reject(tNs: Long, name: String, detail: String) {
        shotRejected++
        lastShotFireNs = tNs - 1_100_000_000L
        event(tNs, name, 0f, 0f, 0f, detail)
        Bench.shotLive = liveText()
    }

    /** [ageMs] = time already spent since the trigger; the user's extra delay counts from the trigger. */
    private fun press(tNs: Long, live: Boolean, detail: String, ageMs: Long) {
        val sweep = detail.startsWith("sweep")
        // ⑩ on: only the sweep presses; the shot rules are still logged, untouched by it (no lastPressNs lockout).
        if (holdOthers && !sweep) { event(tNs, "would_press", 0f, 0f, 0f, detail); return }
        if (!sweep) lastPressNs = tNs
        if (!live) { shotSkipped++; event(tNs, "would_press", 0f, 0f, 0f, detail); Bench.shotLive = liveText(); return }
        val delay = (cfg.shotDelayMs.toLong() - ageMs).coerceAtLeast(0L)
        main.postDelayed({
            val ok = BlockAccessibilityService.instance?.inGame?.fireBlock("SHOT", needTouch = !sweep) ?: false
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
    private fun findMe(sx: Float, sy: Float, top: Float = discY): Float {
        meX = -1f; meY = -1f
        val buf = curBuf ?: return -1f
        val w = curW; val h = curH; val s = step
        val nw = 16; val nh = 6
        val ax = ((sx - 0.3f) * gw).toInt().coerceIn(0, gw - 1); val bx = ((sx + 0.3f) * gw).toInt().coerceIn(0, gw)
        val ay = ((top + 0.05f) * gh).toInt().coerceIn(0, gh - 1); val by = (minOf(0.95f, sy + 0.3f) * gh).toInt().coerceIn(0, gh)
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
            (tNs - startedNs) / 1e9, name, sideName(side), discX, discY, dx, dy, dtMs, if (name == "shot_trigger") detail + nearText(tNs) + matchup.triggerText(tNs) else detail))
    }

    private fun liveText() = "일반 슛 감지 ${shotTriggers}회 · 헛블락 거름 ${shotRejected}회(멀어서 ${shotFar}회) · 블록 누름 ${shotFired}회 · 건너뜀 ${shotSkipped}회"

    fun shotSummary(): String {
        val leads = synchronized(pendingTriggers) { leadsMs.sorted() }
        val med = if (leads.isEmpty()) "-" else "${leads[leads.size / 2]}ms"
        return "일반 슛 발동 ${shotTriggers}회(${if (cfg.shotBlock && cfg.autoBlockLive) "실제 누름" else "기록만"}, 민감도 ${cfg.shotSens}, 지연 ${cfg.shotDelayMs}ms) → 거름 ${shotRejected}회(그중 내 선수가 멀어서 ${shotFar}회), 버튼 미리 열기 ${shotArms}회, 누름 ${shotFired}회, 건너뜀 ${shotSkipped}회\n" +
            "  팔 올림 감지 ${upEvents}회(팔 올림으로 누르기 ${if (cfg.upPress) "켬" else "끔"}, 그걸로 발동 ${upTriggers}회)\n" +
            "  웅크림 감지 ${crouchEvents}회(웅크릴 때 누르기 ${if (cfg.crouchPress) "켬" else "끔"}, 그걸로 발동 ${crouchTriggers}회)\n" +
            "  공 솟음으로 누르기 ${if (cfg.rise2Press) "켬" else "끔"}, 그걸로 발동 ${rise2Triggers}회\n" +
            "  빨리 누르기 ${if (cfg.fastPress > 0) "켬(${cfg.fastPress})" else "끔"}, 버튼 더 미리 열기 ${preArms}회\n" +
            "  내 선수 근처 슛만 누르기 ${if (cfg.nearOnly) "켬" else "끔"}, 그걸로 거름 ${shotNotMine}회(꺼져 있어도 셈: 내 선수와 먼 슛 ${nearNo}회, 내 선수 못 찾음 ${nearUnknown}회)\n" +
            "  연습장 박자: 반복 시작 ${sweepReps}회, 공 떠남 ${sweepReleases}회(박자 ${if (!cfg.sweepPress) "끔" else if (cfg.sweepLeadMs > 0) "블록 켬 ${cfg.sweepLeadMs}ms 전" else "훑기 켬"}, 그걸로 누름 ${sweepPresses}회)\n" +
            "  선수 스킬 어두워짐 ${darkOnsets}회(그걸로 누름 ${darkPresses}회, 누르기 ${if (cfg.darkSweep) "훑기" else if (cfg.darkPress) "켬" else "끔(기록만)"})\n" +
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
        /** Shadow finder ([findDisc2]): candidates checked, min share of directions with the grey rim at one distance. */
        const val D2_CAND = 5
        const val D2_RING_MIN = 0.55f
        /** Shadow rule [rise2]: samples kept, the look-back and the rise in disc radii. */
        const val R2_LEN = 64
        const val R2_W_NS = 80_000_000L
        const val R2_D = 1.6f
        const val R2_PRESS_W_NS = 120_000_000L
        const val R2_PRESS_D = 4.0f
        /** [rise2PreArm]: the rise (in disc radii over [R2_PRESS_W_NS]) that opens the block tile ahead of the rise press. */
        const val R2_PRE_D = 1.5f
        private val COS24 = FloatArray(24) { Math.cos(it * Math.PI / 12).toFloat() }
        private val SIN24 = FloatArray(24) { Math.sin(it * Math.PI / 12).toFloat() }
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
        /** Sweep (⑩): planned press leads before the release, ms, in a mixed order so a drift cannot pass for a trend. */
        val SWEEP_LEADS = intArrayOf(250, 400, 100, 325, 175, 275, 375, 125, 225, 350, 150, 300, 200)
        const val JUMP_UNDER = 20f; const val JUMP_SCREEN = 0.5f   // jump form: slot this far under the flat stretch at once, screen under this share
        const val DK_N = 40      // scans kept for skillDark: 335 ms even at 120 scans/s
        /** Dark sweep: planned press delays after the onset of the dark, ms, in a mixed order. */
        val DARK_DELAYS = intArrayOf(200, 350, 100, 275, 150, 425, 250, 50, 325, 500, 225, 125, 400, 175, 300, 0)
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
        /** Beat block: the last two repT must agree within this to press (steady practice beat). */
        const val SWEEP_TOL_NS = 80_000_000L
        /** fastPress: gap rise above its 60-400 ms low that opens the tile (3 grid steps of 0.0056 H), and how long it stays open. */
        const val PRE_RISE = 0.0167f
        const val PRE_ARM_MS = 250L
        val SENS = arrayOf(floatArrayOf(0.45f, 0.35f, 0.035f), floatArrayOf(0.35f, 0.25f, 0.025f), floatArrayOf(0.25f, 0.18f, 0.018f))
        fun sideName(s: Int) = when (s) { SIDE_DEF -> "def"; SIDE_OFF -> "off"; else -> "unk" }
    }
}
