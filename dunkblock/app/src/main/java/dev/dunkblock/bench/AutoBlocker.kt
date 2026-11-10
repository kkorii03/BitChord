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
    @Volatile private var inDark = false
    @Volatile private var darkSinceNs = 0L
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
                    Bench.clipRequester?.invoke("ult", 1.0, 2.6)
                    live()
                    val sinceNs = tNs
                    main.post { BlockAccessibilityService.instance?.inGame?.armBlock(cfg.ultDelayMs.toLong() + 200) }
                    main.postDelayed({ fire(sinceNs) }, cfg.ultDelayMs.toLong())
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

    private fun fire(sinceNs: Long) {
        val now = System.nanoTime()
        // A real ultimate keeps the screen dark well past the delay (0.85 / 0.96 / 1.67 s seen); loading-screen flickers
        // that tripped v1022 lasted 0.15-0.53 s. Still dark from the same onset = press.
        if (!inDark || darkSinceNs != sinceNs) { skipped++; rec(now, "not_pressed", -1f, "dark_ended_early"); live(); return }
        if (!cfg.autoBlockLive) { skipped++; rec(now, "would_press", -1f, "shadow_mode"); return }
        val proxy = BlockAccessibilityService.instance?.inGame
        val ok = proxy?.fireBlock("ULT") ?: false
        if (ok) { fired++; rec(now, "pressed", -1f, ""); live() } else { skipped++; rec(now, "not_pressed", -1f, if (proxy == null) "no_overlay" else "no_recent_touch") }
    }

    private fun live() { Bench.autoLive = "궁극기 감지 ${triggers}회 · 블록 누름 ${fired}회 · 건너뜀 ${skipped}회" }

    companion object { const val DARK_FRACTION = 0.45f }
}
