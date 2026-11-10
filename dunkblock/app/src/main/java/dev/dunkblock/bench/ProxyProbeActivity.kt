package dev.dunkblock.bench

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import dev.dunkblock.core.Stats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * ⑥ "Proxy joystick" experiment — can manual movement and automatic block coexist if the APP injects both?
 *
 *  A. The finger rests on an accessibility OVERLAY pad (a different window). Plain taps are injected elsewhere.
 *     Is the finger on the overlay cancelled too? If yes, every touch-based proxy design is dead.
 *  B. The pad drives a continuously injected joystick stroke (continueStroke). Block taps are added to the same
 *     gesture. Does the injected joystick survive, does the tap land, how late does the joystick follow the finger?
 *  C. While B runs, the user presses a real "pass" button with the other hand. What breaks?
 *
 * The left half of this screen stands in for the game's joystick area; it records what the "game" would receive.
 */
class ProxyProbeActivity : Activity() {
    private lateinit var view: ProxyView
    private val main = Handler(Looper.getMainLooper())
    private var svc: BlockAccessibilityService? = null
    private var proxy: BlockAccessibilityService.ProxyStick? = null
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
    private val log = StringBuilder("t_ns,source,event,pointer,x,y,device\n")

    private enum class Phase { WAIT, A, B, C, DONE }
    private var phase = Phase.WAIT

    // screen geometry (filled after layout)
    private var padRect = IntArray(4)
    private var origin = PointF()
    private var targetPt = PointF()
    private var stickRadius = 100f

    // pad finger
    @Volatile private var padDown = false
    private var padDownPt = PointF()
    private var padCancelledAt = 0L
    private val padMoves = ArrayList<LongArray>() // t, x, y(mapped target, screen px)

