package dev.dunkblock.bench

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records any crash to a file before the process dies, so the next launch can show what happened
 * (on a phone without a PC there is no other way to see the stack trace).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
                crashFile(this).writeText(
                    "시각: $stamp\n스레드: ${thread.name}\n측정 모드: ${Bench.config.mode}\n" +
                        "${DeviceInfo.short(this)}\n\n${android.util.Log.getStackTraceString(error)}",
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")
    }
}
