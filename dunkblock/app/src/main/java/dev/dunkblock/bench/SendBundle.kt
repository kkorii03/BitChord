package dev.dunkblock.bench

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds ONE zip small enough to attach to a chat (budget below the 30 MB attachment limit).
 * Files go in by priority: summaries first, then small CSVs, then the per-frame logs, then a few
 * evidence images. Whatever does not fit is listed in MANIFEST.txt instead of silently disappearing.
 */
object SendBundle {
    private const val BUDGET = 25L * 1024 * 1024

    private fun priority(f: File): Int = when {
        f.name.endsWith(".txt") -> 0
        f.name in setOf("events.csv", "seconds.csv", "truth.csv", "touch_probe.csv", "ingame_tap.csv") -> 1
        f.name == "display.csv" -> 2
        f.name == "frames.csv" -> 3
        f.name == "touch_events.csv" -> 4
        f.name.endsWith(".jpg") -> 5
        f.name.endsWith(".png") -> 6
        else -> 7
    }

    /** Returns a human-readable result line. Runs on a background thread. */
    fun build(ctx: Context): String {
        val root = ctx.getExternalFilesDir("sessions") ?: return "세션 폴더가 없습니다."
        val files = root.walkTopDown().filter { it.isFile }.toMutableList()
        listOf(File(ctx.filesDir, "last_crash.txt"), File(ctx.filesDir, "last_crash_seen.txt")).filter { it.isFile }.forEach { files.add(it) }
        if (files.isEmpty()) return "보낼 결과가 없습니다. 먼저 시험을 하나 실행하세요."
        // Newest sessions first inside each priority; at most 12 evidence images overall.
        val ordered = files.sortedWith(compareBy<File>({ priority(it) }, { -(it.parentFile?.lastModified() ?: 0L) }, { it.path }))
        var images = 0
        val included = ArrayList<String>()
        val skipped = ArrayList<String>()
        val zipBytes = ByteArrayOutputStream()
        var used = 0L
        ZipOutputStream(zipBytes).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            for (f in ordered) {
                val rel = if (f.startsWith(root)) f.relativeTo(root).path else "app/${f.name}"
                if (f.name.endsWith(".png") && images >= 12) { skipped.add("$rel (증거 이미지 12장 초과)"); continue }
                val raw = f.readBytes()
                val est = compressedSize(raw, f.name.endsWith(".png"))
                if (used + est + 4096 > BUDGET) { skipped.add("$rel (용량 초과, ${raw.size / 1024} KB)"); continue }
                zip.putNextEntry(ZipEntry(rel))
                zip.write(raw)
                zip.closeEntry()
                used += est + 200
                included.add(rel)
                if (f.name.endsWith(".png")) images++
            }
            zip.putNextEntry(ZipEntry("MANIFEST.txt"))
            zip.write(buildString {
                appendLine("DunkBlock 전송용 묶음")
                appendLine(DeviceInfo.short(ctx))
                appendLine("포함 ${included.size}개:")
                included.forEach { appendLine("  $it") }
                if (skipped.isNotEmpty()) {
                    appendLine("용량 때문에 뺀 파일 ${skipped.size}개 (폰의 Download/DunkBlock 에는 남아 있음):")
                    skipped.forEach { appendLine("  $it") }
                }
            }.toByteArray())
            zip.closeEntry()
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val name = "DunkBlock_send_$stamp.zip"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DunkBlock")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "전송용 묶음 저장 실패"
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(zipBytes.toByteArray()) }
        val mb = zipBytes.size() / (1024.0 * 1024.0)
        return String.format(Locale.ROOT, "전송용 묶음 완료: Download/DunkBlock/%s (%.1f MB, 파일 %d개%s)", name, mb, included.size,
            if (skipped.isNotEmpty()) ", 용량 때문에 ${skipped.size}개 제외 — MANIFEST.txt 참고" else "")
    }

    private fun compressedSize(raw: ByteArray, alreadyCompressed: Boolean): Long {
        if (alreadyCompressed) return raw.size.toLong()
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(raw); d.finish()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (!d.finished()) total += d.deflate(buf)
        d.end()
        return total
    }
}
