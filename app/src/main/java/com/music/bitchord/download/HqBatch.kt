package com.music.bitchord.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigins
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.playback.smart.HqVerificationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The Downloads page's "download everything again in higher quality" batch.
 *
 * It is deliberately a small scheduler on top of the ordinary [Downloads]
 * queue, not a second download engine. One candidate is put into that queue at
 * a time, so the existing rate limiting, Wi-Fi rule, foreground service and
 * notification all still apply. The only new state is the list of tracks still
 * to fetch, which is written down so a process death or a lost connection does
 * not lose the batch.
 *
 * A failed track stays in the list and is tried again on the next app start or
 * when a network becomes available, while the rest of the batch carries on. A
 * full disk is different: it stops the whole batch and says so, because every
 * remaining track would fail the same way.
 */
object HqBatch {

    private const val TAG = "BitChord"
    private const val PREFS = "hq_batch"
    private const val KEY_PENDING = "pending"
    private const val KEY_TOTAL = "total"
    private const val WORK_NAME = "hq-batch-upgrade"
    private const val CHANNEL_ID = "hq_batch"
    private const val NOTIFICATION_ID = 0x8176

    @Serializable
    private data class Entry(
        val quality: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val lock = Any()

    /** Tracks still to fetch, in the order the dialog listed them. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, Entry>()

    /** The song snapshot for each pending id, rebuilt from disk after a restart. */
    private val songs = HashMap<String, Song>()

    /** Failed at least once this run, so [pump] moves on instead of retrying it at once. */
    private val settledThisRun = HashSet<String>()

    /** The one id currently in [Downloads]' queue. Guarded by [lock]. */
    private var inFlight: String? = null

    private var total = 0
    private var scheduled = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class Candidate(val song: Song, val quality: DownloadQuality)

    data class Plan(
        val candidates: List<Candidate>,
        val alreadyBest: Int,
        val none: Int,
    ) {
        val total: Int get() = candidates.size + alreadyBest + none
    }

    data class State(
        val total: Int = 0,
        val completed: Int = 0,
        val pending: Int = 0,
        val failed: Int = 0,
        val running: Boolean = false,
        val waitingForNetwork: Boolean = false,
    ) {
        val active: Boolean get() = pending > 0
    }

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()

        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        background.launch {
            Downloads.active.collect { pump() }
        }
        background.launch {
            AppSettings.meteredConnection
                .distinctUntilChanged()
                .collect { metered ->
                    if (metered != null && pendingCount() > 0) {
                        resume(app)
                    }
                }
        }
        scheduleIfPending(app)
    }

    /**
     * What the confirmation dialog shows for [songs], without queueing anything.
     *
     * The three counts are the policy's own: a track is available when a
     * verified better rung exists or the install has a source ranked above
     * YouTube, already best when the ladder says so, and none when neither is
     * true. No network request is made here.
     */
    fun plan(context: Context, songs: List<Song>): Plan {
        Downloads.ensureOpen()
        val hasHigherSource = SourceResolver.canSubstituteForDownload()
        val candidates = ArrayList<Candidate>()
        var alreadyBest = 0
        var none = 0
        songs.distinctBy { it.videoId }.forEach { song ->
            if (!SongOrigins.hasYouTubeIdentity(song.videoId)) {
                none++
                return@forEach
            }
            val verified = HqVerificationStore.bestTier(context, song.videoId)
            when (HqBatchPolicy.category(song.downloadFormat, verified, hasHigherSource)) {
                HqBatchPolicy.Category.AVAILABLE -> {
                    val quality = HqBatchPolicy.qualityFor(song.downloadFormat, verified, hasHigherSource)
                    if (quality != null) candidates += Candidate(song, quality) else none++
                }
                HqBatchPolicy.Category.ALREADY_BEST -> alreadyBest++
                HqBatchPolicy.Category.NONE -> none++
            }
        }
        return Plan(candidates, alreadyBest, none)
    }

    /** Replace any batch already in progress with [plan]'s candidates and start it. */
    fun start(context: Context, plan: Plan) {
        if (plan.candidates.isEmpty()) return
        val app = context.applicationContext
        if (appContext == null) init(app)
        val previous = synchronized(lock) {
            pending.keys.toList().also {
                pending.clear()
                songs.clear()
                settledThisRun.clear()
                inFlight = null
                total = plan.candidates.size
                plan.candidates.forEach { candidate ->
                    pending[candidate.song.videoId] = Entry(candidate.quality.name)
                    songs[candidate.song.videoId] = candidate.song
                }
            }
        }
        previous.forEach(Downloads::cancel)
        persist()
        publish()
        if (canRunNow()) {
            pump()
        } else {
            schedule(app)
        }
    }

