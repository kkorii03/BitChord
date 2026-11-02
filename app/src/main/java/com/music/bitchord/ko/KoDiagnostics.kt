package com.music.bitchord.ko

import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings

/**
 * The main thread's own stall record, written as the loop already measures it.
 *
 * [Looper.setMessageLogging] hands every dispatched message's start and finish
 * to this printer, so no timer or poll is added: a message that takes 200 ms
 * or longer leaves one line naming what ran. The fast path only compares two
 * timestamps and stores the start line; the message target is parsed and the
 * line is built only on the slow path.
 */
private const val MAX_TARGET_CHARS = 120

internal object KoDiagnostics {

    private const val TAG = "BitChord"
    private const val STALL_MS = 200L
    private const val RATE_LIMIT_MS = 2_000L
    private var startedAt = 0L
    private var startLine: String? = null
    private var lastLoggedAt = 0L

    fun install() {
        if (!AppSettings.mainStallLog.value) return
        Looper.getMainLooper().setMessageLogging(Printer { line ->
            if (line.startsWith(">>>>>")) {
                startedAt = SystemClock.uptimeMillis()
                startLine = line
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
    }
}

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
