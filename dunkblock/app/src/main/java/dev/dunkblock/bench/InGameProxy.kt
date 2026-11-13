package dev.dunkblock.bench

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * ⑦ In-game proxy joystick (PRACTICE MODE ONLY). Everything lives in accessibility overlays above the game:
 *  1. a floating button: the user opens the game, then taps it,
 *  2. calibration: tap the joystick centre, then the block button,
 *  3. a pad laid exactly over the joystick. When the thumb lands on it, the pad turns pass-through (the thumb keeps
 *     going to the pad; the service's injected joystick stroke, which copies the thumb, falls through to the game),
 *  4. while the thumb is down, the block button is tapped automatically every [tapEveryMs] (in the same gesture),
 *  5. the floating button ends the test and saves the log.
 * What the game does with it (does the character keep running while blocking?) is observed by the user.
 */
class InGameProxy(private val svc: BlockAccessibilityService, private val autoMode: Boolean = false) {
    private var skippedFires = 0
    private var markBubble: TextView? = null
    private var marks = 0

    /** ⑧: a second small button. Tapping it saves the last 3.4 s as a clip ("that should have been blocked"). */
    private fun showMarkButton() {
        val tv = TextView(svc).apply {
            text = "기록"
            setTextColor(Color.WHITE); textSize = 15f
            setBackgroundColor(Color.argb(210, 0, 90, 200)); setPadding(36, 20, 36, 20)
            setOnClickListener {
                marks++
                rec("user", "mark", 0f, 0f, marks)
                Bench.clipRequester?.invoke("mark_" + ShotWatcher.sideName(Bench.shotSide), 3.6, 0.2)
                text = "기록 $marks"
            }
        }
        val dm = svc.resources.displayMetrics
        if (svc.addOverlay(tv, (0.22f * dm.widthPixels).toInt(), 40, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL) != null) markBubble = tv
    }
    private val tag = if (autoMode) "⑧" else "⑦"

    /**
     * ⑧: press block now (main thread). Every finger the user has on the game is on one of our overlays and part of
     * the same injected gesture, so the press no longer needs the thumb on the joystick (v1022 skipped 8 of 10
     * ultimates for that). To stay off menus it still needs a touch on our overlays in the last [RECENT_TOUCH_NS].
     */
    fun fireBlock(reason: String, needTouch: Boolean = true): Boolean {
        val h = hand
        // needTouch = false only for the practice-court sweep (⑩), which is run with nobody holding the phone.
        // defHolding (mode 5): the app itself holds '수비' on the defence screen, so nobody touches; 16:36 practice skipped 2 of 3 for that.
        val recent = !needTouch || defHolding || Bench.config.unattended || System.nanoTime() - lastTouchNs < RECENT_TOUCH_NS
        if (finished || h == null || !recent) { skippedFires++; rec("auto", "skip_${reason}_${if (h == null) "no_pad" else "idle"}", 0f, 0f, 0); return false }
        if (defHolding) defRelease("block")
        val t = tileAt(block)
        // A tile opened only a moment ago (pre-arm) still needs the rest of the pass-through wait.
        val delay = when {
            t == null -> 0L
            t.touchable -> { setTileTouchable(t, false); PASS_THROUGH_MS }
            else -> (PASS_THROUGH_MS - (System.nanoTime() - t.openedNs) / 1_000_000L).coerceIn(0L, PASS_THROUGH_MS)
        }
        main.postDelayed({
            h.tap(PointF(block.x, block.y))
            if (t != null && t.fingerId < 0) main.postDelayed({ if (t.fingerId < 0 && System.nanoTime() > t.armedUntilNs) setTileTouchable(t, true) }, 150)
        }, delay)
        autoTaps++
        rec("auto", "fire_${reason}_thumb${if (h.isHolding(JOY)) 1 else 0}", block.x, block.y, delay.toInt())
        bubble?.text = "⑧ 자동 블록 ${autoTaps}회 · 여기를 눌러 끝내기"
        return true
    }

    /** ⑧: an ultimate was seen; the press follows in [ms]. Opens the block button to injection now so the press is not late. */
    fun armBlock(ms: Long) {
        val t = tileAt(block) ?: return
        if (blockHeld) return   // already open for the whole defence
        t.armedUntilNs = System.nanoTime() + ms * 1_000_000L
        if (t.touchable) setTileTouchable(t, false)
        main.postDelayed({ if (t.fingerId < 0 && System.nanoTime() >= t.armedUntilNs) setTileTouchable(t, true) }, ms + 10)
    }

    private var blockHeld = false
    private var blockOpens = 0

    /**
     * 0-4z52 (ShotWatcher, follow + fast press): keep the block button's tile open for the whole defence, so the user's
     * own block finger lands on the game directly. Through the tile it reached the game ~85 ms after the touch
     * (40 ms pass-through + injection); in practice 40-60 ms late presses never blocked. Cost: while that finger is
     * on the game the injected joystick is cancelled and comes back as a new touch (ProxyHand.onCancelled).
     */
    fun holdBlockOpen(on: Boolean) {
        if (finished || on == blockHeld) return
        val t = tileAt(block) ?: return
        blockHeld = on
        rec("auto", if (on) "block_open" else "block_close", block.x, block.y, t.id)
        if (on) { blockOpens++; t.armedUntilNs = Long.MAX_VALUE; if (t.touchable) setTileTouchable(t, false) }
        else { t.armedUntilNs = 0L; if (t.fingerId < 0) setTileTouchable(t, true) }
    }

    private var nudging = false

    /**
     * Calibration, practice court with nobody holding the phone ([Bench.Config.darkJoyDeg]): holds the joystick
     * [deg] degrees (0 = screen right, 90 = up) for [ms] as the hand's own joystick finger, so a block pressed
     * meanwhile jumps that way. Skipped while a real thumb is on the pad. Untested on the phone (written 11-11).
     */
    fun nudgeJoy(deg: Int, ms: Long) {
        val h = hand
        if (finished || h == null || thumbDown || nudging) { rec("auto", "nudge_skipped", 0f, 0f, deg); return }
        nudging = true
        val dm = svc.resources.displayMetrics
        val r = 0.13f * min(dm.widthPixels, dm.heightPixels)
        val a = Math.toRadians(deg.toDouble())
        val to = PointF(joy.x + r * Math.cos(a).toFloat(), joy.y - r * Math.sin(a).toFloat())
        svc.setPadTouchable(false)
        main.postDelayed({
            h.down(JOY, to, anchor = PointF(joy.x, joy.y))
            main.postDelayed({ h.up(JOY); nudging = false; if (!thumbDown) svc.setPadTouchable(true) }, ms)
        }, PASS_THROUGH_MS)
        rec("auto", "nudge_$deg", to.x, to.y, ms.toInt())
    }

    // ------------------------------------------------------------------ follow my matchup (11-12, opt-in experiment)

    private var assisting = false          // mode 1: the injected joystick points at the matchup instead of copying the thumb
    private var thumbTookOver = false      // the thumb moved since it landed: the user steers until it lifts
    private var thumbLanded = PointF()
    private var thumbAt = PointF()
    private var steerOwn = false           // mode 2: the hand's joystick finger is ours, nobody's thumb is down
    private var steerDown = false
    private var steerTo = PointF()
    private var assists = 0
    private var assistTakeovers = 0
    private var ownSteers = 0
    private var thumbUpNs = 0L             // when the real thumb last left the pad (mode 3 waits LIFT_IDLE_NS after it)
    private var liftSteers = 0             // mode 3: pushes started while the thumb was off the pad
    private var liftTakeovers = 0          // mode 3: the thumb landed on the pad during such a push and took over
    private var liftLost = 0               // mode 3: our own re-put-down landed on the pad (the game lost the push)
    private var followDeg = Float.NaN      // mode 4: the follow rule's latest direction while the thumb steers
    private var bendState = 0              // mode 4: 0 = no direction, 1 = bending the thumb, 2 = thumb points away (left alone)
    private var bends = 0                  // mode 4: stretches in which the thumb's direction was bent
    private var bendAway = 0               // mode 4: stretches in which the thumb pointed away from the matchup (left alone)
    private val steerWatch = Runnable { steerEnd("stale") }
    private var defAt = PointF()           // mode 5: the game's "수비" button (block button + DEF_DX/DEF_DY of the screen)
    private var defHolding = false         // mode 5: the hand holds the "수비" button
    private var defHolds = 0
    private var defGen = 0
    private var defTile: Tile? = null
    private var defPauseUntilNs = 0L

    /**
     * Mode 5 (11-12): hold the game's own "수비" button through the whole defence (ShotWatcher's slot reading), no
     * screen recognition of players needed: in the 16:13 practice the app saw both me and the matchup in only 27 %
     * of defence scans, so the joystick follow pushed in 13 % of the thumb-off time. A guide says holding 수비
     * "keeps you in front of the ball handler" (the game knows who that is); the user pressed it 8 times in the
     * 15:28 match (스틸 79). The joystick is not touched, so the thumb steers as always. Released for the user's own
     * right-hand buttons, before an auto block, and when the screen was not touched for [RECENT_TOUCH_NS] (menus).
     * Practice court only: in a match the ball handler is often not my matchup. Main thread.
     */
    private fun defHold() {
        val h = hand ?: return
        if (defHolding || downs == 0 || System.nanoTime() - lastTouchNs > RECENT_TOUCH_NS || System.nanoTime() < defPauseUntilNs) return
        if (tiles.any { it.fingerId >= 0 }) return   // the user is pressing a right-hand button
        val t = tileAt(defAt)
        defHolding = true; defHolds++; defTile = t
        val g = ++defGen
        if (t != null) setTileTouchable(t, false)
        rec("auto", "def_hold_on", defAt.x, defAt.y, defHolds)
        main.postDelayed({
            if (defHolding && defGen == g && !finished) {
                h.down(DEF, defAt)
                // As in mode 3: the stroke stays with the game window it went down on, so the tile takes the user's
                // own fingers again (스틸 shares it); their ACTION_DOWN lets go of 수비 (onTile).
                if (t != null) main.postDelayed({ if (defHolding && defGen == g && t.fingerId < 0) setTileTouchable(t, true) }, PASS_THROUGH_MS)
            }
        }, if (t != null) PASS_THROUGH_MS else 0L)
    }

    private fun defRelease(why: String) {
        defHolding = false; defGen++
        // A block or the user's own button: leave its animation alone for a moment before holding again.
        if (why == "block" || why == "tile") defPauseUntilNs = System.nanoTime() + DEF_PAUSE_NS
        hand?.up(DEF)
        val t = defTile; defTile = null
        rec("auto", "def_hold_off_$why", 0f, 0f, 0)
        if (t != null) main.postDelayed({ if (!defHolding && t.fingerId < 0 && System.nanoTime() >= t.armedUntilNs) setTileTouchable(t, true) }, 150)
    }

    /**
     * Mode 4: the thumb's own direction (from where it landed), turned by at most [MAX_BEND_DEG] towards [followDeg].
     * A thumb more than [BEND_KEEP_DEG] away from the matchup's direction means the user goes elsewhere on purpose:
     * left alone. Length kept, so the user's speed is his. Main thread.
     */
    private fun bent(p: PointF): PointF {
        val d = followDeg
        if (d.isNaN()) return p
        val vx = (p.x - thumbLanded.x).toDouble(); val vy = (p.y - thumbLanded.y).toDouble()
        val mag = Math.hypot(vx, vy)
        if (mag < deadZone) return p
        val a1 = Math.toDegrees(Math.atan2(-vy, vx))          // screen y grows downwards
        val diff = ((d - a1) % 360.0 + 540.0) % 360.0 - 180.0
        if (Math.abs(diff) > BEND_KEEP_DEG) {
            if (bendState != 2) { bendState = 2; bendAway++; rec("auto", "bend_away", p.x, p.y, diff.toInt()) }
            return p
        }
        if (bendState != 1) { bendState = 1; bends++; rec("auto", "bend_on", p.x, p.y, diff.toInt()) }
        val a = Math.toRadians(a1 + diff.coerceIn(-MAX_BEND_DEG, MAX_BEND_DEG))
        return PointF(thumbLanded.x + (mag * Math.cos(a)).toFloat(), thumbLanded.y - (mag * Math.sin(a)).toFloat())
    }

    /**
     * [Bench.Config.follow], PRACTICE COURT ONLY, never tried on the phone (written 11-12 without one). Main thread,
     * called after every matchup scan (about 10 Hz) with FollowRule's answer: [deg] = push the joystick that way
     * (0 = right on the screen, 90 = up), NaN = let go. Without a call for [STEER_STALE_MS] the push ends by itself.
     *
     * mode 1 (the user plays): only while the real thumb RESTS on the pad where it landed (within [deadZone]). The
     *   injected joystick stroke - the one that normally copies the thumb, the path measured in the real game - then
     *   points at the matchup. The moment the thumb moves, the stroke copies the thumb again and stays the user's
     *   until the thumb lifts. Thumb not on the pad -> nothing.
     * mode 2 (nobody holds the phone, adb self-tests): the app puts its own joystick finger down like [nudgeJoy].
     *   The pad is pass-through meanwhile, so a real thumb would land on the game unseen: not for playing.
     * mode 3 (11-12, "lift to follow", the user plays): mode 1 does nothing in a real match (0-4z17: the thumb moves
     *   12-33 ms after landing). Here the app pushes only while the thumb is OFF the pad, at least [LIFT_IDLE_NS]
     *   after it lifted, and only within [RECENT_TOUCH_NS] of a touch (playing, not in a menu). It puts its own
     *   joystick finger down like mode 2, then makes the pad touchable again: the injected stroke stays with the game
     *   window it went down on (the right-hand tiles rely on the same rule), and a real thumb lands on the pad,
     *   whose ACTION_DOWN ends the push before the normal proxy starts. A blind window of about 2 x
     *   [PASS_THROUGH_MS] per push start remains, when a thumb would land on the game directly.
     * mode 4 (11-12, "bend", the user asked for it after 0-4z20: the thumb was on the pad at 112 of 120 releases):
     *   modes 1 and 3, plus while the thumb steers its direction is turned towards the matchup ([bent]). This breaks
     *   "the thumb always wins" a little, on purpose; never more than [MAX_BEND_DEG], never against a thumb that
     *   points away.
     * Unknown: whether the game's joystick centre is where the finger lands (assumed here) or fixed.
     */
    fun steer(deg: Float, mode: Int) {
        val h = hand
        if (finished || h == null || nudging) return
        if (deg.isNaN()) { steerEnd("rule"); return }
        main.removeCallbacks(steerWatch); main.postDelayed(steerWatch, STEER_STALE_MS)
        if (mode == 5) { defHold(); return }
        val dm = svc.resources.displayMetrics
        val r = 0.13f * min(dm.widthPixels, dm.heightPixels)
        val a = Math.toRadians(deg.toDouble())
        val ox = r * Math.cos(a).toFloat(); val oy = -r * Math.sin(a).toFloat()
        if (thumbDown) {
            if ((mode == 1 || mode == 4) && joyStarted && !thumbTookOver) {
                if (!assisting) { assisting = true; assists++; rec("auto", "assist_on", thumbLanded.x, thumbLanded.y, deg.toInt()) }
                h.move(JOY, PointF(thumbLanded.x + ox, thumbLanded.y + oy))
            } else if (mode == 4 && joyStarted && thumbTookOver) {
                followDeg = deg
                h.move(JOY, bent(thumbAt))
            }
            return
        }
        if (mode < 2) return
        steerTo = PointF(joy.x + ox, joy.y + oy)
        if (steerOwn) { if (steerDown) h.move(JOY, steerTo); return }
        val now = System.nanoTime()
        val lift = mode >= 3
        if (lift && (downs == 0 || now - thumbUpNs < LIFT_IDLE_NS || now - lastTouchNs > RECENT_TOUCH_NS)) return
        steerOwn = true; steerDown = false
        if (lift) liftSteers++ else ownSteers++
        svc.setPadTouchable(false)
        rec("auto", if (lift) "lift_steer_on" else "steer_on", steerTo.x, steerTo.y, deg.toInt())
        main.postDelayed({
            if (steerOwn && !steerDown && !thumbDown) {
                steerDown = true; h.down(JOY, steerTo, anchor = PointF(joy.x, joy.y))
                // mode 3: the stroke is with the game now; let a real thumb land on the pad again (it ends the push).
                if (lift) main.postDelayed({ if (steerOwn && !thumbDown) svc.setPadTouchable(true) }, PASS_THROUGH_MS)
            }
        }, PASS_THROUGH_MS)
    }

    private fun steerEnd(why: String) {
        main.removeCallbacks(steerWatch)
        val h = hand
        followDeg = Float.NaN
        if (bendState != 0) {
            val was = bendState; bendState = 0
            rec("auto", "bend_off_$why", thumbAt.x, thumbAt.y, was)
            if (was == 1 && h != null && thumbDown && joyStarted) h.move(JOY, thumbAt)
        }
        if (assisting) {
            assisting = false
            rec("auto", "assist_off_$why", thumbAt.x, thumbAt.y, 0)
            if (h != null && thumbDown && joyStarted) h.move(JOY, thumbAt)
        }
        if (steerOwn) {
            steerOwn = false
            if (steerDown) h?.up(JOY)
            steerDown = false
            rec("auto", "steer_off_$why", 0f, 0f, 0)
            if (!thumbDown) svc.setPadTouchable(true)
        }
        if (defHolding && why != "thumb") defRelease(why)   // mode 5 holds a button, not the joystick: a landing thumb keeps it
    }

    private val deadZone get() = 0.035f * min(svc.resources.displayMetrics.widthPixels, svc.resources.displayMetrics.heightPixels)

    // ------------------------------------------------------------------ button overlays (11-10)

    /**
     * Transparent overlays over the right-hand buttons (패스/가속/슛/스틸/블록/스킬). A real finger on the game while the
     * proxy joystick is held stops the character (user report on v1022), so these catch that finger instead and the
     * hand injects it at the same spot, in the joystick's own gesture. Four tiles so two right-hand fingers can work.
     */
    private class Tile(val id: Int, val view: View, val lp: WindowManager.LayoutParams, val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        var touchable = true
        var fingerId = -1
        var armedUntilNs = 0L
        var openedNs = 0L
        var started = false
        var downAt = PointF()
    }
    private val tiles = ArrayList<Tile>()
    private var lastTouchNs = 0L
    private var tileDowns = 0
    private var tileQuickTaps = 0
    private var injectedOnTile = 0

    private fun tileAt(p: PointF): Tile? = tiles.firstOrNull { p.x >= it.x0 && p.x < it.x1 && p.y >= it.y0 && p.y < it.y1 }

    private fun setTileTouchable(t: Tile, on: Boolean) {
        if (t.touchable == on) return
        t.touchable = on
        if (!on) t.openedNs = System.nanoTime()
        t.lp.flags = if (on) t.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else t.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        svc.updateOverlay(t.view, t.lp)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("ClickableViewAccessibility")
    private fun showButtonTiles() {
        val p = android.graphics.Point()
        svc.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(p)
        if (p.x <= p.y) { rec("ctl", "tiles_skipped_portrait", 0f, 0f, 0); return }
        val left = maxOf((0.60f * p.x).toInt(), (joy.x + 0.25f * p.x).toInt())
        val top = (0.30f * p.y).toInt()
        val midX = (left + p.x) / 2; val midY = (top + p.y) / 2
        val rects = listOf(intArrayOf(left, top, midX, midY), intArrayOf(midX, top, p.x, midY), intArrayOf(left, midY, midX, p.y), intArrayOf(midX, midY, p.x, p.y))
        for ((i, r) in rects.withIndex()) {
            val id = i + 1
            val v = object : View(svc) {
                private val edge = Paint().apply { color = Color.argb(60, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
                override fun onDraw(canvas: Canvas) { canvas.drawRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, edge) }
            }
            val lp = svc.addOverlay(v, r[0], r[1], r[2] - r[0], r[3] - r[1]) ?: continue
            val t = Tile(id, v, lp, r[0], r[1], r[2], r[3])
            v.setOnTouchListener { _, e -> onTile(t, e); true }
            tiles.add(t)
        }
        rec("ctl", "tiles_shown", left.toFloat(), top.toFloat(), tiles.size)
    }

    private fun onTile(t: Tile, e: MotionEvent) {
        if (e.deviceId == -1) { injectedOnTile++; rec("tile${t.id}", "injected_landed", e.rawX, e.rawY, -1); return }
        val h = hand ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchNs = System.nanoTime(); tileDowns++
                if (defHolding) defRelease("tile")   // mode 5: the user's own button wins
                val fid = 100 + t.id
                t.fingerId = fid; t.started = false; t.downAt = PointF(e.rawX, e.rawY)
                rec("tile${t.id}", "DOWN", e.rawX, e.rawY, e.deviceId)
                val wasTouchable = t.touchable
                setTileTouchable(t, false)
                main.postDelayed({
                    if (t.fingerId == fid) { t.started = true; h.down(fid, t.downAt) }
                    else { // lifted before the pass-through applied: a quick tap
                        tileQuickTaps++; h.tap(t.downAt, auto = false)
                        main.postDelayed({ if (t.fingerId < 0 && System.nanoTime() >= t.armedUntilNs) setTileTouchable(t, true) }, 80)
                    }
                }, if (wasTouchable) PASS_THROUGH_MS else 0L)
            }
            MotionEvent.ACTION_MOVE -> { t.downAt = PointF(e.rawX, e.rawY); if (t.started) h.move(t.fingerId, t.downAt) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastTouchNs = System.nanoTime()
                rec("tile${t.id}", MotionEvent.actionToString(e.actionMasked), e.rawX, e.rawY, e.deviceId)
                val started = t.started
                if (started) h.up(t.fingerId)
                t.fingerId = -1; t.started = false
                // A started finger's injected stream stays with the game window; the tile can take the next finger now.
                if (started && System.nanoTime() >= t.armedUntilNs) setTileTouchable(t, true)
            }
        }
    }

    /** Uses the saved calibration (fractions of the landscape screen). False if there is none or the screen is portrait. */
    @Suppress("DEPRECATION")
    private fun loadCalibration(): Boolean {
        val sp = svc.getSharedPreferences("calib", 0)
        if (!sp.contains("bx")) return false
        val p = android.graphics.Point()
        svc.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(p)
        if (p.x <= p.y) return false
        joy = PointF(sp.getFloat("jx", 0f) * p.x, sp.getFloat("jy", 0f) * p.y)
        block = PointF(sp.getFloat("bx", 0f) * p.x, sp.getFloat("by", 0f) * p.y)
        rec("calib", "loaded", block.x, block.y, p.x)
        return true
    }

    private val main = Handler(Looper.getMainLooper())
    private val log = StringBuilder("t_ns,source,event,x,y,device\n")
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
    private var bubble: TextView? = null
    private var calib: View? = null
    private var padShown = false
    private var hand: ProxyHand? = null
    private var joy = PointF()
    private var block = PointF()
    private var step = 0
    private var thumbDown = false
    private var downs = 0
    private var padCancels = 0
    private var injectedOnPad = 0
    private var autoTaps = 0
    private var finished = false
    private val tapEveryMs = 2500L
    private var joyStarted = false

    companion object {
        const val JOY = 0
        /** Wait after setting an overlay pass-through before injecting under it (the measured ⑦ value). */
        const val PASS_THROUGH_MS = 40L
        /** Auto-block only while the user touched one of our overlays this recently (playing, not in a menu). */
        const val RECENT_TOUCH_NS = 30_000_000_000L    // vc1031: 8 s skipped 3 of 11 shot presses in practice
        /** The follow push ends when no new direction arrived for this long (the matchup scan runs every 105 ms). */
        const val STEER_STALE_MS = 400L
        /** Follow mode 3 starts a push only this long after the thumb left the pad (a quick re-grip is not a lift). Guess. */
        const val LIFT_IDLE_NS = 400_000_000L
        /**
         * Follow mode 4: at most this much turn of the thumb's direction towards the matchup. Was 30 (a guess). Measured
         * on six follow-4 matches (11-12/13, scratchpad follow\s4.py): far from the matchup (> 85 px) my approach speed
         * was 131 px/s with the thumb within 30 deg of the matchup but 97 px/s with a thumb 30-120 deg off that got
         * only 30 deg of turn; direction mapping right (stick angle = ground angle, bias 0). Replay (follow\sim.py,
         * my recorded motion rotated by the change of stick angle): closing rate -8.2 -> -16.6 px/s on the 3 matches
         * it was chosen on, -12.5 -> -20.3 on the 3 held-out ones, -28 -> -33 on 7 follow-off matches. 90: a thumb
         * up to 90 deg off now points straight at the matchup; [BEND_KEEP_DEG] still leaves an away thumb alone.
         */
        const val MAX_BEND_DEG = 90.0
        /** Follow mode 4: a thumb further than this from the matchup's direction is left alone (the user goes elsewhere). */
        const val BEND_KEEP_DEG = 120.0
        /** Follow mode 5: the hand's finger id for the "수비" button (tiles use 101-104). */
        const val DEF = 2
        /**
         * The "수비" button = block button + (-DEF_DX W, -DEF_DY H). Read off the 11-12 16:13 practice frame
         * (f0106, 960x443): block icon (840, 370), 수비 icon centre about (757, 292).
         */
        const val DEF_DX = 0.086f
        const val DEF_DY = 0.18f
        /** Mode 5: no new 수비 hold for this long after a block or the user's own button (a guess, not measured). */
        const val DEF_PAUSE_NS = 600_000_000L
    }

    private val autoTap = object : Runnable {
        override fun run() {
            val s = hand ?: return
            if (!thumbDown || !s.isHolding(JOY)) return
            s.tap(PointF(block.x, block.y))
            autoTaps++
            bubble?.text = "⑦ 블록 자동 ${autoTaps}회 · 여기를 눌러 끝내기"
            main.postDelayed(this, tapEveryMs)
        }
    }

    private fun rec(src: String, ev: String, x: Float, y: Float, dev: Int) {
        synchronized(log) { if (log.length < 8_000_000) log.append(System.nanoTime()).append(',').append(src).append(',').append(ev).append(',').append(x.toInt()).append(',').append(y.toInt()).append(',').append(dev).append('\n') }
    }

    fun begin() {
        val tv = TextView(svc).apply {
            text = if (autoMode) "⑧ 경기 화면(가로)이 나오면 여기를 누르세요" else "⑦ 게임(연습 모드)을 연 뒤 여기를 누르세요"
            setTextColor(Color.WHITE); textSize = 15f
            setBackgroundColor(Color.argb(210, 20, 20, 20)); setPadding(32, 20, 32, 20)
            setOnClickListener { onBubble() }
        }
        if (svc.addOverlay(tv, 0, 40, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL) == null) {
            Bench.lastSummary = "⑦: 화면 위 버튼을 띄우지 못했습니다(접근성 오버레이 거부)."
            svc.inGame = null
            return
        }
        bubble = tv
        rec("ctl", "begin", 0f, 0f, 0)
    }

    private fun onBubble() {
        when {
            step == 0 && autoMode && !Bench.config.recalibrate && loadCalibration() -> { step = 3; showPad() }
            step == 0 -> showCalibration()
            padShown -> finish("사용자 종료")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCalibration() {
        step = 1
        bubble?.text = "① 조이스틱 중심을 한 번 누르세요"
        val v = object : View(svc) {
            private val p = Paint().apply { color = Color.WHITE; textSize = 44f; isAntiAlias = true }
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.argb(70, 0, 0, 0))
                canvas.drawText(if (step == 1) "① 조이스틱 중심을 누르세요" else "② 블록 버튼을 누르세요", 60f, height * 0.25f, p)
            }
        }
        v.setOnTouchListener { view, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                if (step == 1) {
                    joy = PointF(e.rawX, e.rawY); rec("calib", "joystick", e.rawX, e.rawY, e.deviceId)
                    step = 2; bubble?.text = "② 블록 버튼을 한 번 누르세요"; view.invalidate()
                } else if (step == 2) {
                    block = PointF(e.rawX, e.rawY); rec("calib", "block", e.rawX, e.rawY, e.deviceId)
                    saveCalibration()
                    step = 3
                    svc.removeOverlay(view); calib = null
                    main.post { showPad() }
                }
            }
            true
        }
        if (svc.addOverlay(v, 0, 0, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) == null) {
            finish("보정 화면을 띄우지 못함"); return
        }
        calib = v
    }

    /** Stores the calibration as fractions of the (landscape) screen so ① can find the block button. */
    @Suppress("DEPRECATION")
    private fun saveCalibration() {
        try {
            val p = android.graphics.Point()
            svc.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(p)
            if (p.x <= 0 || p.y <= 0) return
            val c = Bench.config.copy(blockFx = block.x / p.x, blockFy = block.y / p.y, joyFx = joy.x / p.x, joyFy = joy.y / p.y)
            Bench.config = c
            svc.getSharedPreferences("calib", 0).edit()
                .putFloat("bx", c.blockFx).putFloat("by", c.blockFy).putFloat("jx", c.joyFx).putFloat("jy", c.joyFy).apply()
            rec("calib", "saved_fractions", c.blockFx * 1000, c.blockFy * 1000, p.x)
        } catch (_: Throwable) {}
    }

    private fun showPad() {
        val dm = svc.resources.displayMetrics
        val side = (0.36f * min(dm.widthPixels, dm.heightPixels)).toInt()
        val left = (joy.x - side / 2f).toInt()
        val top = (joy.y - side / 2f).toInt()
        val ok = svc.showPad(left, top, side, side, "", outline = true) { e, _ -> onPad(e) }
        if (!ok) { finish("패드를 띄우지 못함"); return }
        padShown = true
        val rp = android.graphics.Point()
        @Suppress("DEPRECATION")
        svc.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(rp)
        defAt = PointF(block.x - DEF_DX * rp.x, block.y - DEF_DY * rp.y)
        hand = svc.newProxyHand(16) { what -> rec("proxy", what, 0f, 0f, 0) }
        if (autoMode) { showMarkButton(); showButtonTiles() }
        bubble?.text = if (autoMode) "⑧ 자동 블록 켜짐 · 여기를 눌러 끝내기"
        else "⑦ 평소처럼 조이스틱으로 움직이세요 · ${tapEveryMs / 1000.0}초마다 자동 블록 · 여기를 눌러 끝내기"
        rec("ctl", "pad_shown", joy.x, joy.y, side)
    }

    private fun onPad(e: MotionEvent) {
        rec("pad", MotionEvent.actionToString(e.actionMasked), e.rawX, e.rawY, e.deviceId)
        if (e.deviceId == -1) {   // our own injection landed on the pad: pass-through failed
            injectedOnPad++
            // Follow mode 3 keeps the pad touchable during its push; a re-put-down after a cancel lands here, not in the game.
            if (steerOwn && e.actionMasked == MotionEvent.ACTION_DOWN) { liftLost++; steerEnd("injpad") }
            return
        }
        val s = hand ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (steerOwn) liftTakeovers++  // only follow mode 3 leaves the pad touchable while the app steers
                steerEnd("thumb")           // the thumb always wins
                thumbDown = true; downs++; lastTouchNs = System.nanoTime()
                svc.setPadTouchable(false)
                val p = PointF(e.rawX, e.rawY)
                thumbLanded = p; thumbAt = p; thumbTookOver = false
                val n = downs
                // Give the window manager a moment to apply the pass-through flag before injecting underneath.
                // Put down where the thumb landed; after a cancel the hand puts it down there again (ProxyStick behaviour).
                main.postDelayed({ if (thumbDown && downs == n) { s.down(JOY, p, anchor = p); joyStarted = true; if (!autoMode) main.postDelayed(autoTap, tapEveryMs) } }, PASS_THROUGH_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                thumbAt = PointF(e.rawX, e.rawY)
                if (!thumbTookOver && Math.hypot((thumbAt.x - thumbLanded.x).toDouble(), (thumbAt.y - thumbLanded.y).toDouble()) > deadZone) {
                    thumbTookOver = true
                    if (assisting) { assisting = false; assistTakeovers++; rec("auto", "assist_off_thumb", thumbAt.x, thumbAt.y, 0) }
                }
                // Without the follow experiment [assisting] is never set: the stroke copies the thumb as before.
                if (!assisting) s.move(JOY, bent(thumbAt))   // bent() is the identity unless follow mode 4 has a direction
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) padCancels++
                if (assisting) { assisting = false; rec("auto", "assist_off_lift", e.rawX, e.rawY, 0) }
                thumbDown = false; lastTouchNs = System.nanoTime(); thumbUpNs = lastTouchNs
                main.removeCallbacks(autoTap)
                if (joyStarted) s.up(JOY)
                joyStarted = false
                svc.setPadTouchable(true)
            }
        }
    }

    fun finish(reason: String) {
        if (finished) return
        steerEnd("finish")
        finished = true
        main.removeCallbacks(autoTap)
        hand?.releaseAll()
        svc.hidePad()
        svc.removeOverlay(calib)
        svc.removeOverlay(bubble)
        svc.removeOverlay(markBubble)
        for (t in tiles) svc.removeOverlay(t.view)
        val s = hand
        val o = s?.taps
        val report = buildString {
            appendLine("== $tag 인게임 대리 조이스틱 (측정값 + 사용자 관찰) ==")
            appendLine("기기: ${DeviceInfo.short(svc)}")
            appendLine("종료: $reason")
            appendLine("보정: 조이스틱 (${joy.x.toInt()}, ${joy.y.toInt()}), 블록 버튼 (${block.x.toInt()}, ${block.y.toInt()})")
            appendLine("엄지 누름 ${downs}회, 엄지 끊김(패드가 CANCEL 받음) ${padCancels}회")
            appendLine("주입이 게임이 아니라 패드에 떨어진 이벤트: ${injectedOnPad}개 ${if (injectedOnPad == 0) "(통과 방식 정상)" else "(통과 방식 실패)"}")
            appendLine("대리 입력 세그먼트 ${s?.segments ?: 0}개, 취소 ${s?.cancels ?: 0}회, 거부 ${s?.refused ?: 0}회, 1px 흔들기 ${s?.wiggles ?: 0}, 동시에 누른 손가락 최대 ${s?.maxFingers ?: 0}개")
            if (tiles.isNotEmpty()) appendLine("오른쪽 버튼 받침판 ${tiles.size}개: 손가락 ${tileDowns}회(짧은 탭 ${tileQuickTaps}회), 주입이 받침판에 떨어짐 ${injectedOnTile}개 ${if (injectedOnTile == 0) "(정상)" else "(통과 실패)"}")
            appendLine("자동 블록 요청 ${autoTaps}회, 시스템 응답 완료 ${o?.get(1) ?: 0} / 취소 ${o?.get(2) ?: 0}" +
                if (autoMode) ", 화면을 ${RECENT_TOUCH_NS / 1_000_000_000L}초 넘게 안 만져서 건너뜀 ${skippedFires}회, 사용자 기록 버튼 ${marks}회" else "")
            if (Bench.config.follow > 0) appendLine("매치업 따라가기(시험, 방식 ${Bench.config.follow}): 엄지를 올려 둔 동안 대신 밀기 ${assists}회(엄지를 움직여 사용자 조작으로 돌아감 ${assistTakeovers}회), 손 없이 밀기 ${ownSteers}회, " +
                "엄지를 뗀 동안 밀기 ${liftSteers}회(엄지를 대서 사용자 조작으로 돌아감 ${liftTakeovers}회, 앱 손가락이 패드에 떨어져 끊김 ${liftLost}회), " +
                "엄지 방향 섞기 ${bends}번(엄지가 반대쪽이라 안 섞음 ${bendAway}번), '수비' 버튼 누르고 있기 ${defHolds}번, 수비 동안 블록 버튼 열어 둠 ${blockOpens}번")
            appendLine("※ 게임 캐릭터가 실제로 움직이고 블록했는지는 앱이 볼 수 없습니다. 관찰 내용을 알려 주세요.")
        }
        if (autoMode) Bench.autoReport = report
        if (!autoMode || !Bench.running) Bench.lastSummary = report
        try {
            val dir = File(svc.getExternalFilesDir("sessions"), "${stamp}_ingameproxy").apply { mkdirs() }
            File(dir, "ingame_proxy_summary.txt").writeText(report)
            File(dir, "ingame_proxy_events.csv").writeText(synchronized(log) { log.toString() })
            val exported = Exporter.copyToDownloads(svc, dir)
            if (!autoMode || !Bench.running) Bench.lastExportDir = exported
        } catch (_: Throwable) {}
        svc.inGame = null
        // ⑧: the bubble ends the whole run, including the capture session (which then writes the summary).
        if (autoMode && Bench.running) CaptureService.stop(svc)
    }
}
