package dev.dunkblock.bench

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Used ONLY for the input-conflict experiments (④⑤⑥). It does not read screen content.
 *
 * ⑥ adds two things:
 *  - an accessibility overlay "pad" (a separate window on top of everything) that receives the user's finger,
 *  - a [ProxyStick] that injects a continuously held joystick stroke (continueStroke) following that finger,
 *    into which block taps can be added as extra strokes of the same gesture.
 */
class BlockAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var pad: View? = null
    private var proxyThread: HandlerThread? = null

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { hidePad(); instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { hidePad(); proxyThread?.quitSafely(); instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Dispatches a single-point tap. [done] runs on the main thread with (completed, System.nanoTime()).
     * Returns false if the system refused to dispatch.
     */
    fun tap(x: Float, y: Float, durationMs: Long, done: (Boolean, Long) -> Unit): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        return dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true, System.nanoTime())
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false, System.nanoTime())
        }, null)
    }

    // ------------------------------------------------------------------ overlay pad (⑥)

    /** Shows a touchable overlay window at the given SCREEN rectangle. [onTouch] gets every event + System.nanoTime(). */
    @SuppressLint("ClickableViewAccessibility")
    fun showPad(left: Int, top: Int, width: Int, height: Int, label: String, onTouch: (MotionEvent, Long) -> Unit): Boolean {
        hidePad()
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = object : View(this) {
                private val fill = Paint().apply { color = Color.argb(150, 40, 110, 255) }
                private val text = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true }
                override fun onDraw(canvas: Canvas) {
                    canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 28f, 28f, fill)
                    canvas.drawText(label, 24f, 56f, text)
                }
            }
            v.setOnTouchListener { _, e -> onTouch(e, System.nanoTime()); true }
            val lp = WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = left; y = top
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            wm.addView(v, lp)
            pad = v
            true
        } catch (t: Throwable) {
            android.util.Log.e("DunkBlockA11y", "showPad", t)
            false
        }
    }

    fun hidePad() {
        val v = pad ?: return
        pad = null
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
    }

    fun newProxyStick(segmentMs: Long, log: (String, Long) -> Unit): ProxyStick {
        val t = proxyThread ?: HandlerThread("dunkblock-proxy").also { it.start(); proxyThread = it }
        return ProxyStick(this, Handler(t.looper), segmentMs, log)
    }

    /**
     * A joystick finger held down by the service: a chain of continued strokes, one short segment per gesture,
     * the next one dispatched when the previous completes. [tap] adds a separate stroke to the next segment's
     * gesture, so joystick and block are ONE gesture and do not cancel each other.
     */
    class ProxyStick internal constructor(
        private val svc: AccessibilityService,
        private val handler: Handler,
        private val segmentMs: Long,
        private val log: (String, Long) -> Unit,
    ) {
        @Volatile var target: PointF? = null
        @Volatile private var ending = false
        @Volatile private var pendingTap: PointF? = null
        @Volatile private var pendingVariant = 0
        private var wiggle = 1f
        /** Segments that would have been zero-length (finger still) and were sent as a 1 px wiggle instead. */
        @Volatile var wiggles = 0; private set
        @Volatile var active = false; private set
        @Volatile var segments = 0; private set
        @Volatile var cancels = 0; private set
        @Volatile var refused = 0; private set
        var tapMs = 30L
        private var stroke: GestureDescription.StrokeDescription? = null
        private var last = PointF()

        fun start(origin: PointF) {
            handler.post {
                last = PointF(origin.x, origin.y)
                target = PointF(origin.x, origin.y)
                ending = false; stroke = null; active = true
                log("proxy_start", System.nanoTime())
                tick()
            }
        }

        fun stop() { ending = true }
        /**
         * Adds a block tap to the next segment. Variants (round 2 showed a single-point tap inside a continued
         * gesture was cancelled at once; which form works is measured, not assumed):
         *  0 = single point, [tapMs]   1 = 2 px line, [tapMs]   2 = 2 px line, same duration as the segment
         */
        fun tap(p: PointF, variant: Int = 1) { pendingVariant = variant; pendingTap = p }

        private fun tick() {
            if (!active) return
            val t0 = target ?: return
            // Round 2: zero-length continued segments (finger held still) were cancelled again and again.
            // Never send one: when the finger is still, move 1 px back and forth around the target.
            val t = if (t0.x == last.x && t0.y == last.y) { wiggles++; wiggle = -wiggle; PointF(t0.x + wiggle, t0.y) } else t0
            val path = Path().apply { moveTo(last.x, last.y); lineTo(t.x, t.y) }
            val cont = !ending
            val s = try {
                stroke?.continueStroke(path, 0, segmentMs, cont)
                    ?: GestureDescription.StrokeDescription(path, 0, segmentMs, cont)
            } catch (e: Throwable) {
                log("continue_failed", System.nanoTime())
                GestureDescription.StrokeDescription(path, 0, segmentMs, cont)
            }
            val b = GestureDescription.Builder().addStroke(s)
            val tp = pendingTap
            if (tp != null) {
                pendingTap = null
                val v = pendingVariant
                val tapPath = Path().apply { moveTo(tp.x, tp.y); if (v != 0) lineTo(tp.x + 2f, tp.y) }
                b.addStroke(GestureDescription.StrokeDescription(tapPath, 0, if (v == 2) segmentMs else tapMs))
                log("tap_dispatch_v$v", System.nanoTime())
            }
            stroke = s
            last = PointF(t.x, t.y)
            val ok = try {
                svc.dispatchGesture(b.build(), object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        segments++
                        if (!cont) { active = false; stroke = null; log("proxy_end", System.nanoTime()) } else tick()
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        cancels++
                        log("proxy_cancelled", System.nanoTime())
                        stroke = null // the held finger is gone; the next segment starts a new DOWN
                        if (cont && active) handler.postDelayed({ tick() }, 1) else { active = false }
                    }
                }, handler)
            } catch (e: Throwable) {
                log("dispatch_exception:${e.javaClass.simpleName}", System.nanoTime()); false
            }
            if (!ok) { refused++; active = false; stroke = null; log("dispatch_refused", System.nanoTime()) }
        }
    }

    companion object {
        @Volatile var instance: BlockAccessibilityService? = null
    }
}