    /**
     * Put the next pending track into the ordinary download queue.
     *
     * Called when a download settles, when the app opens and when a network
     * arrives. It never runs two batch tracks at once; other downloads are
     * free to use the remaining workers.
     */
    private fun pump() {
        val app = appContext ?: return
        if (pendingCount() == 0) return
        if (!canRunNow()) {
            publish()
            schedule(app)
            return
        }
        val picked = synchronized(lock) {
            if (inFlight != null || pending.isEmpty() || songs.isEmpty()) return@synchronized null
            val active = Downloads.active.value
            if (pending.keys.any { id ->
                    active[id] is DownloadState.Queued || active[id] is DownloadState.Running
                }
            ) {
                return@synchronized null
            }
            val next = pending.entries.firstOrNull { it.key !in settledThisRun }
                ?: return@synchronized null
            inFlight = next.key
            next.key to next.value
        } ?: run {
            publish()
            return
        }
        val song = synchronized(lock) { songs[picked.first] }
        val quality = runCatching { DownloadQuality.valueOf(picked.second.quality) }.getOrNull()
        if (song == null || quality == null) {
            synchronized(lock) {
                if (inFlight == picked.first) inFlight = null
                pending.remove(picked.first)
                songs.remove(picked.first)
            }
            persist()
            publish()
            pump()
            return
        }
        publish()
        if (!Downloads.enqueue(app, song, quality = quality)) {
            // The track was already in flight (a normal download of the same
            // song, say). Release the claim; the active-state collector will
            // ask again when that download settles.
            synchronized(lock) {
                if (inFlight == picked.first) inFlight = null
            }
            publish()
        }
    }

    /**
     * Rebuild the in-memory half of a persisted batch from what is on disk.
     *
     * Called by the startup path, the network worker and the page's retry
     * action. A track whose file has disappeared is dropped rather than
     * queued again: there is nothing left for the replacement to replace.
     */
    suspend fun resume(context: Context) {
        val app = context.applicationContext
        if (appContext == null) init(app)
        scheduled = false
        if (pendingCount() == 0) {
            cancelWork(app)
            return
        }
        // A network change while one of the batch's tracks is already running
        // must not reload and forget that claim; onDone/onFailed will move the
        // batch on when it settles.
        if (synchronized(lock) { inFlight != null }) {
            publish()
            return
        }
        if (!canRunNow()) {
            publish()
            schedule(app)
            return
        }
        val downloaded = Downloads.getDownloadedSongs(app).associateBy { it.videoId }
        val hasHigherSource = SourceResolver.canSubstituteForDownload()
        synchronized(lock) {
            // Two resumes can pass the cheap check above while the disk read is
            // in flight. Only the first one to get here may clear the claim.
            if (inFlight != null) return
            settledThisRun.clear()
            // A process can die between committing a replacement and writing
            // the batch down. Re-reading the files drops anything that is now
            // already at the best rung, so the interrupted track is not fetched
            // a second time for nothing.
            val missing = pending.keys.filter { id ->
                val song = downloaded[id] ?: return@filter true
                val verified = HqVerificationStore.bestTier(app, id)
                HqBatchPolicy.category(song.downloadFormat, verified, hasHigherSource) !=
                    HqBatchPolicy.Category.AVAILABLE
            }
            missing.forEach {
                pending.remove(it)
                songs.remove(it)
                Downloads.forgetQualityOverride(it)
            }
            pending.keys.forEach { id -> downloaded[id]?.let { songs[id] = it } }
            if (missing.isNotEmpty()) {
                total = (total - missing.size).coerceAtLeast(pending.size)
            }
        }
        persist()
        publish()
        if (pendingCount() == 0) {
            synchronized(lock) { total = 0 }
            persist()
            publish()
            cancelWork(app)
            return
        }
        pump()
    }

    /** Drop the whole batch, including a track that is already running. */
    fun cancel(context: Context) {
        val ids = synchronized(lock) {
            pending.keys.toList().also {
                pending.clear()
                songs.clear()
                settledThisRun.clear()
                inFlight = null
                total = 0
            }
        }
        persist()
        publish()
        cancelWork(context.applicationContext)
        ids.forEach(Downloads::cancel)
    }

    internal fun onDone(videoId: String) {
        val app = appContext ?: return
        var finished = false
        synchronized(lock) {
            // Only the download this batch put in the queue may settle the
            // batch's own entry. A normal download of the same song, started
            // before or beside it, is not the high-quality fetch.
            if (inFlight != videoId) return
            if (pending.remove(videoId) == null) return
            songs.remove(videoId)
            settledThisRun.remove(videoId)
            inFlight = null
            if (pending.isEmpty()) {
                total = 0
                finished = true
            }
        }
        persist()
        publish()
        if (finished) cancelWork(app) else pump()
    }

    /**
     * A batch track failed. Keep it for a later attempt, but move past it for
     * the rest of this run so one unavailable track cannot hold up the queue.
     */
    internal fun onFailed(videoId: String) {
        val app = appContext ?: return
        var retryOnNetwork = false
        synchronized(lock) {
            if (inFlight != videoId) return
            if (videoId !in pending) return
            inFlight = null
            val entry = pending.remove(videoId) ?: return
            settledThisRun.add(videoId)
            // Offline or held back by the Wi-Fi rule: not the track's fault,
            // and the whole batch should wait rather than march through the
            // rest of the list against a connection that is not there.
            retryOnNetwork =
                AppSettings.meteredConnection.value == null || !AppSettings.downloadsAllowedNow
            pending[videoId] = entry
        }
        persist()
        publish()
        if (retryOnNetwork) {
            schedule(app)
        } else {
            pump()
        }
    }