    // A
    private class TrialA(val n: Int, val dispatchNs: Long) { var padCancelled = false; var padMovesAfter = 0; var tapArrived = false; var verdict = "PENDING" }
    private val trialsA = ArrayList<TrialA>()
    private val totalA = 4
    // B
    private class TrialB(val n: Int, val dispatchNs: Long, val variant: Int) {
        var tapArrived = false; var tapLatencyMs = Double.NaN; var stickCancelled = false; var padCancelled = false
        var stickGapMs = 0.0; var verdict = "PENDING"
    }
    private val trialsB = ArrayList<TrialB>()
    private val totalB = 12
    private var bStartNs = 0L
    private var bTapsScheduled = false
    // C
    private class TrialC(val n: Int, val pressNs: Long) { var stickCancelled = false; var proxyCancelled = false; var padCancelled = false; var passArrived = true }
    private val trialsC = ArrayList<TrialC>()
    private val totalC = 3
    private var proxyCancelsBefore = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = ProxyView(this)
        setContentView(view)
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        svc = BlockAccessibilityService.instance
        if (svc == null) {
            view.message = "접근성 서비스가 꺼져 있습니다. 앱의 '접근성 설정 열기'에서 DunkBlock 입력 충돌 시험을 켜세요."
            return
        }
        view.post { setup() }
    }

    override fun onDestroy() {
        proxy?.stop()
        svc?.hidePad()
        if (phase != Phase.DONE && phase != Phase.WAIT) finishTest("화면을 닫아 중간 종료")
        super.onDestroy()
    }

    private fun setup() {
        val loc = IntArray(2); view.getLocationOnScreen(loc)
        val w = view.width; val h = view.height
        padRect = intArrayOf(loc[0] + (0.02f * w).toInt(), loc[1] + (0.50f * h).toInt(), (0.26f * w).toInt(), (0.46f * h).toInt())
        origin = PointF(loc[0] + 0.36f * w, loc[1] + 0.32f * h)   // where the injected joystick lives (not under the pad)
        targetPt = PointF(loc[0] + view.targetX(), loc[1] + view.targetY())
        stickRadius = 0.16f * h
        view.originOnView = PointF(origin.x - loc[0], origin.y - loc[1])
        val ok = svc!!.showPad(padRect[0], padRect[1], padRect[2], padRect[3], "조이스틱 패드") { e, t -> onPad(e, t) }
        if (!ok) { view.message = "오버레이 패드를 띄우지 못했습니다(접근성 오버레이 거부). 결과 공유로 알려 주세요."; return }
        proxy = svc!!.newProxyStick(16) { what, t -> rec(t, "proxy", what, -1, 0f, 0f, 0) }
        phase = Phase.A
        view.message = "A단계: 왼쪽 아래 파란 패드에 손가락을 대고 떼지 말고 작은 원을 계속 그리세요.\n1.5초 뒤 오른쪽 빨간 원이 자동으로 ${totalA}번 눌립니다."
        view.invalidate()
    }

    // ------------------------------------------------------------------ input from the overlay pad

    private fun onPad(e: MotionEvent, t: Long) {
        rec(t, "pad", MotionEvent.actionToString(e.actionMasked), e.getPointerId(e.actionIndex), e.rawX, e.rawY, e.deviceId)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                padDown = true; padDownPt = PointF(e.rawX, e.rawY)
                if (phase == Phase.A) main.postDelayed({ if (padDown && phase == Phase.A && !runningA) { runningA = true; nextA() } }, 1500)
                if (phase == Phase.B || phase == Phase.C) startStick()
            }
            MotionEvent.ACTION_MOVE -> {
                val m = mapped(e.rawX, e.rawY)
                synchronized(padMoves) { padMoves.add(longArrayOf(t, m.x.toLong(), m.y.toLong())) }
                proxy?.target = m
            }
            MotionEvent.ACTION_UP -> { padDown = false; proxy?.stop() }
            MotionEvent.ACTION_CANCEL -> { padDown = false; padCancelledAt = t; proxy?.stop() }
        }
    }

    private fun mapped(rx: Float, ry: Float): PointF {
        var dx = rx - padDownPt.x; var dy = ry - padDownPt.y
        val d = hypot(dx, dy)
        if (d > stickRadius) { dx = dx / d * stickRadius; dy = dy / d * stickRadius }
        return PointF(origin.x + dx, origin.y + dy)
    }

    // ------------------------------------------------------------------ A: does an injection cancel a finger on another window?

    private var runningA = false

    private fun nextA() {
        val s = svc ?: return
        if (trialsA.size >= totalA) { beginB(); return }
        if (!padDown) { runningA = false; view.message = "A단계: 패드에서 손가락이 떨어졌습니다. 다시 패드를 누르고 원을 그리세요."; view.invalidate(); return }
        val tr = TrialA(trialsA.size + 1, System.nanoTime())
        trialsA.add(tr)
        val cancelsBefore = padCancelledAt
        s.tap(targetPt.x, targetPt.y, 30) { _, _ -> }
        main.postDelayed({
            tr.padCancelled = padCancelledAt != cancelsBefore && padCancelledAt >= tr.dispatchNs
            tr.padMovesAfter = synchronized(padMoves) { padMoves.count { it[0] > tr.dispatchNs } }
            tr.tapArrived = view.targetDowns.any { it >= tr.dispatchNs }
            tr.verdict = when {
                tr.padCancelled -> "PAD_CANCELLED"
                !padDown -> "LIFTED"
                tr.padMovesAfter == 0 -> "NO_MOVES"
                else -> "PAD_SURVIVED"
            }
            view.message = "A단계 ${tr.n}/$totalA: ${tr.verdict}" + if (tr.verdict == "PAD_CANCELLED") "\n패드 손가락이 끊겼습니다. 다시 누르고 원을 그리면 계속합니다." else ""
            view.invalidate()
            if (padDown) main.postDelayed({ nextA() }, 500) else runningA = false
        }, 450)
    }

    // ------------------------------------------------------------------ B: proxy joystick + block tap in one gesture

    private fun beginB() {
        phase = Phase.B
        runningA = false
        view.message = "B단계: 손가락을 떼고 패드를 다시 누르세요. 이제 패드로 움직이면 왼쪽 위 파란 점(대리 조이스틱)이 따라옵니다.\n원을 그리다가 2초쯤 손가락을 멈추고 가만히 누르기를 반복하세요. 2초 뒤 블록 탭 ${totalB}번이 같이 들어갑니다."
        view.invalidate()
        if (padDown) startStick()
    }

    private fun startStick() {
        val p = proxy ?: return
        if (p.active) return
        p.start(PointF(origin.x, origin.y))
        if (phase == Phase.B && !bTapsScheduled) {
            bTapsScheduled = true
            bStartNs = System.nanoTime()
            main.postDelayed({ nextB() }, 2000)
        }
    }

    private fun nextB() {
        val p = proxy ?: return
        if (trialsB.size >= totalB) { beginC(); return }
        if (!p.active || !padDown) {
            view.message = "B단계: 대리 조이스틱이 멈췄습니다(${if (!padDown) "패드 손가락 끊김/뗌" else "주입 중단"}). 패드를 다시 누르면 계속합니다."
            view.invalidate()
            bTapsScheduled = false
            return
        }
        val tr = TrialB(trialsB.size + 1, System.nanoTime(), trialsB.size % 3)
        trialsB.add(tr)
        val padCancelBefore = padCancelledAt
        val stickCancelBefore = view.stickCancels
        p.tap(PointF(targetPt.x, targetPt.y), tr.variant)
        main.postDelayed({
            val down = view.targetDowns.firstOrNull { it >= tr.dispatchNs }
            tr.tapArrived = down != null
            if (down != null) tr.tapLatencyMs = (down - tr.dispatchNs) / 1e6
            tr.stickCancelled = view.stickCancels != stickCancelBefore
            tr.padCancelled = padCancelledAt != padCancelBefore && padCancelledAt >= tr.dispatchNs
            tr.stickGapMs = view.maxStickGapMs(tr.dispatchNs - 100_000_000L, System.nanoTime())
            tr.verdict = when {
                tr.padCancelled -> "PAD_CANCELLED"
                tr.stickCancelled -> "STICK_CANCELLED"
                !tr.tapArrived -> "TAP_LOST"
                else -> "BOTH_OK"
            }
            view.message = "B단계 ${tr.n}/$totalB (탭 방식 ${tr.variant}): ${tr.verdict} (탭 도착 ${if (tr.tapArrived) "%.1fms".format(tr.tapLatencyMs) else "없음"}, 조이스틱 최대 공백 ${"%.0f".format(tr.stickGapMs)}ms)"
            view.invalidate()
            main.postDelayed({ nextB() }, 800)
        }, 400)
    }

    // ------------------------------------------------------------------ C: real press elsewhere while the proxy runs

    private fun beginC() {
        phase = Phase.C
        proxyCancelsBefore = proxy?.cancels ?: 0
        view.showPass = true
        view.message = "C단계: 패드로 계속 원을 그리면서, 다른 손가락으로 오른쪽 아래 노란 '패스' 버튼을 ${totalC}번 눌러 보세요(한 번에 하나씩, 2초 간격)."
        view.invalidate()
        main.postDelayed({ if (phase == Phase.C) finishTest("C단계 시간 초과(20초)") }, 20_000)
    }

    fun onPassPressed(t: Long) {
        if (phase != Phase.C) return
        val tr = TrialC(trialsC.size + 1, t)
        trialsC.add(tr)
        val stickBefore = view.stickCancels
        val proxyBefore = proxy?.cancels ?: 0
        val padBefore = padCancelledAt
        main.postDelayed({
            tr.stickCancelled = view.stickCancels != stickBefore
            tr.proxyCancelled = (proxy?.cancels ?: 0) != proxyBefore
            tr.padCancelled = padCancelledAt != padBefore && padCancelledAt >= tr.pressNs
            view.message = "C단계 ${tr.n}/$totalC: 대리 조이스틱 ${if (tr.stickCancelled || tr.proxyCancelled) "끊김" else "유지"}, 패드 손가락 ${if (tr.padCancelled) "끊김" else "유지"}"
            view.invalidate()
            if (trialsC.size >= totalC) main.postDelayed({ finishTest("완료") }, 600)
        }, 350)
    }

    // ------------------------------------------------------------------ report

    private fun lagEstimate(): Pair<Double, Double> {
        // Cross-correlate pad-mapped target x(t) with the joystick x(t) the "game" view received, during B.
        val pm = synchronized(padMoves) { padMoves.filter { it[0] >= bStartNs }.toList() }
        val sm = view.stickMovesSince(bStartNs)
        if (pm.size < 50 || sm.size < 50) return Double.NaN to Double.NaN
        var bestLag = 0; var bestErr = Double.MAX_VALUE
        for (lagMs in 0..200 step 2) {
            val lag = lagMs * 1_000_000L
            var i = 0; var err = 0.0; var n = 0
            for (s in sm) {
                val tt = s[0] - lag
                while (i + 1 < pm.size && pm[i + 1][0] <= tt) i++
                if (pm[i][0] > tt) continue
                val dx = (s[1] - pm[i][1]).toDouble(); val dy = (s[2] - pm[i][2]).toDouble()
                err += dx * dx + dy * dy; n++
            }
            if (n > 30) { val e = err / n; if (e < bestErr) { bestErr = e; bestLag = lagMs } }
        }
        return bestLag.toDouble() to sqrt(bestErr)
    }

    private var finished = false

    private fun finishTest(reason: String) {
        if (finished) return
        finished = true
        phase = Phase.DONE
        proxy?.stop()
        svc?.hidePad()
        val (lag, rms) = lagEstimate()
        val aCancelled = trialsA.count { it.verdict == "PAD_CANCELLED" }
        val aSurvived = trialsA.count { it.verdict == "PAD_SURVIVED" }
        val bOk = trialsB.count { it.verdict == "BOTH_OK" }
        val tapLat = trialsB.filter { it.tapArrived }.map { it.tapLatencyMs }.toDoubleArray()
        val report = buildString {
            appendLine("== ⑥ 대리 조이스틱 시험 (측정값) ==")
            appendLine("기기: ${DeviceInfo.short(this@ProxyProbeActivity)}")
            appendLine("종료: $reason")
            appendLine()
            appendLine("[A] 다른 창(오버레이 패드)의 손가락이 자동 탭에 취소되는가")
            appendLine("  시행 ${trialsA.size}: 취소 $aCancelled, 유지 $aSurvived, 기타 ${trialsA.size - aCancelled - aSurvived} ${trialsA.map { it.verdict }}")
            appendLine("  판정: " + when {
                aCancelled > 0 -> "취소됨 → 손가락이 화면 어디에 있든 주입 시 끊김. 화면 터치 기반 대리 조작 불가."
                aSurvived >= 4 -> "유지됨 → 오버레이 손가락은 안전. B 결과로 판단."
                else -> "판정 불가(유효 시행 부족)"
            })
            appendLine()
            appendLine("[B] 대리 조이스틱 + 블록 탭을 한 제스처로 주입")
            appendLine("  시행 ${trialsB.size}: 둘 다 정상 $bOk ${trialsB.map { it.verdict }}")
            for (v in 0..2) {
                val tv = trialsB.filter { it.variant == v }
                val name = arrayOf("점 탭 30ms", "2px 선 탭 30ms", "2px 선 탭 16ms(세그먼트와 같은 길이)")[v]
                appendLine("  탭 방식 $v ($name): 도착 ${tv.count { it.tapArrived }}/${tv.size}, 조이스틱 끊김 ${tv.count { it.stickCancelled }}")
            }
            appendLine("  손가락 정지 구간에서 1px 흔들기로 보낸 세그먼트: ${proxy?.wiggles ?: 0}")
            appendLine("  블록 탭 도착 지연(요청→수신, 다음 세그먼트까지 대기 포함): ${Stats.summarize(tapLat).fmt("ms")}")
            appendLine("  탭 전후 대리 조이스틱 최대 이벤트 공백: ${Stats.summarize(trialsB.map { it.stickGapMs }.toDoubleArray()).fmt("ms")}")
            appendLine("  손가락 → 대리 조이스틱 추종 지연(상호상관 추정): ${if (lag.isNaN()) "데이터 부족" else "%.0fms (오차 RMS %.0fpx)".format(lag, rms)}")
            appendLine("  주입 세그먼트 ${proxy?.segments ?: 0}개, 주입 취소 ${proxy?.cancels ?: 0}회, 거부 ${proxy?.refused ?: 0}회, '게임' 화면이 받은 조이스틱 취소 ${view.stickCancels}회")
            appendLine()
            appendLine("[C] 대리 조이스틱 중 다른 손가락으로 실제 버튼 누르기")
            for (c in trialsC) appendLine("  #${c.n}: 대리 조이스틱 ${if (c.stickCancelled || c.proxyCancelled) "끊김" else "유지"}, 패드 손가락 ${if (c.padCancelled) "끊김" else "유지"}")
            if (trialsC.isEmpty()) appendLine("  시행 없음")
            appendLine()
            appendLine("종합: " + when {
                aCancelled > 0 -> "접근성 주입은 화면의 모든 실제 터치를 끊음 → '사람이 화면으로 이동 + 앱이 블록'은 이 방식으로 불가. 남은 길: 물리 장치 또는 터치가 아닌 이동 입력."
                trialsB.isNotEmpty() && bOk == trialsB.size && trialsC.none { it.stickCancelled || it.proxyCancelled } ->
                    "대리 조이스틱 방식 성립 가능(앱 화면 기준). 추종 지연과 게임 내 동작은 추가 확인 필요."
                trialsB.isNotEmpty() && bOk == trialsB.size -> "이동+블록은 함께 가능하나, 다른 손으로 버튼을 누르면 대리 조이스틱이 끊김 → 실전 사용 제한적."
                else -> "대리 조이스틱 방식에 문제가 있음(B 결과 참고)."
            })
        }
        view.message = report.lines().filter { it.startsWith("  판정") || it.startsWith("종합") }.joinToString("\n")
        view.invalidate()
        Bench.lastSummary = report
        try {
            val dir = File(getExternalFilesDir("sessions"), "${stamp}_proxyprobe").apply { mkdirs() }
            File(dir, "proxy_probe_summary.txt").writeText(report)
            File(dir, "proxy_events.csv").writeText(synchronized(log) { log.toString() })
            Bench.lastExportDir = Exporter.copyToDownloads(this, dir)
        } catch (_: Throwable) {}
    }

    fun rec(t: Long, src: String, ev: String, pointer: Int, x: Float, y: Float, device: Int) {
        synchronized(log) { if (log.length < 6_000_000) log.append(t).append(',').append(src).append(',').append(ev).append(',').append(pointer).append(',').append(x.toInt()).append(',').append(y.toInt()).append(',').append(device).append('\n') }
    }
}

