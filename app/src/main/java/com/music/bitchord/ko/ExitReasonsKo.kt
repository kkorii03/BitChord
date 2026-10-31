package com.music.bitchord.ko

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.music.bitchord.data.TrackLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android's own record of why this process went away, read once per process
 * start and written into [TrackLog].
 *
 * An "app not responding" dialog leaves no line in the app's own log: the
 * process that hung is killed and the next process starts with no memory of
 * it. [ActivityManager.getHistoricalProcessExitReasons] is the one place that
 * evidence survives, and for an ANR it carries the main thread's stack — the
 * lines that say what blocked.
 *
 * The read is event-driven: once, from `Application.onCreate`, on the IO
 * dispatcher. Nothing here polls or runs on a timer. The newest reported
 * timestamp is kept in preferences so the same exits are not repeated in every
 * later export.
 */
internal object ExitReasonsKo {

    private const val TAG = "BitChord"

    private const val PREFS = "bitchord_exit_reasons"
    private const val KEY_LAST_REPORTED_AT = "last_reported_at"

    /** Android keeps more, but the last few launches are what a report needs. */
    private const val MAX_EXITS = 5

    /** Enough of the ANR stack to see the call that stalled the main thread. */
    private const val MAX_ANR_TRACE_LINES = 60

    fun report(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val app = context.applicationContext
        val prefs = runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        val lastReportedAt = prefs.getLong(KEY_LAST_REPORTED_AT, 0L)

        val manager = app.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(app.packageName, 0, MAX_EXITS)
        }.getOrNull().orEmpty()

        var newest = lastReportedAt
        // Oldest first, so the export reads chronologically and a failure on
        // one exit does not leave the marker beyond the ones not written yet.
        exits.sortedBy { it.timestamp }.forEach { exit ->
            if (exit.timestamp <= lastReportedAt) return@forEach
            TrackLog.i(
                TAG,
                formatExitLine(
                    reason = exit.reason,
                    atMs = exit.timestamp,
                    importance = exit.importance,
                    description = exit.description,
                ),
            )
            if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    exit.traceInputStream?.bufferedReader()?.use { reader ->
                        mainThreadTraceLines(reader.lineSequence(), MAX_ANR_TRACE_LINES)
                            .forEach { line -> TrackLog.i(TAG, "exit-anr: $line") }
                    }
                }
            }
            if (exit.timestamp > newest) newest = exit.timestamp
        }

        if (newest > lastReportedAt) {
            prefs.edit().putLong(KEY_LAST_REPORTED_AT, newest).apply()
        }
    }
}

/** `reason=<NAME>` for one of [ApplicationExitInfo]'s REASON_* values. */
internal fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
    ApplicationExitInfo.REASON_CRASH -> "CRASH"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
    ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
    ApplicationExitInfo.REASON_OTHER -> "OTHER"
    ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
    ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
    else -> "OTHER($reason)"
}

/** One exported line per historical exit, in the diagnostic log's own wording. */
internal fun formatExitLine(
    reason: Int,
    atMs: Long,
    importance: Int,
    description: String?,
): String {
    val at = EXIT_CLOCK.get().format(Date(atMs))
    val desc = description
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.joinToString(" ")
        ?.takeIf(String::isNotEmpty)
        ?: "-"
    return "exit: reason=${exitReasonName(reason)} at=$at importance=$importance desc=$desc"
}

/**
 * The lines of the `main` thread from an ANR trace: its header, then its
 * stack up to the next thread or [maxLines]. Streamed rather than read whole,
 * so a tombstone with every thread costs no more than the section wanted.
 */
internal fun mainThreadTraceLines(lines: Sequence<String>, maxLines: Int = 60): List<String> {
    if (maxLines <= 0) return emptyList()
    val out = ArrayList<String>(maxLines)
    var inMain = false
    for (line in lines) {
        val trimmed = line.trimStart()
        if (!inMain) {
            if (trimmed.startsWith("\"main\"")) {
                inMain = true
                out += trimmed
                if (out.size >= maxLines) break
            }
            continue
        }
        // The next thread's header ends the main thread's section.
        if (trimmed.startsWith("\"")) break
        if (trimmed.isNotEmpty()) out += trimmed
        if (out.size >= maxLines) break
    }
    return out
}

/** Per-thread formatter: [SimpleDateFormat] is not safe to share. */
private val EXIT_CLOCK = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
