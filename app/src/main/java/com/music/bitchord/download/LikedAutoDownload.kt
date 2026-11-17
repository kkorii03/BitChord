package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.startup.AppForeground
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

    /** [KO] RG3-4: "id:count" lines, failed downloads per parked like. */
    private const val KEY_FAILURES = "liked_download_failures"

    /** [KO] RG3-4: a parked like leaves the list after this many failed downloads, not one blip. */
    internal const val MAX_FAILURES = 3

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
        // A like handed to the queue while the app had no window stays parked
        // (see [settle]); it leaves the list once its file is saved. Event
        // driven: runs only when the saved records change.
        scope.launch {
            Downloads.saved.collect { saved -> unparkSaved(saved.keys) }
        }
        // [KO] RG3-4: a parked like whose download failed leaves the list too,
        // or every process start queued it again and posted another failure
        // row, for ever. Event driven: only when the queue's states change.
        scope.launch {
            var before: Map<String, DownloadState> = emptyMap()
            Downloads.active.collect { now ->
                unparkFailed(newlyFailed(before, now))
                before = now
            }
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
        Downloads.ensureOpen()
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
            settle(videoId)
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
        settle(videoId)
    }

    /**
     * After [videoId] was handed to the queue: off the on-disk list only when
     * this process will really download it. Without a window the queue does
     * not start (KoDownloadQueue.startAfterEnqueue), the id lives only in
     * memory, and process death would lose the like for good.
     */
    private fun settle(videoId: String) {
        val saved = Downloads.saved.value.containsKey(videoId)
        if (keepParked(saved = saved, foreground = AppForeground.isForeground)) park(videoId) else unpark(videoId)
    }

    /** Whether a like handed to the queue must stay on the on-disk list. */
    internal fun keepParked(saved: Boolean, foreground: Boolean): Boolean = !saved && !foreground

    /** [parked] without the ids whose file is now saved. */
    internal fun withoutSaved(parked: List<String>, saved: Set<String>): List<String> =
        parked.filterNot { it in saved }

    private fun unparkSaved(saved: Set<String>) {
        if (!this::prefs.isInitialized) return
        synchronized(lock) {
            val ids = parked()
            val left = withoutSaved(ids, saved)
            if (left.size != ids.size) {
                prefs.edit().putString(KEY_PENDING, left.joinToString("\n")).apply()
            }
            forgetFailures(saved) // [KO] RG3-4
        }
    }

    /**
     * [KO] RG3-4: ids whose download failed in this emission. Compared by
     * identity, so a failure is acted on once, not again whenever another
     * id's state changes. A refusal that never tried (Wi-Fi only, the service
     * not allowed to start) is not a failure of the song and keeps it parked.
     */
    internal fun newlyFailed(before: Map<String, DownloadState>, now: Map<String, DownloadState>): Set<String> =
        now.filter { (id, state) ->
            state is DownloadState.Failed &&
                state !== before[id] &&
                !com.music.bitchord.ko.downloads.KoDownloadQueue.keepsVerifiedPairAfterRefusal(state.reason)
        }.keys

    /**
     * [KO] RG3-4: counts one more failure for each parked id in [failed]; an id
     * that reached [MAX_FAILURES] leaves the list. A brief network blip keeps
     * the like for the next connection.
     */
    private fun unparkFailed(failed: Set<String>) {
        if (failed.isEmpty() || !this::prefs.isInitialized) return
        synchronized(lock) {
            val ids = parked()
            val hit = failed.filter { it in ids }
            if (hit.isEmpty()) return
            val counts = failures().toMutableMap()
            hit.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            val dropped = hit.filter { (counts[it] ?: 0) >= MAX_FAILURES }.toSet()
            dropped.forEach { counts.remove(it) }
            val left = ids.filterNot { it in dropped }
            val edit = prefs.edit().putString(KEY_FAILURES, encodeFailures(counts.filterKeys { it in left }))
            if (dropped.isNotEmpty()) {
                Log.d(TAG, "liked download: failed $MAX_FAILURES times, no longer parked: ${dropped.joinToString()}")
                edit.putString(KEY_PENDING, left.joinToString("\n"))
            } else {
                Log.d(TAG, "liked download: failed, still parked: ${hit.joinToString { "$it (${counts[it]})" }}")
            }
            edit.apply()
        }
    }

    /** [KO] RG3-4: failure counts per parked id; a missing or damaged value reads as none. */
    private fun failures(): Map<String, Int> = synchronized(lock) {
        decodeFailures(prefs.getString(KEY_FAILURES, null))
    }

    internal fun decodeFailures(raw: String?): Map<String, Int> =
        raw.orEmpty().split('\n').mapNotNull { line ->
            val cut = line.lastIndexOf(':')
            if (cut <= 0) return@mapNotNull null
            val count = line.substring(cut + 1).toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, cut) to count
        }.toMap()

    internal fun encodeFailures(counts: Map<String, Int>): String =
        counts.entries.joinToString("\n") { "${it.key}:${it.value}" }

    /** [KO] RG3-4: drops the failure counts of [ids] (saved, unliked or unparked). */
    private fun forgetFailures(ids: Collection<String>) {
        synchronized(lock) {
            val counts = failures()
            if (counts.keys.none { it in ids }) return
            prefs.edit().putString(KEY_FAILURES, encodeFailures(counts.filterKeys { it !in ids })).apply()
        }
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
            forgetFailures(listOf(videoId)) // [KO] RG3-4
        }
    }
}
