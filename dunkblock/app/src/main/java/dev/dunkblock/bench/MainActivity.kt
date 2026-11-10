package dev.dunkblock.bench

import android.app.Activity
import android.content.Intent
import android.graphics.Point
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Display
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.hardware.display.DisplayManager
import java.util.Locale

/** Control panel. No overlay is shown during measurements; results are read here after the session. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var device: TextView
    private lateinit var cfgButtons: LinearLayout
    private var pendingMode = Bench.Mode.GAME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 48) }
        fun title(t: String) = root.addView(TextView(this).apply { text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 28, 0, 8) })
        fun button(t: String, f: () -> Unit) = root.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(TextView(this).apply { text = "DunkBlock Benchmark"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply {
            text = "자동 블록 프로그램이 아니라, 화면 인식 방식이 블록 타이밍에 쓸 만큼 빠른지 수치로 확인하는 측정 앱입니다. 측정 중에는 화면에 아무것도 띄우지 않고, 종료 후 결과를 저장합니다."
        })

        title("Phase 0 · 기기 확인")
        device = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(device)

        title("설정 (누를 때마다 다음 값)")
        cfgButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(cfgButtons)

        title("시험")
        button("① 게임 캡처 세션 시작 (FPS·처리시간·움직임 이벤트)") { requestCapture(Bench.Mode.GAME) }
        button("② 지연·FPS 패턴 시험 (${Bench.config.patternSeconds}초, 자동 종료)") { requestCapture(Bench.Mode.LATENCY) }
        button("③ 합성 모션 검출 시험 (정답 대비 지연·페이크 오탐)") { requestCapture(Bench.Mode.MOTION) }
        button("④ 수동 조이스틱 + 자동 탭 충돌 시험") { startActivity(Intent(this, TouchProbeActivity::class.java)) }
        button("세션 중지") { if (Bench.running) CaptureService.stop(this) }
        button("접근성 설정 열기 (④·⑤에 필요)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        title("⑤ 인게임 자동 탭 시험 (수동 관찰)")
        root.addView(TextView(this).apply {
            text = "게임에서 이동 조이스틱을 누른 채로 있을 때, 지정 위치에 자동 탭을 보내 캐릭터 이동이 끊기는지 눈으로 확인합니다. " +
                "좌표는 가로 화면 기준 %입니다. 시작 후 대기 시간 안에 게임으로 전환하세요."
        })
        val xIn = num("X% (가로 기준)", "88"); val yIn = num("Y%", "70")
        val cIn = num("횟수", "10"); val iIn = num("간격 ms", "1500"); val dIn = num("시작 대기 ms", "8000"); val durIn = num("탭 길이 ms", "50")
        listOf(xIn, yIn, cIn, iIn, dIn, durIn).forEach { root.addView(it) }
        button("⑤ 인게임 자동 탭 시작") {
            InGameTapTest.start(this, xIn.f(88f), yIn.f(70f), cIn.f(10f).toInt(), iIn.f(1500f).toLong(), dIn.f(8000f).toLong(), durIn.f(50f).toLong())
        }

        title("실시간 상태")
        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        root.addView(status)
        title("마지막 결과")
        summary = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(summary)

        setContentView(ScrollView(this).apply { addView(root) })
        renderConfig()
    }

    private fun num(hint: String, def: String) = EditText(this).apply {
        this.hint = hint; setText(def); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun EditText.f(def: Float) = text.toString().toFloatOrNull() ?: def

    private fun renderConfig() {
        cfgButtons.removeAllViews()
        val c = Bench.config
        fun opt(label: String, value: String, next: () -> Bench.Config) {
            cfgButtons.addView(Button(this).apply {
                text = "$label: $value"; isAllCaps = false
                setOnClickListener { if (!Bench.running) { Bench.config = next(); renderConfig() } }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        fun <T> cycle(list: List<T>, cur: T) = list[(list.indexOf(cur) + 1) % list.size]
        opt("캡처 배율", "${c.captureScale}") { c.copy(captureScale = cycle(listOf(1.0f, 0.5f, 0.33f, 0.25f), c.captureScale)) }
        opt("분석 폭(px)", "${c.analysisWidth}") { c.copy(analysisWidth = cycle(listOf(128, 192, 256, 320), c.analysisWidth)) }
        opt("분석 백엔드", c.backend.label) { c.copy(backend = cycle(Bench.Backend.values().toList(), c.backend)) }
        opt("확정 프레임 수", "${c.confirmFrames}") { c.copy(confirmFrames = cycle(listOf(1, 2, 3), c.confirmFrames)) }
        opt("차분 임계값", "${c.diffThreshold}") { c.copy(diffThreshold = cycle(listOf(16, 24, 32, 48), c.diffThreshold)) }
        opt("HUD·조작부 제외", if (c.excludeHud) "켬" else "끔") { c.copy(excludeHud = !c.excludeHud) }
        opt("이벤트 증거 이미지", if (c.evidence) "켬" else "끔") { c.copy(evidence = !c.evidence) }
        opt("OpenCV 축소 방식", if (c.opencvAreaResize) "INTER_AREA" else "INTER_NEAREST") { c.copy(opencvAreaResize = !c.opencvAreaResize) }
        opt("OpenCV 스레드", "${c.opencvThreads}") { c.copy(opencvThreads = cycle(listOf(1, 2, 4), c.opencvThreads)) }
        opt("카메라 검증용 ACK 사각형(②)", if (c.cameraAck) "켬" else "끔") { c.copy(cameraAck = !c.cameraAck) }
    }

    private fun requestCapture(mode: Bench.Mode) {
        if (Bench.running) { status.text = "이미 측정 중입니다."; return }
        // Drop the previous session now, so a test screen started next cannot write into its logs.
        Bench.session = null
        pendingMode = mode
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Activity result API from the framework; AndroidX is intentionally not used")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { status.text = "화면 캡처 권한이 거부되었습니다."; return }
        Bench.config = Bench.config.copy(mode = pendingMode)
        CaptureService.start(this, resultCode, data)
        when (pendingMode) {
            Bench.Mode.GAME -> status.text = "캡처 시작. 게임으로 전환하세요. 끝나면 알림의 '중지' 또는 이 화면의 '세션 중지'."
            else -> main.postDelayed({ startActivity(Intent(this, PatternActivity::class.java)) }, 400)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = Bench.session
            status.text = if (Bench.running) "측정 중 (${Bench.config.mode.label})\n${Bench.liveText}"
            else "대기 중" + (InGameTapTest.status.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "") +
                (s?.let { "\n최근 세션 캡처 ${it.captureW}x${it.captureH}" } ?: "")
            val sum = Bench.lastSummary
            if (sum.isNotEmpty() && summary.text.toString() != sum + "\n\n저장 위치: " + Bench.lastExportDir)
                summary.text = sum + "\n\n저장 위치: " + Bench.lastExportDir
            main.postDelayed(this, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        device.text = DeviceInfo.full(this) + phase0Checklist()
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    @Suppress("DEPRECATION")
    private fun phase0Checklist(): String {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point(); d.getRealSize(p)
        val maxHz = d.supportedModes.maxOf { it.refreshRate }
        return buildString {
            appendLine()
            appendLine("== Phase 0 체크리스트 ==")
            appendLine("[확인됨] 루팅 불필요: MediaProjection·AccessibilityService 모두 공개 API")
            appendLine("[확인됨] 표시 최대 ${"%.0f".format(Locale.ROOT, maxHz)}Hz 모드 존재 (실제 게임 중 Hz는 ①에서 측정)")
            appendLine("[미확인] 캡처 실효 FPS·지연 → ①② 실행 필요")
            appendLine("[미확인] 게임 화면 캡처 가능 여부(FLAG_SECURE 시 검은 화면) → ① 결과의 '검은 화면 비율'")
            appendLine("[미확인] 자동 탭이 조이스틱을 끊는지 → ④(앱 화면), ⑤(게임 화면)")
            appendLine("[미확인] 게임 운영정책(자동 입력 허용 여부) → 약관 직접 확인 필요")
            appendLine("화면 실제 크기 ${p.x}x${p.y}")
        }
    }

    companion object { private const val REQ_CAPTURE = 1001 }
}
