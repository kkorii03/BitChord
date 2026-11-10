package dev.dunkblock.bench

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ⑤ In-game check: while the user plays and holds the movement joystick, inject taps at a chosen position.
 * The app cannot see the game's input handling, so the outcome (did movement stop?) is observed by the user;
 * the app records only what it can: dispatch accepted / completed / cancelled and timings.
 */
object InGameTapTest {
    @Volatile var status = ""
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fun start(ctx: Context, xPct: Float, yPct: Float, count: Int, intervalMs: Long, delayMs: Long, tapMs: Long) {
        val svc = BlockAccessibilityService.instance
        if (svc == null) { status = "접근성 서비스가 꺼져 있습니다."; return }
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point(); d.getRealSize(p)
        val longSide = maxOf(p.x, p.y); val shortSide = minOf(p.x, p.y)
        // Coordinates are given for the landscape game; the tap is computed when it fires, for the orientation then.
        val rows = StringBuilder("n,dispatch_ns,accepted,completed,callback_ms,x,y\n")
        status = "⑤ ${delayMs / 1000}초 후 시작: ${count}회, ${intervalMs}ms 간격, 탭 ${tapMs}ms"
        val app = ctx.applicationContext
        for (i in 0 until count) {
            main.postDelayed({
                val s = BlockAccessibilityService.instance ?: return@postDelayed
                val q = Point(); d.getRealSize(q)
                val landscape = q.x >= q.y
                val x = (if (landscape) longSide else shortSide) * xPct / 100f
                val y = (if (landscape) shortSide else longSide) * yPct / 100f
                val t0 = System.nanoTime()
                val ok = s.tap(x, y, tapMs) { completed, t1 ->
                    synchronized(rows) { rows.append("${i + 1},$t0,true,$completed,${"%.2f".format(Locale.ROOT, (t1 - t0) / 1e6)},$x,$y\n") }
                }
                if (!ok) synchronized(rows) { rows.append("${i + 1},$t0,false,,,${x},$y\n") }
                status = "⑤ 탭 ${i + 1}/$count 전송 (${x.toInt()},${y.toInt()}) ${if (landscape) "가로" else "세로"}"
                if (i == count - 1) main.postDelayed({
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                    val dir = File(app.getExternalFilesDir("sessions"), "${stamp}_ingame_tap").apply { mkdirs() }
                    File(dir, "ingame_tap.csv").writeText(synchronized(rows) { rows.toString() })
                    File(dir, "README.txt").writeText(
                        "⑤ 인게임 자동 탭 시험 기록. 앱은 게임 내부 입력 처리를 볼 수 없으므로, '탭 순간 캐릭터 이동이 끊겼는가'는 " +
                            "사용자가 관찰해 기록해야 합니다 (예: 화면 녹화를 다른 기기로 촬영).\n" +
                            "설정: x=$xPct% y=$yPct% count=$count interval=${intervalMs}ms tap=${tapMs}ms\n",
                    )
                    status = "⑤ 완료. 저장: " + Exporter.copyToDownloads(app, dir)
                }, 1000)
            }, delayMs + i * intervalMs)
        }
    }
}
