package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.HqLibraryAction
import com.music.bitchord.data.sources.HqLibraryEntry
import com.music.bitchord.data.sources.HqLibraryPolicy
import com.music.bitchord.data.sources.HqLibraryTarget
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.TrackEntry
import com.music.bitchord.playback.smart.HqVerificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The automatic high-quality pass over the listener's library.
 *
 * Two jobs, both fed by [HqVerificationStore]'s accepted pairs:
 *
 *  - Replace a downloaded file when a verified copy sits on a higher rung,
 *    even when the file is already high quality.
 *  - Pre-save a verified high-quality copy of songs that are liked or often
 *    played, up to the setting's cap.
 *
 * One target at a time, through the ordinary [Downloads] queue. The worker
 * enqueues exactly one replacement and returns; [onDownloadSettled] — called
 * from the download itself — records the outcome and appends the next worker.
 * That is what keeps the pass sequential while still surviving a process
 * death: the remaining plan is on disk, and the next app start or playback
 * resumes it. There is no timer and no periodic poll.
 *
 * The old file is never deleted first. A replacement is written under its own
 * pending destination and committed only after the bytes and tags are complete;
 * [Downloads.remember] then drops the previous file once nothing else names it.
 */
object HqLibraryUpgrade {

    private const val WORK_NAME = "hq-library-upgrade"
    private const val TAG = "BitChord"
    private const val PREFS = "hq_library"

    private const val KEY_PLAN = "plan"
    private const val KEY_DONE = "done"
    private const val KEY_TOTAL = "total"
    private const val KEY_ACTIVE = "active"

    /** How much history is read when ranking prefetch targets. */
    private const val HISTORY_LIMIT = 1_000

    /**
     * Playback asks on every track. The queue is unique-work with KEEP, so a
     * second request while one is pending is a no-op; this only avoids the
     * WorkManager round trip several times a minute.
     */
    private const val SCHEDULE_DEBOUNCE_MS = 15_000L

