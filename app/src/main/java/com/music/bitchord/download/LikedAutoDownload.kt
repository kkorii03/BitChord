package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Downloads a song the moment it is liked.
 *
 * Goes through [Downloads.enqueue] like any other download, so the Wi-Fi-only
 * setting, the duplicate checks and the download manager all behave the same.
 * The one difference is what happens while downloads are not allowed — on
 * mobile data with Wi-Fi only set, or offline: rather than adding a failed row
 * for every heart, the id is parked here and downloaded once a connection that
 * allows it comes back. Parked ids are kept on disk, so a restart loses none.
 *
 * Un-liking a song takes it off the parked list but leaves a finished download
 * alone: deleting a file the user may have reasons to keep is not this
 * feature's call.
 */
internal object LikedAutoDownload {
    private const val TAG = "BitChord"
    private const val KEY_PENDING = "liked_download_pending"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainLock = Mutex()
    private val lock = Any()
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        scope.launch {
            combine(
                AppSettings.meteredConnection,
                AppSettings.wifiOnlyDownloads,
                AppSettings.autoDownloadLiked,
            ) { _, _, enabled -> enabled && allowedNow() }
                .distinctUntilChanged()
                .collect { ready -> if (ready) drain() }
        }
    }

    private val ready: Boolean get() = this::app.isInitialized

    /** Online, and on a connection downloads may use. */
    private fun allowedNow(): Boolean =
        AppSettings.meteredConnection.value != null && AppSettings.downloadsAllowedNow

    /** A like that YouTube accepted. [known] is the row it was liked from, when there was one. */
    fun onLiked(videoId: String, known: Song? = null) {
        if (!ready || videoId.isBlank() || !AppSettings.autoDownloadLiked.value) return
        if (alreadyHandled(videoId)) return
        if (!allowedNow()) {
            park(videoId)
            return
        }
        scope.launch { download(videoId, known, parkOnFailure = true) }
    }

    /** A like taken back: no longer worth downloading later. */
    fun onUnliked(videoId: String) {
        if (!ready) return
        unpark(videoId)
    }

    private fun alreadyHandled(videoId: String): Boolean {
        if (Downloads.saved.value.containsKey(videoId)) return true
        val state = Downloads.active.value[videoId]
        return state is DownloadState.Queued || state is DownloadState.Running
    }

    /**
     * [parkOnFailure]: keep the id for the next connection when its details
     * can't be had now. Off when draining, so an id that never resolves (a
     * removed track) is dropped rather than retried for ever.
     */
    private suspend fun download(videoId: String, known: Song?, parkOnFailure: Boolean) {
        if (alreadyHandled(videoId)) {
            unpark(videoId)
            return
        }
        val song = known?.takeIf { it.videoId == videoId && it.title.isNotBlank() }
            ?: try {
                YtMusicRepository.trackLinks(videoId).getOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.d(TAG, "liked download: no details for $videoId: ${e.message}")
                null
            }
        if (song == null) {
            if (parkOnFailure) park(videoId) else unpark(videoId)
            return
        }
        withContext(Dispatchers.Main) { Downloads.enqueue(app, song) }
        unpark(videoId)
    }

    private suspend fun drain() = drainLock.withLock {
        for (videoId in parked()) {
            if (!AppSettings.autoDownloadLiked.value || !allowedNow()) return@withLock
            // A miss stays parked for the next time downloads are allowed,
            // rather than dropping a liked song for one failed request.
            download(videoId, null, parkOnFailure = true)
        }
    }

    private fun parked(): List<String> = synchronized(lock) {
        prefs.getString(KEY_PENDING, null).orEmpty().split('\n').filter { it.isNotBlank() }
    }

    private fun park(videoId: String) {
        synchronized(lock) {
            val ids = parked()
            if (videoId !in ids) {
                prefs.edit().putString(KEY_PENDING, (ids + videoId).joinToString("\n")).apply()
            }
        }
    }

    private fun unpark(videoId: String) {
        synchronized(lock) {
            val ids = parked()
            if (videoId in ids) {
                prefs.edit().putString(KEY_PENDING, (ids - videoId).joinToString("\n")).apply()
            }
        }
    }
}
