package dev.dunkblock.bench

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/**
 * ⑤ Manual joystick vs automatic input conflict test.
 *
 * Left half  = virtual joystick. The user puts a finger down and keeps moving it in small circles.
 * Right half = "block button" target. Once the joystick has been held for 1.5 s, the accessibility service
 *              injects taps on the target (dispatchGesture) every 1.2 s, alternating tap durations.
 *
 * For every injected tap the app records, from the app's own view of the input stream:
 *   - did the joystick pointer receive ACTION_CANCEL (the known failure mode)?
 *   - did the joystick keep producing MOVE events afterwards, and how long was the largest gap?
 *   - did the target receive the injected touch, and dispatch → target-receipt latency?
 * Verdict per trial: SURVIVED / CANCELLED / LIFTED (user lifted, inconclusive) / NO_MOVES (finger
 * not moving, inconclusive) / NOT_DELIVERED.
 */
class TouchProbeActivity : Activity() {
    private lateinit var probe: ProbeView
    private val main = Handler(Looper.getMainLooper())
    private val durations = longArrayOf(10, 50, 100)
    private val totalTrials = 12

    class Trial(val n: Int, val durMs: Long, val dispatchNs: Long) {
        var accepted = false
        var completedNs = 0L
        var completed: Boolean? = null
        var targetDownNs = 0L
        var targetUpNs = 0L
        var targetDeviceId = Int.MIN_VALUE
        var joyCancelled = false
        var joyLifted = false
        var movesBefore = 0
        var movesAfter = 0
        var maxGapMs = 0.0
        var verdict = "PENDING"
    }

