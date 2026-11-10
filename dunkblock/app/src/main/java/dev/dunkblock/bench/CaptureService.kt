package dev.dunkblock.bench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Display
import dev.dunkblock.core.Csv
import dev.dunkblock.core.SessionReport
import dev.dunkblock.core.Stats
import dev.dunkblock.core.TruthMatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service (type mediaProjection) that owns the MediaProjection, the VirtualDisplay and the
 * ImageReader. Frames are processed on a dedicated high-priority thread; nothing is written to storage
 * until the session ends.
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var pipeline: FramePipeline? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var sampler: SystemSampler? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var dpi = 0
    private var stopping = false
    private lateinit var sessionDir: File

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSession("사용자 중지"); return START_NOT_STICKY }
        if (Bench.running) return START_NOT_STICKY
        if (stopping) {
            // This instance is still writing the previous session; the projection token cannot be kept for later.
            Bench.lastSummary = "이전 세션을 저장하는 중이었습니다. 몇 초 뒤 다시 시작하세요."
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        // Android 14+: startForeground(type=mediaProjection) must happen BEFORE getMediaProjection().
        startForegroundCompat()
        if (data == null) { failAndStop("캡처 권한 데이터 없음"); return START_NOT_STICKY }
        try {
            start(code, data)
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            failAndStop("캡처 시작 실패: $t")
        }
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent) {
        val cfg = Bench.config
        if (cfg.backend == Bench.Backend.OPENCV || cfg.backend == Bench.Backend.BOTH) OpenCv.init(cfg.opencvThreads)
        val session = Bench.Session(cfg, System.currentTimeMillis(), System.nanoTime())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(session.startedWallMs))
        sessionDir = File(getExternalFilesDir("sessions"), "${stamp}_${cfg.mode.name.lowercase(Locale.ROOT)}")
        sessionDir.mkdirs()
        Bench.session = session

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("getMediaProjection returned null")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSession("시스템이 캡처를 중지함") } }
            override fun onCapturedContentResize(width: Int, height: Int) {
                Bench.session?.note("캡처 콘텐츠 크기 변경 통지: ${width}x$height")
            }
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                Bench.session?.note("캡처 대상 가시성: $isVisible (t=${System.nanoTime()})")
            }
        }, main)

        val t = HandlerThread("dunkblock-capture", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        captureThread = t
        captureHandler = Handler(t.looper)
        val p = FramePipeline(session, io, File(sessionDir, "evidence"))
        pipeline = p

        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        dpi = resources.displayMetrics.densityDpi
        val (w, h) = captureSize(display, cfg.captureScale)
        val r = newReader(w, h)
        virtualDisplay = mp.createVirtualDisplay(
            "dunkblock-capture", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, captureHandler,
        )
        session.note("VirtualDisplay ${w}x$h dpi=$dpi scale=${cfg.captureScale} 표시 refresh=${display.refreshRate}Hz")

        // Rotation: the game is landscape while this UI is portrait; resize the capture when it changes.
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val (nw, nh) = captureSize(dm.getDisplay(Display.DEFAULT_DISPLAY), Bench.config.captureScale)
                val cur = reader ?: return
                if (nw == cur.width && nh == cur.height) return
                val nr = newReader(nw, nh)
                virtualDisplay?.resize(nw, nh, dpi)
                virtualDisplay?.surface = nr.surface
                captureHandler?.post { cur.close() }
                Bench.session?.note("회전/크기 변경 → 캡처 ${nw}x$nh (t=${System.nanoTime()})")
            }
        }
        displayListener = l
        dm.registerDisplayListener(l, main)

        sampler = SystemSampler(this, session).also { it.start() }
        Bench.stopRequester = { main.post { stopSession("테스트 종료") } }
        Bench.running = true
        Bench.lastSummary = ""
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener(pipeline, captureHandler)
        reader = r
        return r
    }

    @Suppress("DEPRECATION")
    private fun captureSize(display: Display, scale: Float): Pair<Int, Int> {
        val p = Point()
        display.getRealSize(p)
        fun even(v: Float) = (v.toInt() / 2 * 2).coerceAtLeast(64)
        return even(p.x * scale) to even(p.y * scale)
    }

    private fun stopSession(reason: String) {
        if (stopping) return
        stopping = true
        val session = Bench.session
        if (Bench.running) Bench.saving = true
        Bench.running = false
        Bench.stopRequester = null
        Bench.flipSeenListener = null
        sampler?.stop()
        try { BlockAccessibilityService.instance?.inGame?.finish("캡처 종료") } catch (_: Throwable) {}
        displayListener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        val t = captureThread
        val h = captureHandler
        // Let the capture thread finish the frame in flight, then tear down there.
        h?.post {
            try { reader?.close() } catch (_: Throwable) {}
            pipeline?.release()
            t?.quitSafely()
        }
        io.execute {
            try { t?.join(3000) } catch (_: InterruptedException) {}
            try {
                if (session != null) finish(session, reason)
            } catch (t: Throwable) {
                Log.e(TAG, "finish failed", t)
                Bench.lastSummary = "결과 저장 중 오류: $t"
            } finally {
                Bench.saving = false
            }
            main.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun finish(session: Bench.Session, reason: String) {
        val p = pipeline
        val det = p?.detector
        det?.flush(System.nanoTime())
        val events = det?.events ?: emptyList()
        val truth = session.truth.toList()
        val header = buildString {
            appendLine("DunkBlock Benchmark 세션 요약")
            appendLine("종료 사유: $reason")
            appendLine("설정: ${session.config}")
            appendLine("캡처 크기: ${session.captureW}x${session.captureH}")
            appendLine(DeviceInfo.short(this@CaptureService))
            session.error?.let { appendLine("!! 처리 오류: $it") }
            p?.auto?.let {
                appendLine("== ⑧ 자동 블록 초안 ==")
                appendLine("궁극기(화면 어두워짐) 감지 ${it.triggers}회 → 블록 누름 ${it.fired}회, 누르지 않음 ${it.skipped}회 " +
                    "(지연 ${session.config.ultDelayMs}ms, ${if (session.config.autoBlockLive) "실제 누름" else "기록만"})")
                appendLine("  일반 슛·덩크·레이업은 이 초안의 대상이 아닙니다. 블록 성공 여부는 앱이 판정하지 못합니다(clips/ 확인).")
                if (Bench.autoReport.isNotEmpty()) append(Bench.autoReport)
            }
            p?.clipper?.let { appendLine("블록 버튼 누름 감지 ${it.presses}회, 저장한 60fps 클립 ${it.clips}개 (블록 버튼 위치 ${session.config.blockFx}, ${session.config.blockFy})") }
            appendLine("-- 메모 --")
            append(synchronized(session.notes) { session.notes.toString() })
        }
        val summary = try {
            SessionReport.build(header, session.frames, session.display, events, det, session.seconds.toList(), session.config.warmupSec, truth)
        } catch (t: Throwable) {
            Log.e(TAG, "report failed", t); "$header\n요약 생성 실패: $t"
        }
        try {
            File(sessionDir, "summary.txt").writeText(summary)
            File(sessionDir, "frames.csv").bufferedWriter().use { Csv.frames(session.frames, it) }
            File(sessionDir, "events.csv").bufferedWriter().use { Csv.events(events, it) }
            File(sessionDir, "seconds.csv").bufferedWriter().use { Csv.seconds(session.seconds.toList(), it) }
            session.display?.let { d -> File(sessionDir, "display.csv").bufferedWriter().use { Csv.display(d, it) } }
            if (truth.isNotEmpty()) File(sessionDir, "truth.csv").bufferedWriter().use { TruthMatcher.csv(truth, it) }
            p?.clipper?.let { c -> File(sessionDir, "presses.csv").writeText(c.pressLog.toString()) }
            p?.auto?.let { a -> File(sessionDir, "autoblock.csv").writeText(a.csv()) }
            File(sessionDir, "device.txt").writeText(DeviceInfo.full(this))
        } catch (t: Throwable) {
            Log.e(TAG, "write failed", t)
        }
        val exported = Exporter.copyToDownloads(this, sessionDir)
        Bench.lastExportDir = exported
        Bench.lastSummary = summary
    }

    private fun failAndStop(msg: String) {
        Bench.lastSummary = msg
        Bench.running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "캡처 세션", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("DunkBlock 측정 중")
            .setContentText("화면 캡처·분석 중. 종료하려면 '중지'를 누르세요.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DunkBlockCapture"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "dev.dunkblock.bench.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }

        /** Rolling analysis-time stats over [from, to) of the frame log, ms. */
        fun analysisStats(s: Bench.Session, from: Int, to: Int) = Stats.summarizeInts(s.frames.tAnalysisUs, from, to, 1e-3)
    }
}
