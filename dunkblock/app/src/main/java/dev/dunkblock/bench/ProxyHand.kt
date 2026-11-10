package dev.dunkblock.bench

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import java.util.concurrent.ConcurrentHashMap

/**
 * Several held fingers injected as ONE continued gesture (generalises [BlockAccessibilityService.ProxyStick]).
 *
 * Why: a real finger on the game window while our injected joystick is held makes the game drop the joystick
 * (user, 11-10: pressing 패스/가속 while moving stops the character), and an injected tap cancels a real finger
 * (24/24). So every finger the user puts on the game goes onto one of our overlays instead, and each becomes a
 * stroke of the same injected gesture: the joystick thumb, a button thumb, and the auto-block tap.
 *
 * Rules carried over from the ProxyStick measurements:
 *  - one short segment per gesture, the next dispatched when the previous completes;
 *  - whole pixels, never a zero-length segment (1 px wiggle);
 *  - in a gesture that continues strokes, a NEW stroke starts 2 ms late (0 ms: 0/4 survived, 2 ms: 8/8);
 *  - after a cancel every finger starts again as a new touch (the joystick at its anchor).
 * Untested: more than one continued finger at a time. Every outcome is logged.
 * All gesture state lives on [handler]'s thread; [move] only writes a target.
 */
class ProxyHand(
    private val svc: AccessibilityService,
    private val handler: Handler,
    private val segmentMs: Long,
    private val log: (String) -> Unit,
) {
    private class Finger(val anchor: PointF?) {
        var stroke: GestureDescription.StrokeDescription? = null
        var last = PointF()
        var ending = false
        var wiggle = 1f
    }

    private val fingers = LinkedHashMap<Int, Finger>()
    private val targets = ConcurrentHashMap<Int, PointF>()
    private var inFlight = false
    private val pendingTaps = ArrayList<Pair<PointF, Boolean>>()
    var tapMs = 30L

    @Volatile var segments = 0; private set
    @Volatile var cancels = 0; private set
    @Volatile var refused = 0; private set
    @Volatile var wiggles = 0; private set
    @Volatile var maxFingers = 0; private set
    /** Auto-block taps: dispatched / completed / cancelled. */
    val taps = IntArray(3)
    @Volatile var held = 0; private set

    /** A finger went down at [p]. [anchor]: where it is put down again after a cancel (joystick centre); null = at its target. */
    fun down(id: Int, p: PointF, anchor: PointF? = null) {
        targets[id] = whole(p)
        handler.post {
            fingers[id] = Finger(anchor?.let { whole(it) })
            held = fingers.size
            if (fingers.size > maxFingers) maxFingers = fingers.size
            log("down_$id")
            kick()
        }
    }

    fun move(id: Int, p: PointF) { if (targets.containsKey(id)) targets[id] = whole(p) }

    fun up(id: Int) { handler.post { fingers[id]?.ending = true; kick() } }

    /**
     * One short tap, inside the held gesture when there is one, else on its own.
     * [auto] = the app's block press (counted in [taps]); false = a user's quick tap on a button overlay.
     */
    fun tap(p: PointF, auto: Boolean = true) { val q = whole(p); handler.post { pendingTaps.add(q to auto); kick() } }

    fun isHolding(id: Int): Boolean = targets.containsKey(id)

    /** Lifts every finger (end of run). */
    fun releaseAll() { handler.post { for (f in fingers.values) f.ending = true; kick() } }

    private fun whole(p: PointF) = PointF(Math.round(p.x).toFloat(), Math.round(p.y).toFloat())

    private fun kick() { if (!inFlight) tick() }

    private fun tick() {
        if (fingers.isEmpty() && pendingTaps.isEmpty()) { inFlight = false; return }
        val continuing = fingers.values.any { it.stroke != null }
        val newStart = if (continuing) 2L else 0L
        val b = GestureDescription.Builder()
        var strokes = 0
        val max = GestureDescription.getMaxStrokeCount()
        val sent = ArrayList<Pair<Int, Finger>>()
        for ((id, f) in fingers) {
            if (strokes >= max - 1) break
            val t0 = targets[id] ?: continue
            val fresh = f.stroke == null
            val from = if (fresh) (f.anchor ?: t0) else f.last
            val t = if (t0.x == from.x && t0.y == from.y) { wiggles++; f.wiggle = -f.wiggle; PointF(t0.x + f.wiggle, t0.y) } else t0
            val path = Path().apply { moveTo(from.x, from.y); lineTo(t.x, t.y) }
            val cont = !f.ending
            val s = try {
                if (fresh) GestureDescription.StrokeDescription(path, newStart, segmentMs - newStart, cont)
                else f.stroke!!.continueStroke(path, 0, segmentMs, cont)
            } catch (e: Throwable) {
                log("stroke_failed_$id"); f.stroke = null; null // next segment puts it down again as a new touch
            } ?: continue
            b.addStroke(s)
            strokes++
            f.stroke = s
            f.last = t
            sent.add(id to f)
        }
        var tapSent = false
        while (pendingTaps.isNotEmpty() && strokes < max) {
            val (tp, auto) = pendingTaps.removeAt(0)
            b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(tp.x, tp.y); lineTo(tp.x + 2f, tp.y) }, newStart, tapMs))
            strokes++
            if (auto) { tapSent = true; taps[0]++; log("tap_dispatch") } else log("click_dispatch")
        }
        if (strokes == 0) { inFlight = false; if (fingers.isNotEmpty()) handler.postDelayed({ kick() }, segmentMs); return }
        // Fingers whose last stroke is in this gesture are done whatever happens next.
        for ((id, f) in sent) if (f.ending) { fingers.remove(id); targets.remove(id); log("up_$id") }
        held = fingers.size
        inFlight = true
        val ok = try {
            svc.dispatchGesture(b.build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    segments++
                    if (tapSent) { taps[1]++; log("tap_completed") }
                    tick()
                }
                override fun onCancelled(g: GestureDescription?) {
                    cancels++
                    if (tapSent) taps[2]++
                    log(if (tapSent) "cancelled_with_tap" else "cancelled")
                    for (f in fingers.values) f.stroke = null // every held finger is gone; they come back as new touches
                    handler.postDelayed({ tick() }, 1)
                }
            }, handler)
        } catch (e: Throwable) {
            log("dispatch_exception:${e.javaClass.simpleName}"); false
        }
        if (!ok) {
            refused++; inFlight = false
            for (f in fingers.values) f.stroke = null
            log("dispatch_refused")
            // Held fingers would otherwise freeze until the next touch event; try again shortly (bounded by the fingers lifting).
            if (fingers.isNotEmpty()) handler.postDelayed({ kick() }, 50)
        }
    }
}