    /** What the Downloads screen shows above the list. */
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
    ) {
        val visible: Boolean get() = running && total > 0
    }

    private data class PendingTarget(
        val target: HqLibraryTarget,
        val song: Song,
    )

    private val lock = Any()
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private lateinit var prefs: SharedPreferences
    private var plan: List<PendingTarget> = emptyList()
    private var done: Int = 0
    private var total: Int = 0
    private var activeVideoId: String? = null

    /** A new verdict arrived while a replacement was in flight; rebuild after it. */
    private var rebuildRequested: Boolean = false

    @Volatile
    private var lastScheduleRequestMs: Long = 0L

    private val ready: Boolean get() = this::prefs.isInitialized

    fun enabled(): Boolean =
        AppSettings.autoReplaceHqDownloads.value || AppSettings.autoPrefetchHq.value

    /** Called at app start and on playback; safe to call repeatedly. */
    fun scheduleIfEnabled(context: Context) {
        ensureLoaded(context)
        if (!enabled()) return
        val now = System.currentTimeMillis()
        if (now - lastScheduleRequestMs < SCHEDULE_DEBOUNCE_MS) return
        lastScheduleRequestMs = now
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    /**
     * A new sound comparison was accepted.
     *
     * A higher rung may have just been proved for a song already in the plan,
     * or for one the current plan predates. Rebuilding is what makes the new
     * verdict take effect without waiting for a 300-track pass to end; an
     * in-flight replacement is allowed to finish first.
     */
    fun onVerified(context: Context) {
        ensureLoaded(context)
        if (!enabled()) return
        synchronized(lock) {
            rebuildRequested = activeVideoId != null
            plan = emptyList()
            done = 0
            total = 0
            persistLocked()
            publishLocked()
        }
        lastScheduleRequestMs = 0L
        enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /** A setting changed: drop the old plan so the new switches take effect now. */
    fun onSettingsChanged(context: Context) {
        ensureLoaded(context)
        if (enabled()) {
            finishRun()
            lastScheduleRequestMs = 0L
            scheduleIfEnabled(context)
        } else {
            cancel(context)
        }
    }

    fun cancel(context: Context) {
        ensureLoaded(context)
        val active = synchronized(lock) { activeVideoId }
        if (active != null) Downloads.cancel(active)
        finishRun()
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }.onFailure {
            TrackLog.w(TAG, "could not cancel the high-quality pass: ${it.message}")
        }
    }

    /**
     * One downloaded target has finished (or failed).
     *
     * Called from [Downloads.run]'s finally block, so it also sees ordinary
     * downloads — the id simply is not the pass's active target and this
     * returns. [success] is the download's own answer, not "a file exists":
     * a failed replacement leaves the old file in place and must not be
     * mistaken for a completed one.
     */
    fun onDownloadSettled(context: Context, videoId: String, success: Boolean) {
        if (!ready) return
        if (synchronized(lock) { activeVideoId } != videoId) return
        settle(context, videoId, success)
    }

    internal suspend fun runOne(context: Context) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        if (!enabled()) {
            finishRun()
            return@withContext
        }
        if (recoverActive(context)) return@withContext
        if (!enabled()) {
            finishRun()
            return@withContext
        }

        var target = synchronized(lock) { plan.firstOrNull() }
        if (target == null) {
            val built = buildPlan(context)
            if (built.isEmpty()) {
                finishRun()
                return@withContext
            }
            synchronized(lock) {
                plan = built
                done = 0
                total = built.size
                activeVideoId = null
                persistLocked()
                publishLocked()
            }
            target = built.first()
        }

        val pending = target ?: return@withContext
        val verified = HqVerificationStore.get(
            context,
            pending.target.videoId,
            pending.target.sourceConfigId,
            pending.target.trackId,
        )
        if (verified == null) {
            TrackLog.w(
                TAG,
                "hq-library: '${pending.song.title}' has no stored verdict any more; skipping",
                pending.target.videoId,
            )
            settle(context, pending.target.videoId, success = false)
            return@withContext
        }

        synchronized(lock) {
            activeVideoId = pending.target.videoId
            persistLocked()
            publishLocked()
        }

        Downloads.enqueue(
            context = context,
            song = pending.song,
            allowMetered = true,
            quality = DownloadedQuality.qualityFor(pending.target.verifiedTier),
            hq = HqDownloadSource(
                sourceConfigId = pending.target.sourceConfigId,
                trackId = pending.target.trackId,
                tier = pending.target.verifiedTier,
                offsetMs = verified.offsetMs,
                rate = verified.rate,
            ),
        )

        // A refused start leaves a Failed row and never reaches Downloads.run,
        // so the finally hook will not fire for it. Settle it here instead of
        // leaving the pass stuck on this id until the next launch.
        when (Downloads.active.value[pending.target.videoId]) {
            is DownloadState.Failed -> {
                Downloads.dismissFailure(pending.target.videoId)
                settle(context, pending.target.videoId, success = false)
            }
            null -> settle(context, pending.target.videoId, success = false)
            else -> Unit
        }
    }

    /**
     * Handles an active target left behind by a process death.
     *
     * @return true when that download is still running, in which case the pass
     *   must not enqueue another one.
     */
    private fun recoverActive(context: Context): Boolean {
        val active = synchronized(lock) { activeVideoId } ?: return false
        when (Downloads.active.value[active]) {
            is DownloadState.Queued, is DownloadState.Running -> return true
            else -> Unit
        }
        val target = synchronized(lock) { plan.firstOrNull { it.target.videoId == active } }
        val applied = target != null && Downloads.hqApplied(
            active,
            HqDownloadSource(
                sourceConfigId = target.target.sourceConfigId,
                trackId = target.target.trackId,
                tier = target.target.verifiedTier,
            ),
        )
        if (applied) {
            settle(context, active, success = true, scheduleNext = false)
        } else {
            // Nothing landed. Keep the target in the plan and try the same one
            // again now rather than counting it as done.
            synchronized(lock) {
                activeVideoId = null
                persistLocked()
                publishLocked()
            }
        }
        return false
    }

    private fun settle(
        context: Context,
        videoId: String,
        success: Boolean,
        scheduleNext: Boolean = true,
    ) {
        ensureLoaded(context)
        var rebuild = false
        val remaining = synchronized(lock) {
            if (activeVideoId != videoId) return
            val left = plan.filterNot { it.target.videoId == videoId }
            plan = left
            done += 1
            activeVideoId = null
            if (left.isEmpty()) {
                done = 0
                total = 0
                rebuild = rebuildRequested
                rebuildRequested = false
            }
            persistLocked()
            publishLocked()
            left
        }
        if (!success) {
            TrackLog.w(TAG, "hq-library: $videoId did not land; it will be retried next time")
        }
        if (enabled() && (rebuild || (scheduleNext && remaining.isNotEmpty()))) {
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    private fun finishRun() {
        synchronized(lock) {
            plan = emptyList()
            done = 0
            total = 0
            activeVideoId = null
            if (ready) {
                persistLocked()
                publishLocked()
            } else {
                _progress.value = Progress()
            }
        }
    }

    private suspend fun buildPlan(context: Context): List<PendingTarget> {
        val verified = HqVerificationStore.entries(context)
        if (verified.isEmpty()) return emptyList()
        val downloaded = Downloads.savedMetadataSnapshot()
        val recent = runCatching { ListeningStats.recentTracks(HISTORY_LIMIT) }
            .getOrDefault(emptyList())
            .associateBy { it.id }
        val liked = LikeState.overrides.value
            .filterValues { it == LikeStatus.LIKE }
            .keys

        // One entry per song, on the best rung any accepted pair proved. A
        // second source that proved a higher rung is the one to fetch; keeping
        // the first would leave the better copy unseen.
        val best = verified
            .groupBy { it.youtubeId }
            .mapValues { (_, candidates) ->
                candidates.minWith(
                    compareBy<HqVerificationStore.Entry> { it.verified.tier.rank }
                        .thenBy { it.sourceConfigId }
                        .thenBy { it.trackId },
                )
            }

        val entries = best.values.mapNotNull { entry ->
            val meta = downloaded[entry.youtubeId]
            HqLibraryEntry(
                videoId = entry.youtubeId,
                currentTier = meta?.let { DownloadedQuality.tierOf(it.downloadFormat) },
                verifiedTier = entry.verified.tier,
                sourceConfigId = entry.sourceConfigId,
                trackId = entry.trackId,
                plays = recent[entry.youtubeId]?.plays ?: 0,
                liked = entry.youtubeId in liked,
            )
        }

        val selected = HqLibraryPolicy.select(
            entries = entries,
            prefetchLimit = AppSettings.hqPrefetchLimit.value,
            replaceEnabled = AppSettings.autoReplaceHqDownloads.value,
            prefetchEnabled = AppSettings.autoPrefetchHq.value,
        )

        return (selected.replace + selected.prefetch).mapNotNull { target ->
            val song = songFor(context, target, downloaded, recent)
            if (song == null) {
                TrackLog.d(TAG, "hq-library: no details for ${target.videoId}; skipping")
                null
            } else {
                PendingTarget(target, song)
            }
        }
    }

    private suspend fun songFor(
        context: Context,
        target: HqLibraryTarget,
        downloaded: Map<String, SavedSongMetadata>,
        recent: Map<String, TrackEntry>,
    ): Song? {
        Downloads.savedSongFor(target.videoId)
            ?.takeIf { it.title.isNotBlank() }
            ?.let { return it }
        recent[target.videoId]
            ?.asSong()
            ?.takeIf { it.title.isNotBlank() }
            ?.let { return it }
        // A liked song that has not been played on this device has no local
        // row. One lookup, only for such a target, and the result is persisted
        // in the plan so the later one-at-a-time workers do not repeat it.
        return runCatching { YtMusicRepository.trackLinks(target.videoId).getOrNull() }
            .getOrNull()
            ?.takeIf { it.title.isNotBlank() }
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        if (!enabled()) return
        val request = OneTimeWorkRequestBuilder<HqLibraryUpgradeWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, policy, request)
        }.onFailure {
            TrackLog.w(TAG, "could not schedule the high-quality pass: ${it.message}")
        }
    }

    private fun ensureLoaded(context: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            val app = context.applicationContext
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            plan = prefs.getString(KEY_PLAN, null).orEmpty()
                .lineSequence()
                .mapNotNull(::parseTarget)
                .toList()
            done = prefs.getInt(KEY_DONE, 0)
            total = prefs.getInt(KEY_TOTAL, 0)
            activeVideoId = prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }
            if (plan.isEmpty() && activeVideoId != null) activeVideoId = null
            if (plan.isEmpty()) {
                done = 0
                total = 0
            } else if (total <= 0) {
                total = plan.size + done
            }
            publishLocked()
        }
    }

    private fun persistLocked() {
        if (!ready) return
        prefs.edit()
            .putString(KEY_PLAN, plan.joinToString("\n") { encodeTarget(it) })
            .putInt(KEY_DONE, done)
            .putInt(KEY_TOTAL, total)
            .putString(KEY_ACTIVE, activeVideoId)
            .apply()
    }

    private fun publishLocked() {
        _progress.value = Progress(
            running = plan.isNotEmpty() || activeVideoId != null,
            done = done,
            total = total,
        )
    }

    private fun encodeTarget(pending: PendingTarget): String = listOf(
        pending.target.action.name,
        pending.target.videoId,
        pending.target.verifiedTier.name,
        pending.target.sourceConfigId,
        pending.target.trackId,
        pending.song.title,
        pending.song.artist,
        pending.song.thumbnailUrl.orEmpty(),
        pending.song.durationText.orEmpty(),
        pending.song.albumName.orEmpty(),
    ).joinToString(FIELD_SEPARATOR) { it.sanitizedField() }

    private fun parseTarget(line: String): PendingTarget? {
        if (line.isBlank()) return null
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 10) return null
        val action = runCatching { HqLibraryAction.valueOf(parts[0]) }.getOrNull() ?: return null
        val tier = runCatching { HqTier.valueOf(parts[2]) }.getOrNull() ?: return null
        val videoId = parts[1]
        val source = parts[3]
        val track = parts[4]
        if (videoId.isBlank() || source.isBlank() || track.isBlank()) return null
        val target = HqLibraryTarget(
            videoId = videoId,
            action = action,
            verifiedTier = tier,
            sourceConfigId = source,
            trackId = track,
        )
        val song = Song(
            videoId = videoId,
            title = parts[5],
            artist = parts[6],
            thumbnailUrl = parts[7].takeIf { it.isNotBlank() },
            durationText = parts[8].takeIf { it.isNotBlank() },
            albumName = parts[9].takeIf { it.isNotBlank() },
        )
        if (song.title.isBlank()) return null
        return PendingTarget(target, song)
    }

    private fun String.sanitizedField(): String =
        replace(FIELD_SEPARATOR, " ").replace('\n', ' ').replace('\r', ' ')

    private const val FIELD_SEPARATOR = "\u0001"
}

class HqLibraryUpgradeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!HqLibraryUpgrade.enabled()) return Result.success()
        HqLibraryUpgrade.runOne(applicationContext)
        return Result.success()
    }
}
