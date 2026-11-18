package com.music.bitchord.ko

import android.content.Context
import android.os.Debug
import com.music.bitchord.data.TrackLog
import java.io.File
import kotlin.concurrent.thread

/**
 * [KO] oom1119: one heap line per track change, so a phone log shows the heap
 * trend song by song, plus a heap dump on request for a release build.
 *
 * 11-19 02:08:49 the app died of an OOM (256 MB growth limit) while two EJS
 * solves ran at once. A release build is not debuggable, so `am dumpheap` is
 * refused; the app may still dump its own heap. It does so only when someone
 * puts [REQUEST] in its external files folder over adb, once per request, on a
 * track change. Nothing here runs on a timer.
 */
object KoHeapTrend {
    private const val TAG = "BitChord"
    const val REQUEST = "heapdump.request"

    /** `heap: <why> java=<used>/<max>MB native=<allocated>MB`, used = live + not yet collected. */
    fun line(why: String, javaUsed: Long, javaMax: Long, nativeAllocated: Long): String =
        "heap: $why java=${javaUsed shr 20}/${javaMax shr 20}MB native=${nativeAllocated shr 20}MB"

    fun onTrackChange(context: Context, why: String) {
        val rt = Runtime.getRuntime()
        TrackLog.i(TAG, line(why, rt.totalMemory() - rt.freeMemory(), rt.maxMemory(), Debug.getNativeHeapAllocatedSize()))
        val app = context.applicationContext
        thread(name = "ko-heap-dump", isDaemon = true) { app.getExternalFilesDir(null)?.let(::dumpIfAsked) }
    }

    /** Takes the request away first, so one request is one dump even if the dump fails. */
    internal fun dumpIfAsked(dir: File): File? {
        if (!File(dir, REQUEST).delete()) return null
        val out = File(dir, "heap-${System.currentTimeMillis()}.hprof")
        return try {
            Debug.dumpHprofData(out.path)
            TrackLog.i(TAG, "heap: dumped ${out.length() shr 20}MB to ${out.name}")
            out
        } catch (e: Throwable) {
            TrackLog.w(TAG, "heap: dump failed type=${e::class.simpleName}")
            null
        }
    }
}
