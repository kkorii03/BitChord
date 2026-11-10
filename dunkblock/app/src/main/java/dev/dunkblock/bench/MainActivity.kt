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
    private lateinit var scroll: ScrollView
    private lateinit var resultBox: LinearLayout
    private var pendingMode = Bench.Mode.GAME
    private var pendingAuto = false
    private var shownSummary = ""
    private var crashText = ""
    private var bundleMsg = ""
    private var bundling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        getSharedPreferences("calib", 0).let { sp ->
            if (sp.contains("bx")) Bench.config = Bench.config.copy(
                blockFx = sp.getFloat("bx", Bench.config.blockFx), blockFy = sp.getFloat("by", Bench.config.blockFy),
                joyFx = sp.getFloat("jx", Bench.config.joyFx), joyFy = sp.getFloat("jy", Bench.config.joyFy))
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 48) }
        fun title(t: String) = root.addView(TextView(this).apply { text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 28, 0, 8) })
        fun button(t: String, f: () -> Unit) = root.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(TextView(this).apply { text = "DunkBlock Benchmark"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply { text = DeviceInfo.appVersion(this@MainActivity); textSize = 13f })
        root.addView(TextView(this).apply {
            text = "자동 블록 프로그램이 아니라, 화면 인식 방식이 블록 타이밍에 쓸 만큼 빠른지 수치로 확인하는 측정 앱입니다. 측정 중에는 화면에 아무것도 띄우지 않고, 종료 후 결과를 저장합니다."
        })

        // Results first: after every test the user lands here and must not have to scroll to find them.
        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20); setBackgroundColor(0x22FF8A4C) }
        root.addView(resultBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 24 })
        resultBox.addView(TextView(this).apply { text = "상태 · 결과"; textSize = 18f; setTypeface(typeface, Typeface.BOLD) })
        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
        resultBox.addView(status)
        summary = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        resultBox.addView(summary)
        resultBox.addView(Button(this).apply {
            text = "결과 공유 (카톡·메모 등으로 보내기)"; isAllCaps = false
            setOnClickListener { shareResult() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        resultBox.addView(Button(this).apply {
            text = "전송용 묶음 만들기 (모든 결과 → 25MB 이하 zip 최대 4개)"; isAllCaps = false
            setOnClickListener {
                if (Bench.running || Bench.saving) { bundleMsg = "측정이 끝난 뒤에 눌러 주세요."; return@setOnClickListener }
                // A second tap while the first bundle is still being written made every zip twice (vc1035 user report).
                if (bundling) { bundleMsg = "전송용 묶음 만드는 중… (한 번만 누르세요)"; return@setOnClickListener }
                bundling = true
                bundleMsg = "전송용 묶음 만드는 중…"
                Thread {
                    val msg = try { SendBundle.build(this@MainActivity) } catch (t: Throwable) { "전송용 묶음 실패: $t" }
                    runOnUiThread { bundleMsg = msg; bundling = false }
                }.start()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

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
        button("⑥ 대리 조이스틱 시험 (④ 실패 시 대안 확인, 약 3분)") { startActivity(Intent(this, ProxyProbeActivity::class.java)) }
        button("⑦ 인게임 대리 조이스틱 (연습 모드 전용)") {
            val svc = BlockAccessibilityService.instance
            if (svc == null) { status.text = "접근성 서비스가 꺼져 있습니다."; return@button }
            if (svc.inGame != null) { status.text = "⑦이 이미 실행 중입니다. 화면 위 검은 버튼으로 끝내세요."; return@button }
            svc.inGame = InGameProxy(svc).also { it.begin() }
            status.text = "⑦ 준비됨: 덩크시티를 연습 모드로 열고, 화면 위쪽의 검은 버튼을 누르세요."
        }
        button("세션 중지") { if (Bench.running) CaptureService.stop(this) }
        button("접근성 설정 열기 (④·⑤에 필요)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        title("⑧ 자동 블록 초안")
        root.addView(TextView(this).apply {
            text = "상대 궁극기(화면이 어두워짐)를 감지하면 설정한 지연 뒤에 블록을 누릅니다. 조이스틱을 누르고 있을 때만 동작합니다. " +
                "일반 슛·덩크·레이업은 아직 대상이 아닙니다. 경기 화면과 블록 장면을 함께 기록합니다. " +
                "사람과의 공식 경기에서 쓰면 운영정책 위반으로 제재될 수 있습니다."
        })
        button("⑧ 자동 블록 초안 시작") {
            if (BlockAccessibilityService.instance == null) { status.text = "접근성 서비스가 꺼져 있습니다."; return@button }
            if (BlockAccessibilityService.instance?.inGame != null) { status.text = "이미 실행 중입니다. 화면 위 검은 버튼으로 끝내세요."; return@button }
            requestCapture(Bench.Mode.GAME, auto = true)
        }

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

        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        renderConfig()

        val crash = App.crashFile(this)
        if (crash.isFile) {
            crashText = "!! 지난번 앱이 오류로 종료되었습니다. 이 내용을 '결과 공유'로 보내 주세요 !!\n\n" + crash.readText()
            crash.renameTo(java.io.File(filesDir, "last_crash_seen.txt"))
        }
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
        opt("경기 화면 2초마다 저장(①)", if (c.sampleFrames) "켬" else "끔") { c.copy(sampleFrames = !c.sampleFrames) }
        opt("블록 누름 순간 60fps 클립(①)", if (c.pressClips) "켬" else "끔") { c.copy(pressClips = !c.pressClips) }
        opt("OpenCV 축소 방식", if (c.opencvAreaResize) "INTER_AREA" else "INTER_NEAREST") { c.copy(opencvAreaResize = !c.opencvAreaResize) }
        opt("OpenCV 스레드", "${c.opencvThreads}") { c.copy(opencvThreads = cycle(listOf(1, 2, 4), c.opencvThreads)) }
        opt("⑧ 자동 블록 동작", if (c.autoBlockLive) "실제로 누름" else "기록만(누르지 않음)") { c.copy(autoBlockLive = !c.autoBlockLive) }
        opt("⑧ 궁극기 감지 후 누르기까지(ms)", "${c.ultDelayMs}") { c.copy(ultDelayMs = cycle(listOf(450, 525, 600, 675, 750), c.ultDelayMs)) }
        opt("⑧ 시작할 때 버튼 위치 다시 맞추기", if (c.recalibrate) "켬" else "끔") { c.copy(recalibrate = !c.recalibrate) }
        opt("카메라 검증용 ACK 사각형(②)", if (c.cameraAck) "켬" else "끔") { c.copy(cameraAck = !c.cameraAck) }
    }

    private fun requestCapture(mode: Bench.Mode, auto: Boolean = false) {
        pendingAuto = auto
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
        Bench.config = Bench.config.copy(mode = pendingMode, autoBlock = pendingAuto)
        Bench.autoReport = ""
        CaptureService.start(this, resultCode, data)
        if (pendingAuto) {
            val svc = BlockAccessibilityService.instance
            if (svc != null) svc.inGame = InGameProxy(svc, autoMode = true).also { it.begin() }
            status.text = "⑧ 준비됨: 게임에서 경기 화면이 나오면 화면 위 검은 버튼을 누르세요. 끝낼 때도 그 버튼입니다."
            return
        }
        when (pendingMode) {
            Bench.Mode.GAME -> status.text = "캡처 시작. 게임으로 전환하세요. 끝나면 알림의 '중지' 또는 이 화면의 '세션 중지'."
            else -> main.postDelayed({ startActivity(Intent(this, PatternActivity::class.java)) }, 400)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = Bench.session
            status.text = when {
                Bench.running -> "● 측정 중 (${Bench.config.mode.label})\n${Bench.liveText}\n끝나면 알림의 '중지' 또는 아래 '세션 중지'."
                Bench.saving -> "● 결과 저장 중… 몇 초만 기다리세요."
                Bench.lastSummary.isNotEmpty() -> "✓ 마지막 시험 결과 (아래). 저장 위치: ${Bench.lastExportDir}"
                else -> "대기 중. 아래 '시험' 버튼으로 시작하세요."
            } + (bundleMsg.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "") +
                (InGameTapTest.status.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "") +
                (s?.takeIf { !Bench.running }?.let { "\n최근 세션 캡처 ${it.captureW}x${it.captureH}" } ?: "")
            val text = listOf(crashText, Bench.lastSummary).filter { it.isNotEmpty() }.joinToString("\n\n")
            if (text != shownSummary) {
                shownSummary = text
                summary.text = text
                scroll.post { scroll.smoothScrollTo(0, resultBox.top) }
            }
            main.postDelayed(this, 500)
        }
    }

    private fun shareResult() {
        val body = (if (shownSummary.isNotEmpty()) shownSummary else "아직 결과가 없습니다.\n" + status.text) +
            "\n\n저장 위치: " + Bench.lastExportDir + "\n\n" + DeviceInfo.full(this)
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "결과 공유"))
        } catch (t: Throwable) {
            status.text = "공유를 열 수 없습니다: $t"
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
