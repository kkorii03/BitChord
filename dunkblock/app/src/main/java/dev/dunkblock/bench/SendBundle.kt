package dev.dunkblock.bench

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds up to [MAX_PARTS] zips, each small enough to attach to a chat (below the 30 MB attachment limit).
 * Files go in by priority: summaries first, then small CSVs, then the per-frame logs, then a few
 * evidence images. Whatever does not fit is listed in MANIFEST.txt instead of silently disappearing.
 */
object SendBundle {
    private const val BUDGET = 25L * 1024 * 1024
    /** Up to this many zips, each under [BUDGET], attached one by one. */
    private const val MAX_PARTS = 10   // vc1137: 4 parts left 52.5 MB out of the 11-12 18:12 match (user: 10 is fine)
    private const val MANIFEST_RESERVE = 256L * 1024

    private fun priority(f: File): Int = when {
        f.name.endsWith(".txt") -> 0
        f.name in setOf("events.csv", "seconds.csv", "truth.csv", "touch_probe.csv", "ingame_tap.csv") -> 1
        f.name == "display.csv" -> 2
        f.name == "frames.csv" -> 3
        f.name == "touch_events.csv" -> 4
        f.name in setOf("presses.csv", "clips.csv", "autoblock.csv", "shot_events.csv") -> 1
        f.name == "shot.csv" || f.name == "matchup.csv" -> 2
        f.name == "ingame_proxy_events.csv" -> 4
        f.name == "crops.csv" -> 2
        // Auto-block trigger clips decide success vs '블록 위치 부족', so they go before every other image (vc1033: they
        // were the ones left out).
        f.name.endsWith(".jpg") && f.parentFile?.name?.contains("_trig_") == true -> 5
        // 기록-button clips are the only record of real shots the trigger missed (vc1044 match: none arrived).
        f.name.endsWith(".jpg") && f.parentFile?.name?.contains("_mark_") == true -> 5
        f.name.endsWith("_trig.jpg") -> 5                                 // colour screen at a trigger: where my player stood
        // vc1136: full-res colour around the ball goes before grey clip frames. 11-12 18:12 match: all 14 up_def/crouch
        // colour clips were left out as '용량 초과' while 20 grey trig_dark clips (dark detector, press off) went in.
        f.name.endsWith(".jpg") && f.parentFile?.name?.startsWith("colour_") == true -> 5
        f.name.endsWith(".jpg") && f.parentFile?.name?.contains("_trig_dark_") == true -> 7
        f.name.endsWith(".jpg") && f.parentFile?.name == "frames" -> 6   // colour game screens (thinned to 60 per session)
        f.name.endsWith(".jpg") -> 6                                      // clip frames
        f.name.endsWith(".png") -> 7
        else -> 8
    }

