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
                Bench.clipRequester?.invoke("mark", 3.4, 0.4)
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
    fun fireBlock(reason: String): Boolean {
        val h = hand
        val recent = System.nanoTime() - lastTouchNs < RECENT_TOUCH_NS
        if (finished || h == null || !recent) { skippedFires++; rec("auto", "skip_${reason}_${if (h == null) "no_pad" else "idle"}", 0f, 0f, 0); return false }
        val t = tileAt(block)
        val delay = if (t != null && t.touchable) { setTileTouchable(t, false); PASS_THROUGH_MS } else 0L
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
        t.armedUntilNs = System.nanoTime() + ms * 1_000_000L
        if (t.touchable) setTileTouchable(t, false)
        main.postDelayed({ if (t.fingerId < 0 && System.nanoTime() >= t.armedUntilNs) setTileTouchable(t, true) }, ms + 10)
    }

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
        const val RECENT_TOUCH_NS = 8_000_000_000L
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
        val ok = svc.showPad(left, top, side, side, "") { e, _ -> onPad(e) }
        if (!ok) { finish("패드를 띄우지 못함"); return }
        padShown = true
        hand = svc.newProxyHand(16) { what -> rec("proxy", what, 0f, 0f, 0) }
        if (autoMode) { showMarkButton(); showButtonTiles() }
        bubble?.text = if (autoMode) "⑧ 자동 블록 켜짐 · 여기를 눌러 끝내기"
        else "⑦ 평소처럼 조이스틱으로 움직이세요 · ${tapEveryMs / 1000.0}초마다 자동 블록 · 여기를 눌러 끝내기"
        rec("ctl", "pad_shown", joy.x, joy.y, side)
    }

    private fun onPad(e: MotionEvent) {
        rec("pad", MotionEvent.actionToString(e.actionMasked), e.rawX, e.rawY, e.deviceId)
        if (e.deviceId == -1) { injectedOnPad++; return } // our own injection landed on the pad: pass-through failed
        val s = hand ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                thumbDown = true; downs++; lastTouchNs = System.nanoTime()
                svc.setPadTouchable(false)
                val p = PointF(e.rawX, e.rawY)
                val n = downs
                // Give the window manager a moment to apply the pass-through flag before injecting underneath.
                // Put down where the thumb landed; after a cancel the hand puts it down there again (ProxyStick behaviour).
                main.postDelayed({ if (thumbDown && downs == n) { s.down(JOY, p, anchor = p); joyStarted = true; if (!autoMode) main.postDelayed(autoTap, tapEveryMs) } }, PASS_THROUGH_MS)
            }
            MotionEvent.ACTION_MOVE -> s.move(JOY, PointF(e.rawX, e.rawY))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) padCancels++
                thumbDown = false; lastTouchNs = System.nanoTime()
                main.removeCallbacks(autoTap)
                if (joyStarted) s.up(JOY)
                joyStarted = false
                svc.setPadTouchable(true)
            }
        }
    }

    fun finish(reason: String) {
        if (finished) return
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
