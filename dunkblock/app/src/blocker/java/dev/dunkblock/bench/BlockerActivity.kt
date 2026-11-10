package dev.dunkblock.bench

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "DunkBlock" — the auto-block app (flavor `blocker`). Same engine as the bench app (capture, overlay joystick,
 * AutoBlocker); this screen only starts/stops a run and shows its state, in an iOS-like grouped layout.
 */
class BlockerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var dark = false
    private lateinit var startButton: TextView
    private lateinit var stateTitle: TextView
    private lateinit var stateDetail: TextView
    private lateinit var a11yValue: TextView
    private lateinit var calibValue: TextView
    private lateinit var resultText: TextView
    private var recalibrate = false
    private var note = ""

    // iOS system colours (light / dark)
    private fun c(light: Long, darkC: Long) = (if (dark) darkC else light).toInt()
    private val bg get() = c(0xFFF2F2F7, 0xFF000000)
    private val card get() = c(0xFFFFFFFF, 0xFF1C1C1E)
    private val label get() = c(0xFF000000, 0xFFFFFFFF)
    private val secondary get() = c(0xFF6C6C70, 0xFF98989F)
    private val separator get() = c(0xFFC6C6C8, 0xFF38383A)
    private val blue get() = c(0xFF007AFF, 0xFF0A84FF)
    private val green get() = c(0xFF34C759, 0xFF30D158)
    private val red get() = c(0xFFFF3B30, 0xFFFF453A)
    private val fill get() = c(0x1F767680, 0x3D767680)

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        loadSettings()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; addView(col) }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(dp(16f), b.top + dp(12f), dp(16f), b.bottom + dp(28f))
            insets
        }
        window.decorView.setBackgroundColor(bg)
        window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )

        // Large title
        col.addView(text("DunkBlock", 34f, label, bold = true), lp(top = 20f))
        col.addView(text("자동 블록 · 초안 " + DeviceInfo.appVersion(this), 15f, secondary), lp(top = 2f))

        // State card + primary button
        val stateCard = cardView()
        stateTitle = text("대기 중", 20f, label, bold = true)
        stateDetail = text("", 15f, secondary)
        stateCard.addView(stateTitle, rowLp(top = 16f))
        stateCard.addView(stateDetail, rowLp(top = 4f, bottom = 16f))
        col.addView(stateCard, lp(top = 20f))

        startButton = text("시작", 17f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(blue, 14f)
            setOnClickListener { onStartStop() }
        }
        col.addView(startButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(52f)).apply { topMargin = dp(12f) })

        // Readiness
        col.addView(header("준비"), lp(top = 28f))
        val ready = cardView()
        a11yValue = text("", 17f, secondary)
        ready.addView(row("접근성 권한", "조이스틱 전달과 블록 입력에 필요", a11yValue) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        ready.addView(divider())
        calibValue = text("", 17f, secondary)
        ready.addView(row("버튼 위치", "조이스틱 중심과 블록 버튼", calibValue) {
            recalibrate = !recalibrate
            refreshStatic()
        })
        col.addView(ready, lp(top = 8f))
        col.addView(footnote("버튼 위치를 누르면 다음 시작 때 게임 화면에서 다시 맞춥니다."), lp(top = 8f))

        // Settings
        col.addView(header("설정"), lp(top = 24f))
        val settings = cardView()
        val liveSwitch = IosSwitch(this, green, fill).apply {
            setChecked(Bench.config.autoBlockLive, false)
            onChange = { v -> Bench.config = Bench.config.copy(autoBlockLive = v); saveSettings() }
        }
        settings.addView(row("블록 실제로 누르기", "끄면 누를 시점만 기록", liveSwitch, null))
        settings.addView(divider())
        settings.addView(text("궁극기 감지 후 누르기까지", 17f, label), rowLp(top = 12f))
        val delays = listOf(450, 525, 600, 675, 750)
        settings.addView(segmented(delays.map { "${it}ms" }, delays.indexOf(Bench.config.ultDelayMs).coerceAtLeast(0)) { i ->
            Bench.config = Bench.config.copy(ultDelayMs = delays[i]); saveSettings()
        }, rowLp(top = 10f, bottom = 14f))
        col.addView(settings, lp(top = 8f))
        col.addView(footnote("블록이 늦으면 값을 줄이고, 너무 이르면 늘리세요. 기본값 600ms는 영상 1개에서 읽은 값입니다."), lp(top = 8f))

        // Result
        col.addView(header("최근 결과"), lp(top = 24f))
        val result = cardView()
        resultText = text("아직 결과가 없습니다.", 15f, label)
        result.addView(resultText, rowLp(top = 14f, bottom = 14f))
        result.addView(divider())
        result.addView(actionRow("결과 공유") { share() })
        result.addView(divider())
        result.addView(actionRow("전송용 묶음 만들기") { makeBundle() })
        col.addView(result, lp(top = 8f))

        col.addView(
            footnote(
                "이 초안은 상대 궁극기(화면이 어두워지는 순간)에만 반응합니다. 일반 슛·덩크·레이업은 아직 누르지 않습니다. " +
                    "조이스틱을 누르고 있을 때만 동작합니다. 사람과의 공식 경기에서 자동 입력을 쓰면 운영정책 위반으로 제재될 수 있습니다.",
            ),
            lp(top = 16f),
        )
        setContentView(scroll)
    }

    // ------------------------------------------------------------------ actions

    private fun onStartStop() {
        if (Bench.running) {
            val g = BlockAccessibilityService.instance?.inGame
            if (g != null) g.finish("앱에서 중지") else CaptureService.stop(this)
            return
        }
        if (Bench.saving) return
        if (BlockAccessibilityService.instance == null) {
            note = "접근성 권한을 먼저 켜 주세요 (설치된 앱 → DunkBlock 자동 블록)."
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        Bench.session = null
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Framework activity result API; AndroidX is intentionally not used")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode != RESULT_OK || data == null) { note = "화면 기록 권한이 필요합니다. '전체 화면'으로 허용해 주세요."; return }
        Bench.config = Bench.config.copy(
            mode = Bench.Mode.GAME, autoBlock = true, backend = Bench.Backend.NONE, evidence = false, recalibrate = recalibrate,
        )
        Bench.autoReport = ""
        Bench.autoLive = ""
        CaptureService.start(this, resultCode, data)
        val svc = BlockAccessibilityService.instance
        if (svc != null) svc.inGame = InGameProxy(svc, autoMode = true).also { it.begin() }
        recalibrate = false
        note = "게임을 열고, 경기 화면이 나오면 화면 위 검은 버튼을 누르세요."
    }

    private fun share() {
        val body = (Bench.lastSummary.ifEmpty { "아직 결과가 없습니다." }) + "\n\n저장 위치: " + Bench.lastExportDir + "\n\n" + DeviceInfo.full(this)
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "결과 공유"))
        } catch (t: Throwable) { note = "공유를 열 수 없습니다." }
    }

    private fun makeBundle() {
        if (Bench.running || Bench.saving) { note = "실행이 끝난 뒤에 눌러 주세요."; return }
        note = "전송용 묶음 만드는 중…"
        Thread {
            val msg = try { SendBundle.build(this) } catch (t: Throwable) { "전송용 묶음 실패: $t" }
            runOnUiThread { note = msg }
        }.start()
    }

    // ------------------------------------------------------------------ state

    private fun loadSettings() {
        val sp = getSharedPreferences("blocker", 0)
        var cfg = Bench.config.copy(autoBlockLive = sp.getBoolean("live", true), ultDelayMs = sp.getInt("delay", 600))
        val cal = getSharedPreferences("calib", 0)
        if (cal.contains("bx")) cfg = cfg.copy(
            blockFx = cal.getFloat("bx", cfg.blockFx), blockFy = cal.getFloat("by", cfg.blockFy),
            joyFx = cal.getFloat("jx", cfg.joyFx), joyFy = cal.getFloat("jy", cfg.joyFy),
        )
        Bench.config = cfg
    }

    private fun saveSettings() {
        getSharedPreferences("blocker", 0).edit().putBoolean("live", Bench.config.autoBlockLive).putInt("delay", Bench.config.ultDelayMs).apply()
    }

    private fun refreshStatic() {
        val a11y = DeviceInfo.a11yEnabled(this) && BlockAccessibilityService.instance != null
        a11yValue.text = if (a11y) "켜짐" else "꺼짐 ›"
        a11yValue.setTextColor(if (a11y) green else red)
        val has = getSharedPreferences("calib", 0).contains("bx")
        calibValue.text = when { recalibrate -> "다시 맞춤 예정"; has -> "저장됨"; else -> "첫 시작 때 맞춤" }
        calibValue.setTextColor(if (recalibrate) blue else secondary)
    }

    private val tick = object : Runnable {
        override fun run() {
            when {
                Bench.running -> {
                    stateTitle.text = "실행 중"
                    stateDetail.text = listOf(Bench.autoLive.ifEmpty { "궁극기 감지 대기" }, note).filter { it.isNotEmpty() }.joinToString("\n")
                    startButton.text = "중지"; startButton.background = rounded(red, 14f)
                }
                Bench.saving -> {
                    stateTitle.text = "결과 저장 중…"; stateDetail.text = "몇 초만 기다려 주세요."
                    startButton.text = "저장 중"; startButton.background = rounded(secondary, 14f)
                }
                else -> {
                    stateTitle.text = "대기 중"
                    stateDetail.text = note.ifEmpty { "시작을 누르고 화면 기록을 '전체 화면'으로 허용하세요." }
                    startButton.text = "시작"; startButton.background = rounded(blue, 14f)
                }
            }
            val s = Bench.lastSummary
            resultText.text = if (s.isEmpty()) "아직 결과가 없습니다." else compact(s)
            refreshStatic()
            main.postDelayed(this, 500)
        }
    }

    /** The ⑧ section of the session summary, or its first lines when there is none. */
    private fun compact(s: String): String {
        val lines = s.lines()
        val i = lines.indexOfFirst { it.startsWith("== ⑧") }
        val pick = if (i >= 0) lines.drop(i + 1).takeWhile { !it.startsWith("-- 메모") }.take(9) else lines.take(6)
        return pick.joinToString("\n").trim()
    }

    override fun onResume() { super.onResume(); main.post(tick) }
    override fun onPause() { super.onPause(); main.removeCallbacks(tick) }

    // ------------------------------------------------------------------ small UI kit

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(0f, 1.12f)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun cardView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(card, 14f)
        clipToOutline = true
    }

    private fun lp(top: Float = 0f) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun rowLp(top: Float = 0f, bottom: Float = 0f) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        leftMargin = dp(16f); rightMargin = dp(16f); topMargin = dp(top); bottomMargin = dp(bottom)
    }

    private fun header(s: String) = text(s, 13f, secondary).apply { setPadding(dp(16f), 0, 0, 0) }

    private fun footnote(s: String) = text(s, 13f, secondary).apply { setPadding(dp(16f), 0, dp(16f), 0) }

    private fun divider() = View(this).apply {
        setBackgroundColor(separator)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { leftMargin = dp(16f) }
    }

    private fun row(title: String, subtitle: String, trailing: View, onClick: (() -> Unit)?): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(11f), dp(16f), dp(11f)); minimumHeight = dp(56f)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 17f, label))
        texts.addView(text(subtitle, 13f, secondary))
        r.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(trailing, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(12f) })
        if (onClick != null) r.setOnClickListener { onClick() }
        return r
    }

    private fun actionRow(title: String, onClick: () -> Unit) = text(title, 17f, blue).apply {
        setPadding(dp(16f), dp(13f), dp(16f), dp(13f)); typeface = medium
        setOnClickListener { onClick() }
    }

    private fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = rounded(fill, 9f); setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        }
        val items = ArrayList<TextView>()
        fun style(sel: Int) {
            for ((i, t) in items.withIndex()) {
                t.background = if (i == sel) rounded(if (dark) 0xFF636366.toInt() else Color.WHITE, 7f) else null
                t.typeface = if (i == sel) medium else Typeface.DEFAULT
            }
        }
        for ((i, s) in labels.withIndex()) {
            val t = text(s, 13f, label).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(7f), 0, dp(7f))
                setOnClickListener { style(i); onSelect(i) }
            }
            items.add(t)
            box.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        style(selected)
        return box
    }

    companion object { private const val REQ = 2001 }
}

/** iOS-style toggle. */
class IosSwitch(ctx: Context, private val onColor: Int, private val offColor: Int) : View(ctx) {
    var checked = false; private set
    var onChange: ((Boolean) -> Unit)? = null
    private var pos = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val blend = ArgbEvaluator()

    init {
        isClickable = true
        setOnClickListener { setChecked(!checked, true); onChange?.invoke(checked) }
    }

    fun setChecked(v: Boolean, animate: Boolean) {
        checked = v
        val target = if (v) 1f else 0f
        if (animate) ValueAnimator.ofFloat(pos, target).apply {
            duration = 180
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        } else { pos = target; invalidate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension((51 * d).toInt(), (31 * d).toInt())

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = blend.evaluate(pos, offColor, onColor) as Int
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, paint)
        paint.color = Color.WHITE
        paint.setShadowLayer(3 * d, 0f, 1.5f * d, 0x33000000)
        canvas.drawCircle(h / 2 + pos * (w - h), h / 2, h / 2 - 2 * d, paint)
        paint.clearShadowLayer()
    }
}
