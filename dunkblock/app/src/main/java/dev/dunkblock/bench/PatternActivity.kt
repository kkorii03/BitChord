package dev.dunkblock.bench

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import dev.dunkblock.core.PatternCodec
import dev.dunkblock.core.TruthEvent
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Full-screen test scene drawn by this app, so the "truth" of what was shown and when is known.
 *
 * LATENCY: a 16-bit frame counter strip (decoded from every captured frame) + a colour-flip box that
 *          toggles at random 300–900 ms intervals + an optional ACK square that lights up when the capture
 *          side has seen the flip (for verification with an external high-speed camera).
 * MOTION : a "player" block that idles, then either JUMPS (attack) or FAKES (short rise, abort).
 *          Each start frame is logged as ground truth for detection-latency / false-trigger measurement.
 *
 * Timing: every frame is driven from Choreographer. On API 33+ the frame timeline's expected presentation
 * time is logged; before that, frameTime + one refresh period is used as the estimate.
 */
class PatternActivity : Activity() {
    private lateinit var view: PatternView
    private val rnd = Random()
    private var startNs = 0L
    private var nextFlipNs = 0L
    private var flipState = 0
    private var finished = false
    private var periodNs = 8_333_333L

    // motion script
    private var trialsDone = 0
    private var nextTrialNs = 0L
    private var trialStartNs = 0L
    private var trialKind = ""
    private var trialActive = false
    private var truthRecorded = false
    private var baseX = 0.5f
    private var driftSign = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Ask for the highest refresh mode the display offers (the user can verify it in display.csv).
            val modes = display?.supportedModes
            val best = modes?.maxByOrNull { it.refreshRate }
            if (best != null) { preferredDisplayModeId = best.modeId; preferredRefreshRate = best.refreshRate }
        }
        view = PatternView(this, Bench.config.mode)
        setContentView(view)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        display?.refreshRate?.let { if (it > 1f) periodNs = (1e9 / it).toLong() }
        if (Bench.config.cameraAck) Bench.flipSeenListener = { st -> view.post { view.ack = st; view.invalidate() } }
    }

    override fun onResume() {
        super.onResume()
        startNs = 0L
        schedule()
    }

    override fun onPause() {
        super.onPause()
        finished = true
        Bench.flipSeenListener = null
    }

    private fun schedule() {
        if (finished) return
        val ch = Choreographer.getInstance()
        if (Build.VERSION.SDK_INT >= 33) {
            ch.postVsyncCallback { data ->
                frame(data.frameTimeNanos, data.preferredFrameTimeline.expectedPresentationTimeNanos)
            }
        } else {
            ch.postFrameCallback { t -> frame(t, t + periodNs) }
        }
    }

    private fun frame(frameTimeNs: Long, expectedPresentNs: Long) {
        if (finished) return
        val cfg = Bench.config
        val s = Bench.session
        if (startNs == 0L) { startNs = frameTimeNs; nextFlipNs = frameTimeNs + 1_000_000_000L; nextTrialNs = frameTimeNs + 3_000_000_000L }
        val elapsedS = (frameTimeNs - startNs) / 1e9
        val idx = s?.display?.recordFrame(frameTimeNs, expectedPresentNs) ?: -1
        view.counter = if (idx >= 0) idx and 0xFFFF else 0

        if (cfg.mode == Bench.Mode.LATENCY) {
            if (frameTimeNs >= nextFlipNs) {
                flipState = 1 - flipState
                if (idx >= 0) s?.display?.recordFlip(idx, flipState)
                nextFlipNs = frameTimeNs + (300 + rnd.nextInt(600)) * 1_000_000L
            }
            view.flip = flipState
            view.status = "LATENCY ${"%.0f".format(elapsedS)}/${cfg.patternSeconds}s"
            if (elapsedS >= cfg.patternSeconds) { end(); return }
        } else {
            motionScript(frameTimeNs, expectedPresentNs, idx, s)
            view.status = "MOTION 시행 $trialsDone/${cfg.motionTrials}"
            if (trialsDone >= cfg.motionTrials && !trialActive) { end(); return }
        }
        view.invalidate()
        schedule()
    }

    private fun motionScript(t: Long, expected: Long, idx: Int, s: Bench.Session?) {
        val cfg = Bench.config
        if (!trialActive && t >= nextTrialNs && trialsDone < cfg.motionTrials) {
            trialActive = true
            trialStartNs = t
            trialKind = if (rnd.nextInt(3) == 0) "FAKE" else "JUMP"
            truthRecorded = false
            // No teleporting between trials (that would itself be motion): the block starts where it is.
            baseX = view.blobX
        }
        if (trialActive) {
            val ms = (t - trialStartNs) / 1e6
            // Ground truth = the first frame whose drawn content differs from the idle pose (ms > 0),
            // stamped with that frame's expected presentation time.
            if (!truthRecorded && ms > 0.0) {
                truthRecorded = true
                if (idx >= 0) s?.truth?.add(TruthEvent(idx, expected, trialKind))
            }
            if (trialKind == "JUMP") {
                val dur = 520.0
                if (ms <= dur) {
                    view.blobLift = (sin(PI * ms / dur) * 0.30).toFloat()
                    view.blobX = baseX + driftSign * (ms / dur * 0.04).toFloat()
                } else endTrial(t)
            } else {
                // fake: quick rise for 110 ms, back down by 220 ms
                val up = 110.0; val total = 220.0
                if (ms <= total) {
                    view.blobLift = (if (ms <= up) ms / up * 0.07 else (total - ms) / (total - up) * 0.07).toFloat()
                } else endTrial(t)
            }
        }
    }

    private fun endTrial(t: Long) {
        if (trialKind == "JUMP") driftSign = -driftSign
        trialActive = false
        trialsDone++
        view.blobLift = 0f
        nextTrialNs = t + (1400 + rnd.nextInt(1600)) * 1_000_000L
    }

    private fun end() {
        finished = true
        Bench.stopRequester?.invoke()
        finish()
    }
}