    private val trials = ArrayList<Trial>()
    private var running = false
    private var holdStartNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        probe = ProbeView(this)
        setContentView(probe)
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        probe.message = if (BlockAccessibilityService.instance == null)
            "접근성 서비스가 꺼져 있습니다. 설정 > 접근성 > 설치된 앱 > 'DunkBlock 입력 충돌 시험'을 켜세요."
        else "왼쪽 영역에 손가락을 대고 작은 원을 계속 그리세요. 1.5초 후 자동 탭 ${totalTrials}회가 시작됩니다."
    }

    // ---------------------------------------------------------------- called by ProbeView

    fun onJoyDown(tNs: Long) {
        if (running || trials.size >= totalTrials) return
        holdStartNs = tNs
        main.postDelayed({ if (probe.joyActive && !running && BlockAccessibilityService.instance != null) { running = true; nextTrial() } }, 1500)
    }

    fun onJoyEnd(tNs: Long, cancelled: Boolean) {
        val t = trials.lastOrNull() ?: return
        if (t.verdict != "PENDING") return
        if (cancelled) t.joyCancelled = true else t.joyLifted = true
    }

    fun onTargetDown(tNs: Long, deviceId: Int) {
        val t = trials.lastOrNull() ?: return
        if (t.targetDownNs == 0L) { t.targetDownNs = tNs; t.targetDeviceId = deviceId }
    }

    fun onTargetUp(tNs: Long) { trials.lastOrNull()?.let { if (it.targetUpNs == 0L) it.targetUpNs = tNs } }

    // ---------------------------------------------------------------- trial loop

    private fun nextTrial() {
        val svc = BlockAccessibilityService.instance
        if (svc == null || trials.size >= totalTrials) { finishTest(); return }
        val n = trials.size + 1
        val dur = durations[(n - 1) % durations.size]
        val loc = IntArray(2)
        probe.getLocationOnScreen(loc)
        val x = loc[0] + probe.targetX(); val y = loc[1] + probe.targetY()
        val beforeMoves = probe.countMoves(System.nanoTime() - 300_000_000L, System.nanoTime())
        val t = Trial(n, dur, System.nanoTime())
        t.movesBefore = beforeMoves
        trials.add(t)
        t.accepted = svc.tap(x, y, dur) { ok, tNs -> t.completed = ok; t.completedNs = tNs }
        main.postDelayed({ evaluate(t) }, 450)
    }

    private fun evaluate(t: Trial) {
        val now = System.nanoTime()
        t.movesAfter = probe.countMoves(t.dispatchNs, now)
        t.maxGapMs = probe.maxMoveGapMs(t.dispatchNs - 100_000_000L, now)
        t.verdict = when {
            !t.accepted -> "NOT_DISPATCHED"
            t.joyCancelled -> "CANCELLED"
            t.joyLifted || !probe.joyActive -> "LIFTED"
            t.targetDownNs == 0L -> "NOT_DELIVERED"
            t.movesBefore == 0 || t.movesAfter == 0 -> "NO_MOVES"
            else -> "SURVIVED"
        }
        probe.message = "시행 ${t.n}/$totalTrials: ${t.verdict}  (탭 ${t.durMs}ms, 최대 MOVE 간격 ${"%.0f".format(t.maxGapMs)}ms)"
        probe.invalidate()
        if (t.joyCancelled || t.joyLifted || !probe.joyActive) {
            running = false
            probe.message += "\n조이스틱 손가락이 끊겼습니다. 다시 누르면 남은 시행을 계속합니다."
            if (trials.size >= totalTrials) finishTest()
            return
        }
        main.postDelayed({ nextTrial() }, 750)
    }

    private fun finishTest() {
        running = false
        val report = buildReport()
        probe.message = report.lines().take(8).joinToString("\n")
        probe.invalidate()
        Bench.lastSummary = report
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val dir = File(getExternalFilesDir("sessions"), "${stamp}_touchprobe").apply { mkdirs() }
        File(dir, "touch_probe_summary.txt").writeText(report)
        File(dir, "touch_probe.csv").writeText(buildString {
            appendLine("trial,tap_ms,dispatched,completed,dispatch_to_target_down_ms,dispatch_to_complete_ms,target_device_id,joy_cancelled,joy_lifted,moves_before_300ms,moves_after,max_move_gap_ms,verdict")
            for (t in trials) appendLine(
                "${t.n},${t.durMs},${t.accepted},${t.completed},${ms(t.targetDownNs, t.dispatchNs)},${ms(t.completedNs, t.dispatchNs)}," +
                    "${t.targetDeviceId},${t.joyCancelled},${t.joyLifted},${t.movesBefore},${t.movesAfter},${"%.1f".format(Locale.ROOT, t.maxGapMs)},${t.verdict}",
            )
        })
        File(dir, "touch_events.csv").writeText(probe.eventsCsv())
        Bench.lastExportDir = Exporter.copyToDownloads(this, dir)
    }

    private fun ms(a: Long, b: Long) = if (a == 0L) "" else "%.2f".format(Locale.ROOT, (a - b) / 1e6)

    private fun buildReport(): String = buildString {
        val byVerdict = trials.groupingBy { it.verdict }.eachCount()
        appendLine("== ⑤ 수동 이동 + 자동 입력 충돌 시험 (측정값) ==")
        appendLine("기기: ${DeviceInfo.short(this@TouchProbeActivity)}")
        appendLine("시행 ${trials.size}회: $byVerdict")
        val conclusive = trials.filter { it.verdict == "SURVIVED" || it.verdict == "CANCELLED" }
        val cancelled = trials.count { it.verdict == "CANCELLED" }
        val verdict = when {
            conclusive.size < 5 -> "판정 불가(유효 시행 ${conclusive.size}회 < 5). 손가락을 떼지 말고 계속 움직이며 다시 시험하세요."
            cancelled > 0 -> "실패: 자동 탭이 진행 중인 조이스틱 터치를 취소함(${cancelled}/${conclusive.size}). 현재 방식으로는 '수동 이동 + 자동 블록'이 성립하지 않음."
            else -> "통과(이 기기·이 앱 화면 기준): 유효 ${conclusive.size}회 모두 조이스틱 유지. 단, 게임 내 동작은 ⑥ 인게임 시험으로 별도 확인 필요."
        }
        appendLine("판정: $verdict")
        val lat = trials.filter { it.targetDownNs > 0 }.map { (it.targetDownNs - it.dispatchNs) / 1e6 }.toDoubleArray()
        appendLine("dispatchGesture 호출 → 목표 뷰 터치 수신: ${Stats.summarize(lat).fmt("ms")}")
        val gaps = trials.filter { it.verdict == "SURVIVED" }.map { it.maxGapMs }.toDoubleArray()
        appendLine("유지된 시행의 탭 전후 최대 MOVE 간격: ${Stats.summarize(gaps).fmt("ms")}  (평소 간격보다 크게 늘면 이동 끊김 체감 가능)")
        appendLine("주입 터치 deviceId: ${trials.map { it.targetDeviceId }.distinct()}")
        for (t in trials) appendLine("  #${t.n} tap=${t.durMs}ms ${t.verdict} cancel=${t.joyCancelled} lift=${t.joyLifted} moves ${t.movesBefore}→${t.movesAfter} gap=${"%.0f".format(t.maxGapMs)}ms")
    }
}

