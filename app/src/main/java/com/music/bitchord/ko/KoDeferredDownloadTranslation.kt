package com.music.bitchord.ko

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.TranslationAhead
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [KO] RG3-5 deferred download translation
 *
 * A download finished during power saving skips its kept translation (review
 * X8: a playlist download sent one request per song). Those ids are remembered
 * here, on disk, and translated later on two events only, never a timer:
 * power saving ending (the app's existing low-power listener) and the song
 * being played again while heavy work is allowed. An id leaves the list once
 * its translation was answered, its file has no lyrics, or the file is gone.
 */
internal object KoDeferredDownloadTranslation {
    private const val TAG = "BitChord"
    internal const val KEY = "ko_deferred_download_translation"

    /** Enough for several playlists; the oldest go first past it. */
    private const val MAX_IDS = 1000

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catchingUp = AtomicBoolean(false)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)

    private fun read(prefs: SharedPreferences): List<String> =
        prefs.getString(KEY, null).orEmpty().split('\n').filter { it.isNotBlank() }

    /** The ids still waiting for their kept translation, oldest first. */
    fun deferred(context: Context): List<String> = synchronized(lock) { read(prefs(context)) }

    /** [ids] with [videoId] added once, capped at [cap]. */
    internal fun withDeferred(ids: List<String>, videoId: String, cap: Int = MAX_IDS): List<String> =
        if (videoId in ids) ids else (ids + videoId).takeLast(cap)

    /** A download skipped its kept translation for power saving. */
    fun defer(context: Context, videoId: String) {
        if (videoId.isBlank()) return
        synchronized(lock) {
            val prefs = prefs(context)
            val ids = read(prefs)
            val next = withDeferred(ids, videoId)
            if (next != ids) prefs.edit().putString(KEY, next.joinToString("\n")).apply()
        }
    }

    private fun remove(context: Context, videoId: String) {
        synchronized(lock) {
            val prefs = prefs(context)
            val ids = read(prefs)
            if (videoId in ids) prefs.edit().putString(KEY, (ids - videoId).joinToString("\n")).apply()
        }
    }

    private fun wanted(): Boolean =
        AppSettings.heavyWorkAllowed && AppSettings.keepDownloadTranslation.value

    /** Power saving just ended: translate what it held back, one at a time. */
    fun onPowerSaveEnded(context: Context) {
        if (!wanted()) return
        val app = context.applicationContext
        if (!catchingUp.compareAndSet(false, true)) return
        scope.launch {
            try {
                val ids = deferred(app)
                if (ids.isEmpty()) return@launch
                Log.d(TAG, "translation-ahead: catching up ${ids.size} download(s) after power saving")
                for (id in ids) {
                    // Power saving back on stops the pass; the rest wait for the next end.
                    if (!wanted()) break
                    catchUp(app, id)
                }
            } finally {
                catchingUp.set(false)
            }
        }
    }

    /** A song started playing; a deferred download gets its kept translation now. */
    fun onPlayed(context: Context, videoId: String) {
        if (!wanted() || videoId.isBlank()) return
        val app = context.applicationContext
        scope.launch {
            if (videoId in deferred(app)) catchUp(app, videoId)
        }
    }

    private suspend fun catchUp(app: Context, videoId: String) {
        Downloads.ensureOpen()
        val uri = Downloads.saved.value[videoId]
        if (uri == null) {
            remove(app, videoId)
            return
        }
        if (TranslationAhead.keepFromFile(app, videoId, uri)) remove(app, videoId)
    }
}