    /** Returns a human-readable result line. Runs on a background thread. */
    fun build(ctx: Context): String {
        val root = ctx.getExternalFilesDir("sessions") ?: return "세션 폴더가 없습니다."
        // Only the latest run: the game session and the in-game proxy session start together (same yyyyMMdd_HHmmss
        // prefix, give or take a minute). vc1033: five old sessions filled the 100 MB and pushed out the new clips.
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
        fun startMs(d: File): Long = try { fmt.parse(d.name.take(15))?.time ?: -1L } catch (e: Exception) { -1L }
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val newest = dirs.maxOfOrNull { startMs(it) } ?: -1L
        val keepDirs = dirs.filter { newest < 0 || startMs(it).let { s -> s < 0 || newest - s <= 120_000L } }.toSet()
        val older = dirs.size - keepDirs.size
        val files = root.walkTopDown().filter { it.isFile && (it.parentFile == root || keepDirs.any { d -> it.startsWith(d) }) }.toMutableList()
        listOf(File(ctx.filesDir, "last_crash.txt"), File(ctx.filesDir, "last_crash_seen.txt")).filter { it.isFile }.forEach { files.add(it) }
        if (files.isEmpty()) return "보낼 결과가 없습니다. 먼저 시험을 하나 실행하세요."
        // Newest sessions first inside each priority; at most 12 evidence images overall.
        // vc1138: one lastModified() per folder, not one per comparison (2,000+ files made the sort itself slow).
        val dirTime = HashMap<File?, Long>()
        fun dirMs(f: File): Long = dirTime.getOrPut(f.parentFile) { f.parentFile?.lastModified() ?: 0L }
        val ordered = files.sortedWith(compareBy<File>({ priority(it) }, { -dirMs(it) }, { it.path }))
        var images = 0
        // Colour screen samples: keep 60 evenly spaced per session so clips still fit.
        val thin = HashSet<File>()
        files.filter { it.name.endsWith(".jpg") && !it.name.endsWith("_trig.jpg") && it.parentFile?.name == "frames" }.groupBy { it.parentFile }.forEach { (_, list) ->
            val sorted = list.sortedBy { it.name }
            if (sorted.size > 60) {
                val keep = (0 until 60).map { sorted[it * sorted.size / 60] }.toSet()
                sorted.filter { it !in keep }.forEach { thin.add(it) }
            }
        }
        // Pass 1: assign each file to the first part (zip) it still fits in. A chat attachment is limited to 30 MB,
        // so each part stays under BUDGET and the user attaches the parts one by one (11-10 user request).
        val skipped = ArrayList<String>()
        val parts = ArrayList<ArrayList<Pair<File, String>>>()
        val partUsed = ArrayList<Long>()
        for (f in ordered) {
            val rel = if (f.startsWith(root)) f.relativeTo(root).path else "app/${f.name}"
            if (f in thin) continue
            if (f.name.endsWith(".png") && images >= 12) { skipped.add("$rel (증거 이미지 12장 초과)"); continue }
            val est = if (isImage(f)) f.length() else compressedSize(f.readBytes(), false)
            var p = partUsed.indexOfFirst { it + est + 4096 <= BUDGET - MANIFEST_RESERVE }
            if (p < 0 && parts.size < MAX_PARTS && est + 4096 <= BUDGET - MANIFEST_RESERVE) {
                parts.add(ArrayList()); partUsed.add(0L); p = parts.size - 1
            }
            if (p < 0) { skipped.add("$rel (용량 초과, ${f.length() / 1024} KB)"); continue }
            parts[p].add(f to rel)
            partUsed[p] = partUsed[p] + est + 200
            if (f.name.endsWith(".png")) images++
        }
        if (parts.isEmpty()) return "보낼 결과가 없습니다."
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val n = parts.size
        val names = (1..n).map { if (n == 1) "DunkBlock_send_$stamp.zip" else "DunkBlock_send_${stamp}_${it}of$n.zip" }
        val manifest = buildString {
            appendLine("DunkBlock 전송용 묶음 (${n}개 파일로 나눔)")
            appendLine(DeviceInfo.short(ctx))
            if (older > 0) appendLine("가장 최근 시험만 담음 (이전 세션 ${older}개는 폰의 앱 폴더에 남아 있음)")
            if (thin.isNotEmpty()) appendLine("경기 화면 사진은 세션당 60장만 골라 담음(제외 ${thin.size}장)")
            parts.forEachIndexed { i, list ->
                appendLine("${names[i]}: ${list.size}개")
                list.forEach { appendLine("  ${it.second}") }
            }
            if (skipped.isNotEmpty()) {
                appendLine("용량 때문에 뺀 파일 ${skipped.size}개 (폰의 앱 폴더에는 남아 있음):")
                skipped.forEach { appendLine("  $it") }
            }
        }.toByteArray()
        // Pass 2: write each part straight to Download/DunkBlock (no whole-zip copy in memory).
        var totalMb = 0.0
        var written = 0
        parts.forEachIndexed { i, list ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, names[i])
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DunkBlock")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "전송용 묶음 저장 실패 (${i + 1}/$n)"
            // vc1138: buffered (the zip stream writes 512-byte pieces, each one a call into MediaStore's file descriptor).
            val raw = ctx.contentResolver.openOutputStream(uri) ?: return "전송용 묶음 저장 실패 (${i + 1}/$n)"
            val counter = CountingStream(raw.buffered(1 shl 20))
            ZipOutputStream(counter).use { zip ->
                for ((f, rel) in list) {
                    // vc1138: JPG/PNG are already compressed; BEST_COMPRESSION on 2,000+ of them made the bundle take
                    // minutes for ~0% gain. Text keeps the level the size estimate used.
                    zip.setLevel(if (isImage(f)) Deflater.NO_COMPRESSION else TEXT_LEVEL)
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                    written++
                }
                zip.setLevel(TEXT_LEVEL)
                zip.putNextEntry(ZipEntry(if (i == 0) "MANIFEST.txt" else "MANIFEST_${i + 1}of$n.txt"))
                zip.write(manifest)
                zip.closeEntry()
            }
            totalMb += counter.count / (1024.0 * 1024.0)
        }
        val list = if (n == 1) names[0] else "${names[0]} 외 ${n - 1}개 (_1of$n ~ _${n}of$n, 전부 첨부)"
        return String.format(Locale.ROOT, "전송용 묶음 완료: Download/DunkBlock/%s (합계 %.1f MB, 파일 %d개%s)", list, totalMb, written,
            if (skipped.isNotEmpty()) ", 용량 때문에 ${skipped.size}개 제외 — MANIFEST.txt 참고" else "")
    }

    private class CountingStream(private val out: java.io.OutputStream) : java.io.OutputStream() {
        var count = 0L; private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    /** Same level for the size estimate and the real write, so the estimate stays exact. */
    private const val TEXT_LEVEL = Deflater.DEFAULT_COMPRESSION

    private fun isImage(f: File) = f.name.endsWith(".jpg") || f.name.endsWith(".png")

    private fun compressedSize(raw: ByteArray, alreadyCompressed: Boolean): Long {
        if (alreadyCompressed) return raw.size.toLong()
        val d = Deflater(TEXT_LEVEL)
        d.setInput(raw); d.finish()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (!d.finished()) total += d.deflate(buf)
        d.end()
        return total
    }
}
