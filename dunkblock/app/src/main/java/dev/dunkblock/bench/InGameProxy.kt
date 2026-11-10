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
class InGameProxy(private val svc: BlockAccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val log = StringBuilder("t_ns,source,event,x,y,device\n")
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
    private var bubble: TextView? = null
    private var calib: View? = null
    private var padShown = false
    private var stick: BlockAccessibilityService.ProxyStick? = null
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

    private val autoTap = object : Runnable {
        override fun run() {
            val s = stick ?: return
            if (!thumbDown || !s.active) return
            s.tap(PointF(block.x, block.y), 1)
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
            text = "⑦ 게임(연습 모드)을 연 뒤 여기를 누르세요"
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

    private fun showPad() {
        val dm = svc.resources.displayMetrics
        val side = (0.36f * min(dm.widthPixels, dm.heightPixels)).toInt()
        val left = (joy.x - side / 2f).toInt()
        val top = (joy.y - side / 2f).toInt()
        val ok = svc.showPad(left, top, side, side, "") { e, _ -> onPad(e) }
        if (!ok) { finish("패드를 띄우지 못함"); return }
        padShown = true
        stick = svc.newProxyStick(16) { what, _ -> rec("proxy", what, 0f, 0f, 0) }
        bubble?.text = "⑦ 평소처럼 조이스틱으로 움직이세요 · ${tapEveryMs / 1000.0}초마다 자동 블록 · 여기를 눌러 끝내기"
        rec("ctl", "pad_shown", joy.x, joy.y, side)
    }

    private fun onPad(e: MotionEvent) {
        rec("pad", MotionEvent.actionToString(e.actionMasked), e.rawX, e.rawY, e.deviceId)
        if (e.deviceId == -1) { injectedOnPad++; return } // our own injection landed on the pad: pass-through failed
        val s = stick ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                thumbDown = true; downs++
                svc.setPadTouchable(false)
                val p = PointF(e.rawX, e.rawY)
                // Give the window manager a moment to apply the pass-through flag before injecting underneath.
                main.postDelayed({ if (thumbDown) { s.start(p); main.postDelayed(autoTap, tapEveryMs) } }, 40)
            }
            MotionEvent.ACTION_MOVE -> s.target = PointF(e.rawX, e.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) padCancels++
                thumbDown = false
                main.removeCallbacks(autoTap)
                s.stop()
                svc.setPadTouchable(true)
            }
        }
    }

    fun finish(reason: String) {
        if (finished) return
        finished = true
        main.removeCallbacks(autoTap)
        stick?.stop()
        svc.hidePad()
        svc.removeOverlay(calib)
        svc.removeOverlay(bubble)
        val s = stick
        val o = s?.tapOutcome?.get(1)
        val report = buildString {
            appendLine("== ⑦ 인게임 대리 조이스틱 (측정값 + 사용자 관찰) ==")
            appendLine("기기: ${DeviceInfo.short(svc)}")
            appendLine("종료: $reason")
            appendLine("보정: 조이스틱 (${joy.x.toInt()}, ${joy.y.toInt()}), 블록 버튼 (${block.x.toInt()}, ${block.y.toInt()})")
            appendLine("엄지 누름 ${downs}회, 엄지 끊김(패드가 CANCEL 받음) ${padCancels}회")
            appendLine("주입이 게임이 아니라 패드에 떨어진 이벤트: ${injectedOnPad}개 ${if (injectedOnPad == 0) "(통과 방식 정상)" else "(통과 방식 실패)"}")
            appendLine("대리 조이스틱 세그먼트 ${s?.segments ?: 0}개, 취소 ${s?.cancels ?: 0}회 (탭 없이 취소 ${s?.cancelsWithoutTap ?: 0}회), 1px 흔들기 ${s?.wiggles ?: 0}")
            appendLine("자동 블록 요청 ${autoTaps}회, 시스템 응답 완료 ${o?.get(1) ?: 0} / 취소 ${o?.get(2) ?: 0}")
            appendLine("※ 게임 캐릭터가 실제로 움직이고 블록했는지는 앱이 볼 수 없습니다. 관찰 내용을 알려 주세요.")
        }
        Bench.lastSummary = report
        try {
            val dir = File(svc.getExternalFilesDir("sessions"), "${stamp}_ingameproxy").apply { mkdirs() }
            File(dir, "ingame_proxy_summary.txt").writeText(report)
            File(dir, "ingame_proxy_events.csv").writeText(synchronized(log) { log.toString() })
            Bench.lastExportDir = Exporter.copyToDownloads(svc, dir)
        } catch (_: Throwable) {}
        svc.inGame = null
    }
}