class ProbeView(private val act: TouchProbeActivity) : View(act as Context) {
    var message = ""
    @Volatile var joyActive = false
    private var joyId = -1
    private var joyX = 0f
    private var joyY = 0f
    private val moveTimes = LongArray(20_000)
    private var moveCount = 0
    private val events = StringBuilder("t_ns,action,pointer_id,x,y,device_id,source,role\n")

    private val joyPaint = Paint().apply { color = Color.rgb(60, 140, 255); isAntiAlias = true }
    private val padPaint = Paint().apply { color = Color.rgb(40, 50, 70) }
    private val targetPaint = Paint().apply { color = Color.rgb(230, 80, 60); isAntiAlias = true }
    private val text = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true }

    fun targetX() = width * 0.80f
    fun targetY() = height * 0.55f
    private fun targetR() = height * 0.14f

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(20, 20, 24))
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), padPaint)
        canvas.drawCircle(targetX(), targetY(), targetR(), targetPaint)
        if (joyActive) canvas.drawCircle(joyX, joyY, height * 0.08f, joyPaint)
        var y = 50f
        for (line in message.lines()) { canvas.drawText(line, 30f, y, text); y += 42f }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = System.nanoTime()
        val action = e.actionMasked
        val idx = e.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx)
                log(now, action, id, x, y, e, if (x < width / 2f) "joy" else "other")
                if (x < width / 2f && joyId < 0) { joyId = id; joyActive = true; joyX = x; joyY = y; act.onJoyDown(now) }
                else if (isTarget(x, y)) act.onTargetDown(now, e.deviceId)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    if (e.getPointerId(i) == joyId) {
                        joyX = e.getX(i); joyY = e.getY(i)
                        if (moveCount < moveTimes.size) moveTimes[moveCount++] = now
                    }
                }
                if (joyId >= 0) log(now, action, joyId, joyX, joyY, e, "joy")
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(idx)
                log(now, action, id, e.getX(idx), e.getY(idx), e, if (id == joyId) "joy" else "other")
                if (id == joyId) { joyId = -1; joyActive = false; act.onJoyEnd(now, cancelled = false) }
                else if (isTarget(e.getX(idx), e.getY(idx))) act.onTargetUp(now)
            }
            MotionEvent.ACTION_CANCEL -> {
                log(now, action, joyId, joyX, joyY, e, "cancel")
                if (joyId >= 0) { joyId = -1; joyActive = false; act.onJoyEnd(now, cancelled = true) }
            }
        }
        invalidate()
        return true
    }

    private fun isTarget(x: Float, y: Float): Boolean {
        val dx = x - targetX(); val dy = y - targetY()
        return dx * dx + dy * dy <= targetR() * targetR()
    }

    private fun log(t: Long, action: Int, id: Int, x: Float, y: Float, e: MotionEvent, role: String) {
        if (events.length < 4_000_000)
            events.append(t).append(',').append(MotionEvent.actionToString(action)).append(',').append(id).append(',')
                .append(x.toInt()).append(',').append(y.toInt()).append(',').append(e.deviceId).append(',')
                .append(e.source).append(',').append(role).append('\n')
    }

    fun countMoves(fromNs: Long, toNs: Long): Int { var c = 0; for (i in 0 until moveCount) if (moveTimes[i] in fromNs..toNs) c++; return c }

    fun maxMoveGapMs(fromNs: Long, toNs: Long): Double {
        var prev = -1L; var gap = 0L
        for (i in 0 until moveCount) {
            val t = moveTimes[i]
            if (t < fromNs || t > toNs) continue
            if (prev >= 0 && t - prev > gap) gap = t - prev
            prev = t
        }
        return gap / 1e6
    }

    fun eventsCsv() = events.toString()
}
