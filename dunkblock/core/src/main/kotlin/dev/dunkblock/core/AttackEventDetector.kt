package dev.dunkblock.core

data class DetectorConfig(
    /** Absolute minimum local-motion energy to start / keep an event. */
    val absOn: Float = 0.06f,
    val absOff: Float = 0.03f,
    /** Also require energy to exceed the learned quiet-time baseline by this factor. */
    val kOn: Float = 4f,
    val kOff: Float = 2f,
    /** Frames with this much whole-screen motion (camera pan, cut-in, scene change) are ignored. */
    val globalSuppress: Float = 0.30f,
    /** The signal is local - globalWeight * global, so a wide pan cannot masquerade as a player. */
    val globalWeight: Float = 1.0f,
    /** Consecutive over-threshold frames needed to confirm an onset (this IS detection latency; 1 = fastest, noisiest). */
    val confirmFrames: Int = 2,
    /** Signal must stay under the off threshold this long to end an event (bridges short dips). */
    val endHoldMs: Float = 120f,
    /** A new onset within this time after an event ended is merged into it, not reported again. */
    val cooldownMs: Float = 300f,
    val shortBurstMs: Float = 150f,
    /** Net upward travel of the activity centroid (fraction of frame height) to label an event UPWARD. */
    val upwardMinDy: Float = 0.06f,
    val baselineAlpha: Float = 0.02f,
    val warmupFrames: Int = 30,
)

/**
 * One motion event. NOTE: this is a *motion burst*, not a classified basketball action. [pattern] is a
 * purely descriptive label from motion geometry; it is NOT a shot/dunk/layup/fake classifier.
 */
class AttackEvent(
    val id: Int,
    /** Arrival time (System.nanoTime base) of the first over-threshold frame. */
    val onsetNs: Long,
    val onsetImageTs: Long,
    /** Arrival time when the onset was confirmed (confirmFrames reached): the earliest moment a decision could be made. */
    val detectNs: Long,
    val startCy: Float,
) {
    var endNs: Long = 0
    var peak: Float = 0f
    var peakNs: Long = 0
    var minCy: Float = startCy
    var maxCy: Float = startCy
    var peakGlobal: Float = 0f
    var mergedBursts: Int = 0
    var pattern: String = "OPEN"
    val durationMs get() = if (endNs > 0) (endNs - onsetNs) / 1e6 else Double.NaN
    val confirmDelayMs get() = (detectNs - onsetNs) / 1e6
    val riseMs get() = (peakNs - onsetNs) / 1e6
    val netUp get() = startCy - minCy
}

class AttackEventDetector(
    private val cfg: DetectorConfig = DetectorConfig(),
    private val listener: Listener? = null,
) {
    interface Listener {
        /** Called on the capture thread at confirmation time. Keep it trivial. */
        fun onDetected(e: AttackEvent)
        fun onFinalized(e: AttackEvent)
    }

    val events = ArrayList<AttackEvent>()
    var suppressedGlobalFrames = 0; private set
    var mergedDuplicates = 0; private set

    private var frames = 0
    private var baseline = 0.01f
    private var candCount = 0
    private var candOnsetNs = 0L
    private var candOnsetImg = 0L
    private var candCy = 0.5f
    private var current: AttackEvent? = null
    private var belowSinceNs = -1L
    private var lastEndNs = Long.MIN_VALUE
    private var lastEvent: AttackEvent? = null

    /** Current learned quiet-time baseline of the local-motion signal (for logging). */
    val baselineValue get() = baseline

    /** True while an event is open. */
    val active get() = current != null

    fun onFrame(tNs: Long, imageTs: Long, local: Float, global: Float, cy: Float) {
        frames++
        val suppressed = global > cfg.globalSuppress
        if (suppressed) suppressedGlobalFrames++
        val s = if (suppressed) 0f else (local - cfg.globalWeight * global).coerceAtLeast(0f)
        val thrOn = maxOf(cfg.absOn, baseline * cfg.kOn)
        val thrOff = maxOf(cfg.absOff, baseline * cfg.kOff)

        if (frames <= cfg.warmupFrames) { baseline += cfg.baselineAlpha * 4 * (s - baseline); return }

        val cur = current
        if (cur == null) {
            if (s >= thrOn) {
                if (candCount == 0) { candOnsetNs = tNs; candOnsetImg = imageTs; candCy = cy }
                candCount++
                if (candCount >= cfg.confirmFrames) { open(tNs, s, global, cy) }
            } else {
                candCount = 0
                baseline += cfg.baselineAlpha * (s - baseline)
            }
        } else {
            if (s > cur.peak) { cur.peak = s; cur.peakNs = tNs }
            if (global > cur.peakGlobal) cur.peakGlobal = global
            if (s >= thrOff) {
                if (cy < cur.minCy) cur.minCy = cy
                if (cy > cur.maxCy) cur.maxCy = cy
                belowSinceNs = -1L
            } else {
                if (belowSinceNs < 0) belowSinceNs = tNs
                if ((tNs - belowSinceNs) / 1e6 >= cfg.endHoldMs) finalize(belowSinceNs)
            }
        }
    }

    private fun open(tNs: Long, s: Float, global: Float, cy: Float) {
        candCount = 0
        val last = lastEvent
        if (last != null && (candOnsetNs - lastEndNs) / 1e6 <= cfg.cooldownMs) {
            // Same action continuing / re-triggering: merge, do not report a second event.
            last.mergedBursts++
            mergedDuplicates++
            last.endNs = 0
            last.pattern = "OPEN"
            if (s > last.peak) { last.peak = s; last.peakNs = tNs }
            current = last
            belowSinceNs = -1L
            return
        }
        val e = AttackEvent(events.size + 1, candOnsetNs, candOnsetImg, tNs, candCy)
        e.peak = s; e.peakNs = tNs; e.peakGlobal = global
        e.minCy = minOf(candCy, cy); e.maxCy = maxOf(candCy, cy)
        events.add(e)
        current = e
        lastEvent = e
        belowSinceNs = -1L
        listener?.onDetected(e)
    }

    private fun finalize(endNs: Long) {
        val e = current ?: return
        e.endNs = endNs
        e.pattern = when {
            e.durationMs < cfg.shortBurstMs -> "SHORT_BURST"
            e.netUp >= cfg.upwardMinDy -> "UPWARD_BURST"
            else -> "LATERAL_BURST"
        }
        lastEndNs = endNs
        current = null
        belowSinceNs = -1L
        listener?.onFinalized(e)
    }

    /** Close any open event at session end. */
    fun flush(tNs: Long) { if (current != null) finalize(tNs) }
}
