package dev.dunkblock.bench

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Copies a session folder's text and CSV files to Download/DunkBlock/<session>/ through MediaStore (no storage
 * permission needed), so they are reachable from the Samsung "My Files" app or a PC over USB. The originals stay in
 * the app-specific folder (Android/data/dev.dunkblock.bench/files/sessions/) for `adb pull` and the send bundle.
 *
 * vc1138: images (.jpg/.png) are no longer copied. A match session holds 2,000+ clip frames and every MediaStore
 * insert costs tens of ms, so '결과 저장 중' took minutes (11-12 user: "저장 너무 느려"). The send bundle reads the
 * images from the app folder, so nothing is lost.
 */
object Exporter {
    fun copyToDownloads(ctx: Context, dir: File): String {
        val rel = "${Environment.DIRECTORY_DOWNLOADS}/DunkBlock/${dir.name}"
        var ok = 0
        var fail = 0
        var images = 0
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            if (f.name.endsWith(".jpg") || f.name.endsWith(".png")) { images++; return@forEach }
            val sub = f.parentFile?.relativeTo(dir)?.path?.takeIf { it.isNotEmpty() }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(f.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (sub != null) "$rel/$sub" else rel)
            }
            try {
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert returned null")
                ctx.contentResolver.openOutputStream(uri)!!.buffered(1 shl 16).use { out -> f.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ok++
            } catch (t: Throwable) {
                Log.e("DunkBlockExport", "export ${f.name}", t)
                fail++
            }
        }
        return "내장저장소/$rel (파일 $ok 개 복사${if (fail > 0) ", 실패 $fail" else ""}" +
            "${if (images > 0) ", 사진 $images 장은 앱 폴더에만 두고 전송용 묶음에 넣음" else ""})\n원본: ${dir.absolutePath}"
    }

    private fun mime(name: String) = when {
        name.endsWith(".csv") -> "text/csv"
        else -> "text/plain"
    }
}