/** Stands in for the game: left half = joystick area, red circle = block button, yellow box = pass button. */
class ProxyView(private val act: ProxyProbeActivity) : View(act as Context) {
    var message = ""
    var showPass = false
    var originOnView = PointF()
    val targetDowns = java.util.concurrent.CopyOnWriteArrayList<Long>()
    @Volatile var stickCancels = 0
    private var stickId = -1
    private val stickMoves = ArrayList<LongArray>() // t, screenX, screenY
    private val loc = IntArray(2)
    private var stickPos: PointF? = null

    private val bg = Paint().apply { color = Color.rgb(28, 32, 40) }
    private val padArea = Paint().apply { color = Color.rgb(40, 50, 70) }
    private val stickPaint = Paint().apply { color = Color.rgb(60, 140, 255); isAntiAlias = true }
    private val originPaint = Paint().apply { color = Color.rgb(90, 110, 140); style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private val targetPaint = Paint().apply { color = Color.rgb(230, 80, 60); isAntiAlias = true }
    private val passPaint = Paint().apply { color = Color.rgb(240, 200, 60) }
    private val text = Paint().apply { color = Color.WHITE; textSize = 32f; isAntiAlias = true }

    fun targetX() = width * 0.80f
    fun targetY() = height * 0.40f
    private fun targetR() = height * 0.13f
    private fun passRect() = floatArrayOf(width * 0.70f, height * 0.68f, width * 0.92f, height * 0.92f)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), padArea)
        canvas.drawCircle(originOnView.x, originOnView.y, height * 0.16f, originPaint)
        stickPos?.let { canvas.drawCircle(it.x, it.y, height * 0.05f, stickPaint) }
        canvas.drawCircle(targetX(), targetY(), targetR(), targetPaint)
        if (showPass) { val r = passRect(); canvas.drawRect(r[0], r[1], r[2], r[3], passPaint); canvas.drawText("패스", r[0] + 20f, r[1] + 50f, Paint(text).apply { color = Color.BLACK }) }
        var y = 46f
        for (line in message.lines()) { canvas.drawText(line, 24f, y, text); y += 40f }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = System.nanoTime()
        getLocationOnScreen(loc)
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        act.rec(now, "game", MotionEvent.actionToString(e.actionMasked), id, e.getX(idx) + loc[0], e.getY(idx) + loc[1], e.deviceId)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx)
                when {
                    x < width / 2f && stickId < 0 -> { stickId = id; stickPos = PointF(x, y) }
                    hypot(x - targetX(), y - targetY()) <= targetR() -> targetDowns.add(now)
                    showPass && passRect().let { x >= it[0] && x <= it[2] && y >= it[1] && y <= it[3] } -> act.onPassPressed(now)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) if (e.getPointerId(i) == stickId) {
                    stickPos = PointF(e.getX(i), e.getY(i))
                    synchronized(stickMoves) { if (stickMoves.size < 200_000) stickMoves.add(longArrayOf(now, (e.getX(i) + loc[0]).toLong(), (e.getY(i) + loc[1]).toLong())) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (id == stickId) { stickId = -1; stickPos = null }
            MotionEvent.ACTION_CANCEL -> { if (stickId >= 0) stickCancels++; stickId = -1; stickPos = null }
        }
        invalidate()
        return true
    }

    fun stickMovesSince(t: Long): List<LongArray> = synchronized(stickMoves) { stickMoves.filter { it[0] >= t }.toList() }

    fun maxStickGapMs(from: Long, to: Long): Double {
        var prev = -1L; var gap = 0L
        synchronized(stickMoves) {
            for (m in stickMoves) {
                if (m[0] < from || m[0] > to) continue
                if (prev >= 0 && m[0] - prev > gap) gap = m[0] - prev
                prev = m[0]
            }
        }
        return gap / 1e6
    }
}
