package dev.dunkblock.bench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.media.ImageReader
import android.util.Log
import dev.dunkblock.core.AttackEvent
import dev.dunkblock.core.AttackEventDetector
import dev.dunkblock.core.DetectorConfig
import dev.dunkblock.core.KotlinMotion
import dev.dunkblock.core.MotionBackend
import dev.dunkblock.core.MotionConfig
import dev.dunkblock.core.MotionFeatures
import dev.dunkblock.core.PatternCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Runs on the capture HandlerThread. Everything per frame is preallocated; the only allocations happen
 * when the capture size changes (rotation) or when an evidence image is being assembled for an event.
 */
class FramePipeline(
    private val session: Bench.Session,
    private val io: ExecutorService,
    private val evidenceDir: File,
) : ImageReader.OnImageAvailableListener {

    private val cfg = session.config
    private val log = session.frames
    private var primary: MotionBackend? = null
    private var alt: MotionBackend? = null
    private val feat: MotionFeatures
    private val altFeat: MotionFeatures
    private var motionCfg: MotionConfig? = null
    private var builtFor = 0L
    private var prevHash = 0
    private var lastFlipState = -1
    private var trackValid = false
    private var trackX = 0.5f
    private var trackY = 0.5f

    // evidence: ring of recent gray frames
    private val ringSize = 16
    private var ring: Array<ByteArray> = emptyArray()
    private val ringNs = LongArray(ringSize)
    private var ringPos = 0
    private var ringFilled = 0
    private var evidenceJob: EvidenceJob? = null
    private var evidenceCount = 0

    val detector: AttackEventDetector

    init {
        val cells = GRID_COLS * GRID_ROWS
        feat = MotionFeatures(cells)
        altFeat = MotionFeatures(cells)
        detector = AttackEventDetector(DetectorConfig(confirmFrames = cfg.confirmFrames), object : AttackEventDetector.Listener {
            override fun onDetected(e: AttackEvent) {
                session.lastEvent = e
                session.eventCount = e.id
                if (cfg.evidence && evidenceJob == null && evidenceCount < MAX_EVIDENCE && ring.isNotEmpty()) startEvidence(e)
            }
            override fun onFinalized(e: AttackEvent) { session.lastEvent = e }
        })
        session.detector = detector
    }

    override fun onImageAvailable(reader: ImageReader) {
        val tCallback = System.nanoTime()
        var k = 0
        while (true) {
            val img: Image = try { reader.acquireNextImage() } catch (e: IllegalStateException) { null } ?: break
            k++
            try {
                process(img, if (k == 1) tCallback else System.nanoTime(), k)
            } catch (t: Throwable) {
                Log.e(TAG, "frame processing failed", t)
                session.error = t.toString()
            } finally {
                img.close()
            }
        }
    }

    private fun ensureBackends(w: Int, h: Int, rowStride: Int) {
        val key = (w.toLong() shl 32) or h.toLong()
        if (key == builtFor) return
        builtFor = key
        primary?.release(); alt?.release()
        val aw = cfg.analysisWidth.coerceAtMost(w)
        val ah = (aw.toLong() * h / w).toInt().coerceAtLeast(16)
        val mc = MotionConfig(
            width = aw, height = ah, cols = GRID_COLS, rows = GRID_ROWS, diffThreshold = cfg.diffThreshold,
            excluded = if (cfg.excludeHud && w > h) MotionConfig.DEFAULT_EXCLUDED else emptyList(),
        )
        motionCfg = mc
        fun make(b: Bench.Backend): MotionBackend? = when (b) {
            Bench.Backend.KOTLIN -> KotlinMotion(mc, w, h, rowStride)
            Bench.Backend.OPENCV -> if (OpenCv.ready) OpenCvMotion(mc, cfg.opencvAreaResize) else null
            else -> null
        }
        when (cfg.backend) {
            Bench.Backend.KOTLIN -> primary = make(Bench.Backend.KOTLIN)
            Bench.Backend.OPENCV -> {
                primary = make(Bench.Backend.OPENCV)
                if (primary == null) { session.note("OpenCV 초기화 실패 → Kotlin 백엔드로 대체"); primary = make(Bench.Backend.KOTLIN) }
            }
            Bench.Backend.BOTH -> {
                primary = make(Bench.Backend.KOTLIN)
                alt = make(Bench.Backend.OPENCV)
                if (alt == null) session.note("OpenCV 초기화 실패 → 비교 불가")
            }
            Bench.Backend.NONE -> primary = null
        }
        ring = Array(ringSize) { ByteArray(aw * ah) }
        ringPos = 0; ringFilled = 0
        trackValid = false
        session.captureW = w; session.captureH = h
        session.note("분석 해상도 ${aw}x$ah (캡처 ${w}x$h, 백엔드 ${cfg.backend})")
    }

    private fun process(img: Image, arrivalNs: Long, burst: Int) {
        val slot = log.next()
        val plane = img.planes[0]
        val buf: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = img.width
        val h = img.height
        val ts = img.timestamp
        val tAccess = System.nanoTime()
        ensureBackends(w, h, rowStride)

        var counter = -1
        var patLuma = -1
        if (cfg.mode == Bench.Mode.LATENCY) {
            counter = PatternCodec.decode(buf, w, h, rowStride, pixelStride)
            patLuma = PatternCodec.flipLuma(buf, w, h, rowStride, pixelStride)
            val st = if (patLuma >= PatternCodec.LUMA_THRESHOLD) 1 else 0
            if (st != lastFlipState) { lastFlipState = st; Bench.flipSeenListener?.invoke(st) }
        }
        val hash = sampleHash(buf, w, h, rowStride, pixelStride)
        val dup = hash == prevHash
        prevHash = hash

        var tPre = 0L; var tRoi = 0L; var tMotion = 0L; var tAnalysis = 0L; var tAlt = 0L
        val p = primary
        if (p != null) {
            val a0 = System.nanoTime()
            p.pre(buf, w, h, rowStride, pixelStride)
            val a1 = System.nanoTime()
            p.roi(trackValid, trackX, trackY)
            val a2 = System.nanoTime()
            p.motion(feat)
            val a3 = System.nanoTime()
            if (feat.hasPrev) {
                detector.onFrame(arrivalNs, ts, feat.local, feat.global, feat.cy)
                trackValid = feat.local > 0.02f
                if (trackValid) { trackX = feat.cx; trackY = feat.cy }
            }
            val a4 = System.nanoTime()
            tPre = a1 - a0; tRoi = a2 - a1; tMotion = a3 - a2; tAnalysis = a4 - a0
            if (cfg.evidence) recordRing(p, arrivalNs)
        }
        val b = alt
        if (b != null) {
            val b0 = System.nanoTime()
            b.pre(buf, w, h, rowStride, pixelStride); b.roi(trackValid, trackX, trackY); b.motion(altFeat)
            tAlt = System.nanoTime() - b0
        }

        if (slot >= 0) {
            log.arrivalNs[slot] = arrivalNs
            log.imageTsNs[slot] = ts
            log.burst[slot] = burst.coerceAtMost(127).toByte()
            log.counter[slot] = counter
            log.patLuma[slot] = patLuma
            log.sampleHash[slot] = hash
            log.dupSampled[slot] = dup
            log.meanLuma100[slot] = if (p != null) feat.meanLuma100 else sampledLuma100
            log.tCopyUs[slot] = ((tAccess - arrivalNs) / 1000).toInt()
            log.tPreUs[slot] = (tPre / 1000).toInt()
            log.tRoiUs[slot] = (tRoi / 1000).toInt()
            log.tMotionUs[slot] = (tMotion / 1000).toInt()
            log.tAnalysisUs[slot] = (tAnalysis / 1000).toInt()
            log.tAltAnalysisUs[slot] = (tAlt / 1000).toInt()
            log.globalMotion[slot] = feat.global
            log.localMotion[slot] = feat.local
            log.tTotalUs[slot] = ((System.nanoTime() - arrivalNs) / 1000).toInt()
            log.commit()
        }
    }

    private var sampledLuma100 = 0

    /** FNV-1a over a 32x32 grid of pixels (1024 samples); also yields a coarse mean luma. */
    private fun sampleHash(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int): Int {
        var hsh = -0x7ee3623b
        var luma = 0L
        for (gy in 0 until 32) {
            val y = ((gy + 0.5f) * h / 32).toInt()
            val rb = y * rowStride
            for (gx in 0 until 32) {
                val x = ((gx + 0.5f) * w / 32).toInt()
                val o = rb + x * pixelStride
                val v = buf.getInt(o)
                hsh = (hsh xor v) * 0x01000193
                val r = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val bl = buf.get(o + 2).toInt() and 0xFF
                luma += (r * 77 + g * 151 + bl * 28) shr 8
            }
        }
        sampledLuma100 = (luma * 100 / 1024).toInt()
        return hsh
    }

    // ------------------------------------------------------------------ evidence images

    private class EvidenceJob(val event: AttackEvent, val before: List<Pair<Long, ByteArray>>) {
        val after = ArrayList<Pair<Long, ByteArray>>()
    }

    private fun recordRing(p: MotionBackend, tNs: Long) {
        val dst = ring[ringPos]
        p.copyLatestGray(dst)
        ringNs[ringPos] = tNs
        val job = evidenceJob
        if (job != null) {
            job.after.add(tNs to dst.copyOf())
            if (job.after.size >= ringSize) { evidenceJob = null; writeEvidence(job) }
        }
        ringPos = (ringPos + 1) % ringSize
        if (ringFilled < ringSize) ringFilled++
    }

    private fun startEvidence(e: AttackEvent) {
        val before = ArrayList<Pair<Long, ByteArray>>(ringFilled)
        for (i in 0 until ringFilled) {
            val idx = (ringPos - ringFilled + i + ringSize) % ringSize
            before.add(ringNs[idx] to ring[idx].copyOf())
        }
        evidenceCount++
        evidenceJob = EvidenceJob(e, before)
    }

    private fun writeEvidence(job: EvidenceJob) {
        val mc = motionCfg ?: return
        val aw = mc.width; val ah = mc.height
        val frames = job.before + job.after
        io.execute {
            try {
                val cols = 8
                val rows = (frames.size + cols - 1) / cols
                val label = 14
                val sheet = Bitmap.createBitmap(cols * aw, rows * (ah + label), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sheet)
                canvas.drawColor(Color.BLACK)
                val paint = Paint().apply { color = Color.YELLOW; textSize = 11f; isAntiAlias = true }
                val red = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f }
                val px = IntArray(aw * ah)
                for ((i, fr) in frames.withIndex()) {
                    val (t, g) = fr
                    for (j in px.indices) { val v = g[j].toInt() and 0xFF; px[j] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                    val tile = Bitmap.createBitmap(px, aw, ah, Bitmap.Config.ARGB_8888)
                    val x = (i % cols) * aw; val y = (i / cols) * (ah + label)
                    canvas.drawBitmap(tile, x.toFloat(), (y + label).toFloat(), null)
                    tile.recycle()
                    val rel = (t - job.event.onsetNs) / 1e6
                    canvas.drawText(String.format(Locale.ROOT, "%+.1fms", rel), x + 2f, y + 11f, paint)
                    if (t == job.event.onsetNs || t == job.event.detectNs)
                        canvas.drawRect(x + 1f, y + label + 1f, x + aw - 1f, y + label + ah - 1f, red)
                }
                evidenceDir.mkdirs()
                FileOutputStream(File(evidenceDir, String.format(Locale.ROOT, "event_%04d.png", job.event.id))).use {
                    sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                sheet.recycle()
            } catch (t: Throwable) {
                Log.e(TAG, "evidence write failed", t)
            }
        }
    }

    fun release() { primary?.release(); alt?.release() }

    companion object {
        private const val TAG = "DunkBlockPipeline"
        private const val MAX_EVIDENCE = 60
        const val GRID_COLS = 16
        const val GRID_ROWS = 9
    }
}