class PatternView(ctx: Context, private val mode: Bench.Mode) : View(ctx) {
    @Volatile var counter = 0
    @Volatile var flip = 0
    @Volatile var ack = 0
    @Volatile var blobX = 0.5f
    @Volatile var blobLift = 0f
    var status = ""
    private val white = Paint().apply { color = Color.WHITE }
    private val black = Paint().apply { color = Color.BLACK }
    private val ackPaint = Paint().apply { color = Color.rgb(0, 255, 0) }
    private val blobPaint = Paint().apply { color = Color.rgb(240, 200, 60) }
    private val text = Paint().apply { color = Color.LTGRAY; textSize = 28f; isAntiAlias = true }
    private val bg = Color.rgb(40, 40, 40)
    private val court = Color.rgb(70, 90, 110)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (mode == Bench.Mode.LATENCY) {
            canvas.drawColor(bg)
            val bits = PatternCodec.encode(counter)
            val y0 = (PatternCodec.STRIP_Y - PatternCodec.STRIP_H / 2) * h
            val y1 = (PatternCodec.STRIP_Y + PatternCodec.STRIP_H / 2) * h
            val bw = w / PatternCodec.BLOCKS
            for (i in 0 until PatternCodec.BLOCKS) canvas.drawRect(i * bw, y0, (i + 1) * bw, y1, if (bits[i]) white else black)
            canvas.drawRect(0.2f * w, 0.25f * h, 0.8f * w, 0.9f * h, if (flip == 1) white else black)
            if (ack == 1) canvas.drawRect(0.02f * w, 0.80f * h, 0.12f * w, 0.95f * h, ackPaint)
        } else {
            canvas.drawColor(court)
            val bwid = 0.06f * w; val bh = 0.14f * h
            val cx = blobX * w
            val bottom = (0.78f - blobLift) * h
            canvas.drawRect(cx - bwid / 2, bottom - bh, cx + bwid / 2, bottom, blobPaint)
        }
        // Status text only in LATENCY mode: in MOTION mode any changing text would itself be detected as motion.
        if (mode == Bench.Mode.LATENCY) canvas.drawText(status, 0.30f * w, 0.97f * h, text)
    }
}
