package com.music.bitchord.startup

import android.os.Process
import android.os.SystemClock
import com.music.bitchord.data.TrackLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two lines that say how long startup took, measured from process creation.
 *
 * Deliberately just two: the end of `Application.onCreate` and the moment the
 * first Home content is on screen. Anything per-frame would cost more than it
 * tells us, and these two are what change when the stores stop blocking launch.
 * Read them with `adb logcat -s BitChord`.
 */
object StartupTrace {

    private const val TAG = "BitChord"

    private val startUptime = Process.getStartUptimeMillis()
    private val homeLogged = AtomicBoolean(false)

    /** Called once at the end of `Application.onCreate`. */
    fun applicationCreated() = log("application.onCreate")

    /** Called the first time Home has content to draw; later calls are silent. */
    fun firstHomeContent() {
        if (homeLogged.compareAndSet(false, true)) log("first-home-content")
    }

    private fun log(what: String) {
        TrackLog.d(TAG, "startup $what: ${SystemClock.uptimeMillis() - startUptime} ms")
    }
}
