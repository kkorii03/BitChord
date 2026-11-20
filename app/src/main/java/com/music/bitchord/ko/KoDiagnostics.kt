package com.music.bitchord.ko

import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import java.util.concurrent.atomic.AtomicLong

/**
 * The main thread's own stall record, written as the loop already measures it.
 *
 * [Looper.setMessageLogging] hands every dispatched message's start and finish
 * to this printer, so no timer or poll is added: a message that takes 200 ms
 * or longer leaves one line naming what ran. The fast path only compares two
 * timestamps and stores the start line; the message target is parsed and the
 * line is built only on the slow path.
 *
 * A message that never finishes (the process is killed for not responding)
 * would leave nothing that way, so the start is also readable from other
 * threads: when one of them writes a log line while the current message has
 * run past [HANG_MS], that thread writes the main thread's stack once for
 * that message ([checkHang]). It rides on lines written anyway; still no timer.
 */
private const val MAX_TARGET_CHARS = 120

/** How long one main-thread message must run before another thread reports its stack. */
internal const val HANG_MS = 5_000L

/** Frames of the main thread's stack written with a hang. */
private const val HANG_FRAMES = 25

internal object KoDiagnostics {

    private const val TAG = "BitChord"
    private const val STALL_MS = 200L
    private const val RATE_LIMIT_MS = 2_000L

    // Volatile: read by other threads' log writes in [checkHang].
    @Volatile private var startedAt = 0L
    @Volatile private var startLine: String? = null
    private var lastLoggedAt = 0L

    /** The [startedAt] of the message whose hang was already written. */
    private val hangReportedFor = AtomicLong(0L)

    fun install() {
        val mainThread = Looper.getMainLooper().thread
        Looper.getMainLooper().setMessageLogging(Printer { line ->
            // Read live: the kill switch can be turned off without a restart.
            if (!AppSettings.mainStallLog.value) {
                // A start left behind would read as a hang to [checkHang].
                if (startedAt != 0L) startedAt = 0L
                return@Printer
            }
            if (line.startsWith(">>>>>")) {
                startLine = line
                startedAt = SystemClock.uptimeMillis()
            } else if (line.startsWith("<<<<<")) {
                val began = startedAt
                if (began == 0L) return@Printer
                val target = startLine
                startedAt = 0L
                startLine = null
                val elapsed = SystemClock.uptimeMillis() - began
                if (elapsed < STALL_MS) return@Printer
                val now = SystemClock.uptimeMillis()
                if (lastLoggedAt != 0L && now - lastLoggedAt < RATE_LIMIT_MS) return@Printer
                lastLoggedAt = now
                TrackLog.w(TAG, stallLine(elapsed, messageTarget(target)))
            }
        })
        TrackLog.beforeWrite = { checkHang(mainThread) }
    }

    /**
     * Called on every log write. When not stuck this is one volatile read
     * (and a clock read while a message runs). Takes no lock the main thread
     * holds; the lines it writes come back here and stop at the
     * once-per-message check.
     */
    private fun checkHang(mainThread: Thread) {
        val began = startedAt
        if (began == 0L) return
        val reported = hangReportedFor.get()
        val now = SystemClock.uptimeMillis()
        if (!shouldReportHang(Thread.currentThread() === mainThread, now, began, reported)) return
        if (!hangReportedFor.compareAndSet(reported, began)) return
        val target = startLine
        // The message ended between the reads: nothing is stuck.
        if (startedAt != began) return
        val frames = mainThread.stackTrace
        TrackLog.w(TAG, hangLine(now - began, messageTarget(target)), about = null)
        frames.take(HANG_FRAMES).forEach { TrackLog.w(TAG, "main-hang:   at $it", about = null) }
    }
}

/**
 * Whether a log write should report the main thread as hung: never from the
 * main thread itself, only while a message runs, once that message has run
 * [thresholdMs], and only once per message ([reportedFor] is the start of the
 * last one reported).
 */
internal fun shouldReportHang(
    onMain: Boolean,
    nowMs: Long,
    startedAtMs: Long,
    reportedFor: Long,
    thresholdMs: Long = HANG_MS,
): Boolean = !onMain &&
    startedAtMs != 0L &&
    startedAtMs != reportedFor &&
    nowMs - startedAtMs >= thresholdMs

/** The handler/callback text from a Looper dispatch line, or a short fallback. */
internal fun messageTarget(line: String?): String {
    if (line.isNullOrBlank()) return "unknown"
    val body = line.substringAfter("Dispatching to ", line).trim()
    val afterHandler = body.substringAfter("} ", body)
    return afterHandler
        .substringBeforeLast(':')
        .trim()
        .ifBlank { body }
        .take(MAX_TARGET_CHARS)
}

/** The exported line for one slow main-thread message. */
internal fun stallLine(elapsedMs: Long, target: String): String =
    "main-stall: ${elapsedMs}ms $target"

/** The first line written for a main-thread message still running past [HANG_MS]. */
internal fun hangLine(elapsedMs: Long, target: String): String =
    "main-hang: ${elapsedMs}ms $target"