    /** The user cancelled one row; the rest of the batch carries on. */
    internal fun onCancelled(videoId: String) {
        var removed = false
        synchronized(lock) {
            if (inFlight != videoId) return
            if (pending.remove(videoId) != null) {
                songs.remove(videoId)
                settledThisRun.remove(videoId)
                inFlight = null
                removed = true
            }
            if (pending.isEmpty()) total = 0
        }
        if (!removed) return
        persist()
        publish()
        pump()
    }

    /** The foreground service is gone; a later start will pick the batch up. */
    internal fun onServiceStopped() {
        synchronized(lock) {
            inFlight = null
            settledThisRun.clear()
        }
        publish()
    }

    /**
     * The disk filled up. Every remaining track would fail the same way, so the
     * batch stops and the notification says why.
     */
    internal fun onOutOfSpace(failingId: String? = null) {
        val app = appContext ?: return
        val (hadPending, toCancel, toForget) = synchronized(lock) {
            val had = pending.isNotEmpty()
            val ids = pending.keys.filter { it != failingId }
            val all = pending.keys.toList()
            pending.clear()
            songs.clear()
            settledThisRun.clear()
            inFlight = null
            total = 0
            Triple(had, ids, all)
        }
        persist()
        publish()
        cancelWork(app)
        toCancel.forEach(Downloads::cancel)
        toForget.forEach(Downloads::forgetQualityOverride)
        if (hadPending) notifyOutOfSpace(app)
    }

    fun scheduleIfPending(context: Context) {
        if (pendingCount() == 0) return
        schedule(context.applicationContext)
    }

    /** Whether [videoId] is one of the tracks this batch still has to fetch. */
    fun isPending(videoId: String): Boolean = synchronized(lock) { videoId in pending }

    /**
     * Let a failed [videoId] have another turn in the current run.
     *
     * The manager's retry action uses this rather than [resume]: the batch may
     * already be running another track, and the retried one should be picked up
     * as soon as that one settles, not only after a restart.
     */
    fun retry(context: Context, videoId: String) {
        val app = context.applicationContext
        if (appContext == null) init(app)
        synchronized(lock) {
            if (videoId !in pending) return
            settledThisRun.remove(videoId)
        }
        publish()
        if (synchronized(lock) { inFlight == null }) {
            if (canRunNow()) pump() else schedule(app)
        }
    }

    private fun schedule(context: Context) {
        if (scheduled) return
        scheduled = true
        val network = if (AppSettings.wifiOnlyDownloads.value) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<HqBatchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    .build(),
            )
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }.onFailure {
            scheduled = false
            Log.w(TAG, "could not schedule the high-quality batch: ${it.message}")
        }
    }

    private fun cancelWork(context: Context) {
        scheduled = false
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }.onFailure {
            Log.w(TAG, "could not cancel the high-quality batch: ${it.message}")
        }
    }

    private fun canRunNow(): Boolean =
        AppSettings.meteredConnection.value != null && AppSettings.downloadsAllowedNow

    private fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun load() {
        val stored = prefs?.getString(KEY_PENDING, null)?.let { raw ->
            runCatching { json.decodeFromString(serializer, raw) }
                .getOrDefault(emptyMap<String, Entry>())
        }.orEmpty()
        synchronized(lock) {
            pending.clear()
            pending.putAll(stored)
            songs.clear()
            settledThisRun.clear()
            inFlight = null
            total = prefs?.getInt(KEY_TOTAL, pending.size)?.coerceAtLeast(pending.size)
                ?: pending.size
            if (pending.isEmpty()) total = 0
        }
        publish()
    }

    private fun persist() {
        val stored = prefs ?: return
        val snapshot = synchronized(lock) {
            pending.toMap() to total
        }
        stored.edit()
            .putString(KEY_PENDING, json.encodeToString(serializer, snapshot.first))
            .putInt(KEY_TOTAL, snapshot.second)
            .apply()
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            State(
                total = total,
                completed = (total - pending.size).coerceAtLeast(0),
                pending = pending.size,
                failed = settledThisRun.size,
                running = inFlight != null,
                waitingForNetwork = pending.isNotEmpty() && !canRunNow(),
            )
        }
        if (snapshot != _state.value) _state.value = snapshot
    }

    private fun notifyOutOfSpace(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.hq_batch_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val text = context.getString(R.string.hq_batch_out_of_space_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(context.getString(R.string.hq_batch_out_of_space_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Log.w(TAG, "could not show the out-of-space notice: ${it.message}")
        }
    }
}

class HqBatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        HqBatch.resume(applicationContext)
        return Result.success()
    }
}
