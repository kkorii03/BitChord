package dev.dunkblock.bench

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Copies a session folder to Download/DunkBlock/<session>/ through MediaStore (no storage permission needed),
 * so the files are reachable from the Samsung "My Files" app or a PC over USB. The originals stay in the
 * app-specific folder (Android/data/dev.dunkblock.bench/files/sessions/) for `adb pull`.
 */
object Exporter {
    fun copyToDownloads(ctx: Context, dir: File): String {
        val rel = "${Environment.DIRECTORY_DOWNLOADS}/DunkBlock/${dir.name}"
        var ok = 0
        var fail = 0
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val sub = f.parentFile?.relativeTo(dir)?.path?.takeIf { it.isNotEmpty() }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(f.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (sub != null) "$rel/$sub" else rel)
            }
            try {
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert returned null")
                ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                ok++
            } catch (t: Throwable) {
                Log.e("DunkBlockExport", "export ${f.name}", t)
                fail++
            }
        }
        return "내장저장소/$rel (파일 $ok 개 복사${if (fail > 0) ", 실패 $fail" else ""})\n원본: ${dir.absolutePath}"
    }

    private fun mime(name: String) = when {
        name.endsWith(".csv") -> "text/csv"
        name.endsWith(".png") -> "image/png"
        else -> "text/plain"
    }
}
