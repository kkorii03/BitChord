package com.music.bitchord.startup

import android.os.Process
import android.os.SystemClock
import com.music.bitchord.data.TrackLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Two lines that say how long startup took.
 *
 * Deliberately just two: the end of `Application.onCreate` and the moment the
 * first Home content is on screen. Anything per-frame would cost more than it
 * tells us, and these two are what change when the stores stop blocking launch.
 * Read them with `adb logcat -s BitChord`.
 *
 * `application.onCreate` is measured from process creation. `first-home-content`
 * is measured from the process's first foreground, because a process the system
 * or the widget started in the background can sit for minutes before anyone
 * opens a window (LOG-1117 G3: 79 602 ms logged for a launch that took 0.36 s).
 * The time since process creation follows it as `since-process=`.
 */
object StartupTrace {

    private const val TAG = "BitChord"

    private val startUptime = Process.getStartUptimeMillis()
    private val homeLogged = AtomicBoolean(false)
    private val firstForegroundUptime = AtomicLong(0L)

    /** Called once at the end of `Application.onCreate`. */
    fun applicationCreated() {
        log("application.onCreate")
        // Before any activity can start: Application.onCreate always finishes
        // first, so the first foreground is never missed.
        AppForeground.onForeground { noteForeground() }
    }

    /** The first foreground of this process; later ones keep the first. */
    private fun noteForeground() {
        firstForegroundUptime.compareAndSet(0L, SystemClock.uptimeMillis())
    }

    /** Called the first time Home has content to draw; later calls are silent. */
    fun firstHomeContent() {
        if (homeLogged.compareAndSet(false, true)) {
            TrackLog.d(
                TAG,
                homeLine(SystemClock.uptimeMillis(), startUptime, firstForegroundUptime.get()),
            )
        }
    }

    /**
     * `startup first-home-content: <from foreground> ms since-process=<from start> ms`.
     * With no foreground recorded (0) it falls back to the process start, which
     * is what the line measured before.
     */
    internal fun homeLine(nowUptime: Long, processStartUptime: Long, foregroundUptime: Long): String {
        val from = if (foregroundUptime > 0L) foregroundUptime else processStartUptime
        return "startup first-home-content: ${nowUptime - from} ms " +
            "since-process=${nowUptime - processStartUptime} ms"
    }

    private fun log(what: String) {
        TrackLog.d(TAG, "startup $what: ${SystemClock.uptimeMillis() - startUptime} ms")
    }
}
