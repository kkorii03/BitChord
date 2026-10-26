package com.music.bitchord.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.SettingsTransfer
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Dev-debug only: applies a settings file without the Settings preview.
 *
 * This is the hook an emulator smoke test needs. It is registered solely by
 * `src/devDebug/AndroidManifest.xml`, so it exists in the dev debug APK and in
 * neither release nor the prod debug build. It goes through the same
 * [SettingsTransfer] decode/preview/apply path as the UI, so a file that would
 * be refused there is refused here too.
 *
 * `path` may be an absolute path the app can read, or a name under the app's
 * cache directory — the latter is how `scripts/smoke/import-settings.sh` hands
 * over a file from `/data/local/tmp` when the app cannot read that directory
 * directly.
 */
class DebugSettingsImportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_IMPORT_SETTINGS) return
        val requested = intent.getStringExtra(EXTRA_PATH)
        if (requested.isNullOrBlank()) {
            TrackLog.w(TAG, "debug-import: missing path")
            return
        }
        val file = resolve(context, requested)
        if (file == null) {
            TrackLog.w(TAG, "debug-import: unreadable path=$requested")
            return
        }
        val result = runCatching {
            require(file.length() <= MAX_BYTES) { "settings file too large" }
            runBlocking {
                val parsed = SettingsTransfer.decode(file.readText()).getOrThrow()
                SettingsTransfer.apply(SettingsTransfer.preview(parsed)).getOrThrow()
            }
        }
        result.fold(
            onSuccess = { summary ->
                TrackLog.i(
                    TAG,
                    "debug-import: applied=${summary.settings} " +
                        "skipped=${summary.skipped} sources=${summary.sources}",
                )
            },
            onFailure = { error ->
                TrackLog.w(TAG, "debug-import: failed=${error.message}")
            },
        )
    }

    private fun resolve(context: Context, path: String): File? {
        val requested = File(path)
        if (requested.isAbsolute) {
            if (requested.isFile && requested.canRead()) return requested
            return File(context.cacheDir, requested.name).takeIf { it.isFile && it.canRead() }
        }
        return File(context.cacheDir, path).takeIf { it.isFile && it.canRead() }
    }

    companion object {
        const val ACTION_IMPORT_SETTINGS = "com.music.bitchord.debug.IMPORT_SETTINGS"
        const val EXTRA_PATH = "path"

        private const val TAG = "BitChord"
        private const val MAX_BYTES = 1024L * 1024L
    }
}
