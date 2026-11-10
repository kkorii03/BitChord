package dev.dunkblock.bench

import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Auto-block DRAFT (v0). One trigger only, the one that real footage supports so far:
 *
 *  ULTIMATE: when an opponent uses an ultimate the whole screen goes dark at once (replay clip 6: grid luma
 *  105 -> 21..30 for 0.96 s; five other clips never dropped below 101). In that clip the defender who got a clean
 *  "블록" left the ground about 0.64 s after the darkening began. So: detect the darkening, wait [ultDelayMs],
 *  press block. One clip is the whole basis — the delay is a setting, not a known constant.
 *
 * Not covered: ordinary shots, dunks, lay-ups. Two rules for those were tested on 9 clips and rejected
 * ("opponent outline rises": ~4 false fires per second; "body leaves its ring": 1 of 9 found).
 * Not checked: distance to the shooter, offence vs defence, whose ultimate it is.
 * The press goes out only while the user's thumb is on the joystick pad, in the same gesture as the stick.
 */
class AutoBlocker(private val cfg: Bench.Config, private val startedNs: Long) {
    private val main = Handler(Looper.getMainLooper())
    private var base = -1f
    private var darkRun = 0
    private var inDark = false
    private var darkSinceNs = 0L
    private var lastTriggerNs = 0L
    @Volatile var triggers = 0; private set
    @Volatile var fired = 0; private set
    @Volatile var skipped = 0; private set
    private val log = StringBuilder("t_ns,t_s,event,luma,baseline,detail\n")

    private fun rec(tNs: Long, ev: String, l: Float, detail: String) = synchronized(log) {
        log.append(tNs).append(',').append(String.format(Locale.ROOT, "%.3f,%s,%.1f,%.1f,%s\n", (tNs - startedNs) / 1e9, ev, l, base, detail))
    }

    fun csv(): String = synchronized(log) { log.toString() }

    /** Capture thread, every landscape frame. [luma100] = mean luma x100 of a 32x32 pixel grid over the frame. */
    fun onFrame(luma100: Int, tNs: Long, landscape: Boolean) {
        if (!landscape) return
        val l = luma100 / 100f
        if (base < 0) { base = l; return }
        val dark = base > 40f && l < DARK_FRACTION * base
        if (dark) {
            darkRun++
            if (!inDark && darkRun >= 3) {
                inDark = true
                darkSinceNs = tNs
                if (tNs - lastTriggerNs > 2_500_000_000L) {
                    lastTriggerNs = tNs
                    triggers++
                    rec(tNs, "ult_dark_detected", l, "delay_ms=${cfg.ultDelayMs}")
                    main.postDelayed({ fire() }, cfg.ultDelayMs.toLong())
                }
            } else if (inDark && tNs - darkSinceNs > 3_000_000_000L) {
                // Not an ultimate (loading screen, menu): adopt the new brightness instead of staying armed.
                inDark = false; darkRun = 0; base = l
                rec(tNs, "dark_timeout_rebase", l, "")
            }
        } else {
            darkRun = 0
            if (inDark && l > 0.8f * base) { inDark = false; rec(tNs, "ult_dark_end", l, String.format(Locale.ROOT, "dark_ms=%.0f", (tNs - darkSinceNs) / 1e6)) }
            if (!inDark) base += 0.02f * (l - base)
        }
    }

    private fun fire() {
        val now = System.nanoTime()
        if (!cfg.autoBlockLive) { skipped++; rec(now, "would_press", -1f, "shadow_mode"); return }
        val proxy = BlockAccessibilityService.instance?.inGame
        val ok = proxy?.fireBlock("ULT") ?: false
        if (ok) { fired++; rec(now, "pressed", -1f, "") } else { skipped++; rec(now, "not_pressed", -1f, if (proxy == null) "no_overlay" else "thumb_not_on_joystick") }
    }

    companion object { const val DARK_FRACTION = 0.45f }
}
