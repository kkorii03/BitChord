package dev.dunkblock.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import dev.dunkblock.core.Csv
import java.io.File
import java.util.Locale

/**
 * Once per second, on its own low-priority thread: capture fps, analysis-time stats for that second,
 * this app's own CPU time (whole-system CPU is not readable by apps since Android 8), thermal status /
 * headroom and battery temperature. Also refreshes the live text for the control screen.
 */
class SystemSampler(private val ctx: Context, private val session: Bench.Session) {
    private val thread = HandlerThread("dunkblock-sampler", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var handler: Handler
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private var lastSize = 0
    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallNs = System.nanoTime()
    private var tick = 0
    @Volatile private var running = false

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
        running = true
        handler.postDelayed(::sample, 1000)
    }

    fun stop() {
        running = false
        thread.quitSafely()
    }

    private fun sample() {
        if (!running) return
        try {
            tick++
            val size = session.frames.size
            val now = System.nanoTime()
            val cpu = Process.getElapsedCpuTime()
            val cores = (cpu - lastCpuMs) / ((now - lastWallNs) / 1e6)
            val st = CaptureService.analysisStats(session, lastSize, size)
            val thermal = pm.currentThermalStatus
            val headroom = try { pm.getThermalHeadroom(10).toDouble() } catch (_: Throwable) { Double.NaN }
            val tempC = try {
                val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
                    .let { if (it == Int.MIN_VALUE) Double.NaN else it / 10.0 }
            } catch (_: Throwable) { Double.NaN }
            val freq = maxCpuFreqKhz()
            val fps = size - lastSize
            session.seconds.add(Csv.SecondRow(tick, fps, st.mean, st.p95, cores, thermal, headroom, tempC, freq))
            val ev = session.lastEvent
            Bench.liveText = String.format(
                Locale.ROOT,
                "측정 %ds | 수신 %d fps | 분석 평균 %.2fms p95 %.2fms max %.2fms\n앱 CPU %.2f코어 | 열상태 %d | 헤드룸 %.2f | 배터리 %.1f°C\n이벤트 %d건%s",
                tick, fps, st.mean, st.p95, st.max, cores, thermal, headroom, tempC, session.eventCount,
                if (ev != null) " | 최근 #${ev.id} ${ev.pattern} 확정지연 ${"%.1f".format(Locale.ROOT, ev.confirmDelayMs)}ms" else "",
            ) + (session.error?.let { "\n오류: $it" } ?: "")
            lastSize = size; lastCpuMs = cpu; lastWallNs = now
        } catch (_: Throwable) {
        }
        if (running) handler.postDelayed(::sample, 1000)
    }

    /** Best effort: many devices deny apps read access to cpufreq; then -1 is logged. */
    private fun maxCpuFreqKhz(): Long {
        var best = -1L
        for (i in 0 until 8) {
            try {
                val v = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong()
                if (v > best) best = v
            } catch (_: Throwable) {}
        }
        return best
    }
}
