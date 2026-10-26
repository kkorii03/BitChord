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
import com.music.bitchord.data.sources.DownloadHealthPolicy
import com.music.bitchord.data.sources.HqLibraryAction
import com.music.bitchord.data.sources.HqLibraryEntry
import com.music.bitchord.data.sources.HqLibraryPolicy
import com.music.bitchord.data.sources.HqLibraryTarget
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.TrackEntry
import com.music.bitchord.playback.smart.HqAudioVerify
import com.music.bitchord.playback.smart.HqVerificationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private const val KEY_MANUAL = "manual"

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

    /**
     * A pass the listener asked for with "모두 고음질로 다시 받기".
     *
     * The automatic switches decide whether the background pass may run at
     * all. A button is a one-off instruction, so it keeps its own flag: the
     * pass may run with both switches off, but only for this run, and it is
     * cleared as soon as the plan is finished or cancelled.
     */
    private var manualPass: Boolean = false

    /** A new verdict arrived while a replacement was in flight; rebuild after it. */
    private var rebuildRequested: Boolean = false

    @Volatile
    private var lastScheduleRequestMs: Long = 0L

    private val ready: Boolean get() = this::prefs.isInitialized

    private val manualScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enabled(): Boolean =
        AppSettings.autoReplaceHqDownloads.value || AppSettings.autoPrefetchHq.value

    /** Whether this run may proceed: either switch is on, or the listener asked. */
    internal fun runAllowed(): Boolean = enabled() || manualPass

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

    /**
     * The Downloads screen's "모두 고음질로 다시 받기" button.
     *
     * Starts the replacement half of the pass at once, even with the automatic
     * switches off. It is still the same one-target-at-a-time worker and the
     * same verified-pair download; only the reason for starting differs. New
     * pre-saves are deliberately left out: this tap is about the files already
     * on the device.
     */
    fun startReplacementPass(context: Context) {
        ensureLoaded(context)
        synchronized(lock) {
            manualPass = true
            if (activeVideoId == null) {
                plan = emptyList()
                done = 0
                total = 0
            } else {
                // Let the replacement already in flight land, then rebuild
                // with the button's wider scope.
                rebuildRequested = true
            }
            persistLocked()
            publishLocked()
        }
        lastScheduleRequestMs = 0L
        enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /**
     * The song menu's quality upgrade for a file already on the device.
     *
     * A verified higher pair is fetched through the same pinned replacement as
     * the automatic pass. With no verdict yet, one search runs for this track
     * only, and the candidate must clear the same sound comparison before it is
     * allowed to replace the file. A deliberate upgrade with no proved better
     * pair does nothing; a repair may fall back to a re-fetch only when the
     * served rendition is at least the rung of the file it replaces.
     */
    fun requestUpgrade(context: Context, song: Song, allowEqual: Boolean = false) {
        ensureLoaded(context)
        val meta = Downloads.savedMetadataFor(song.videoId)
        val badge = song.downloadFormat ?: meta?.downloadFormat
        val currentTier = DownloadedQuality.tierOf(badge, meta?.hqTier)
        // A repair must not ask the very source that supplied the wrong
        // recording. Computed before the entry lookup so it can be excluded
        // there, not only from the later search.
        val wrongSource = meta?.takeIf { it.wrongRecording }?.hqSourceConfigId
        val entry = bestEntryFor(
            context = context,
            videoId = song.videoId,
            songTitle = song.title,
            songArtist = song.artist,
            excludeSourceConfigId = wrongSource,
        )
        // A repair may accept an equal rung, but never a lower one: replacing
        // a lossless file with a verified 320kbps copy would be a downgrade
        // dressed as a fix.
        val entryUsable = entry != null &&
            if (allowEqual) {
                entry.verified.tier.rank <= currentTier.rank
            } else {
                HqLibraryPolicy.isBetter(currentTier, entry.verified.tier)
            }
        if (entry != null && entryUsable) {
            enqueueVerified(context, song, entry)
            if (!allowEqual) {
                TrackLog.i(
                    TAG,
                    "download-check: ${song.videoId} result=lower-quality action=upgrade " +
                        "tier=${entry.verified.tier.name}",
                    song.videoId,
                )
            }
            return
        }
        manualScope.launch(TrackLog.about(song.videoId)) {
            val target = TrackMatcher.targetOf(song)
            val candidate = runCatching {
                SourceResolver.upgradeFor(
                    target = target,
                    playing = DownloadedQuality.formatOf(badge),
                    excludeSources = setOfNotNull(wrongSource),
                )
            }.getOrNull()
            if (candidate != null) {
                val sourceId = candidate.sourceConfigId
                val trackId = candidate.trackId
                if (!sourceId.isNullOrBlank() && !trackId.isNullOrBlank()) {
                    val verified = HqAudioVerify.verify(context, song.videoId, target, candidate)
                    val acceptable = verified != null &&
                        if (allowEqual) {
                            verified.tier.rank <= currentTier.rank
                        } else {
                            HqLibraryPolicy.isBetter(currentTier, verified.tier)
                        }
                    if (verified != null && acceptable) {
                        enqueueVerified(
                            context,
                            song,
                            HqVerificationStore.Entry(song.videoId, sourceId, trackId, verified),
                        )
                        return@launch
                    }
                }
            }
            // A deliberate upgrade only ever replaces a file with a pair the
            // sound comparison proved better. Falling back to an unverified
            // search here used to fetch a lower rendition (320 AAC asked for,
            // 160 Opus served) under the banner of an upgrade.
            if (!allowEqual) return@launch
            // The repair could not be proved first, so the download itself has
            // to prove it: the served rendition must be at least the rung of
            // the file being replaced, or [Downloads.prepare] refuses it and
            // the old file stays.
            val fallbackQuality = if (currentTier == HqTier.DOLBY_ATMOS) {
                DownloadedQuality.qualityFor(currentTier)
            } else {
                DownloadedQuality.qualityForUpgrade(badge, null)
            }
            Downloads.enqueue(
                context = context,
                song = song,
                allowMetered = true,
                quality = fallbackQuality,
                avoidSourceConfigId = wrongSource,
                forceReplace = true,
                youtubeOnly = true,
                minimumTier = currentTier,
            )
        }
    }

    private fun enqueueVerified(
        context: Context,
        song: Song,
        entry: HqVerificationStore.Entry,
    ) {
        Downloads.enqueue(
            context = context,
            song = song,
            allowMetered = true,
            quality = DownloadedQuality.qualityFor(entry.verified.tier),
            hq = HqDownloadSource(
                sourceConfigId = entry.sourceConfigId,
                trackId = entry.trackId,
                tier = entry.verified.tier,
                offsetMs = entry.verified.offsetMs,
                rate = entry.verified.rate,
                sourceTitle = entry.verified.sourceTitle,
                sourceArtist = entry.verified.sourceArtist,
            ),
        )
    }

    private fun bestEntryFor(
        context: Context,
        videoId: String,
        songTitle: String,
        songArtist: String,
        excludeSourceConfigId: String? = null,
    ): HqVerificationStore.Entry? =
        HqVerificationStore.entries(context)
            .asSequence()
            .filter { it.youtubeId == videoId }
            .filter { !it.sourceConfigId.isBlank() && !it.trackId.isBlank() }
            .filter { excludeSourceConfigId == null || it.sourceConfigId != excludeSourceConfigId }
            .filter {
                !DownloadHealthPolicy.isArrangement(
                    sourceTitle = it.verified.sourceTitle,
                    sourceArtist = it.verified.sourceArtist,
                    songTitle = songTitle,
                    songArtist = songArtist,
                )
            }
            .minWithOrNull(
                compareBy<HqVerificationStore.Entry> { it.verified.tier.rank }
                    .thenBy { it.sourceConfigId }
                    .thenBy { it.trackId },
            )

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
        if (!runAllowed()) {
            finishRun()
            return@withContext
        }
        if (recoverActive(context)) return@withContext
        if (!runAllowed()) {
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
                sourceTitle = pending.target.sourceTitle ?: verified.sourceTitle,
                sourceArtist = pending.target.sourceArtist ?: verified.sourceArtist,
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
                // A button-started pass ends with its plan. Only a verdict
                // that arrived mid-run keeps it alive for the rebuild.
                if (!rebuild) manualPass = false
            }
            persistLocked()
            publishLocked()
            left
        }
        if (!success) {
            TrackLog.w(TAG, "hq-library: $videoId did not land; it will be retried next time")
        }
        if (runAllowed() && (rebuild || (scheduleNext && remaining.isNotEmpty()))) {
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    private fun finishRun() {
        synchronized(lock) {
            plan = emptyList()
            done = 0
            total = 0
            activeVideoId = null
            manualPass = false
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

        // Every accepted pair is handed on, not one pre-collapsed winner per
        // song: a higher-tier piano cover must not hide a lower-tier verified
        // entry before [HqLibraryPolicy] has had the chance to drop the cover
        // and group the rest by song.
        val entries = verified.mapNotNull { entry ->
            val meta = downloaded[entry.youtubeId]
            val recentRow = recent[entry.youtubeId]
            HqLibraryEntry(
                videoId = entry.youtubeId,
                currentTier = meta?.let {
                    DownloadedQuality.tierOf(it.downloadFormat, it.hqTier)
                },
                verifiedTier = entry.verified.tier,
                sourceConfigId = entry.sourceConfigId,
                trackId = entry.trackId,
                plays = recentRow?.plays ?: 0,
                liked = entry.youtubeId in liked,
                songTitle = meta?.title ?: recentRow?.title.orEmpty(),
                songArtist = meta?.artist ?: recentRow?.artist.orEmpty(),
                sourceTitle = entry.verified.sourceTitle,
                sourceArtist = entry.verified.sourceArtist,
            )
        }

        val selected = HqLibraryPolicy.select(
            entries = entries,
            prefetchLimit = AppSettings.hqPrefetchLimit.value,
            replaceEnabled = manualPass || AppSettings.autoReplaceHqDownloads.value,
            // The button is about the files already on the device; pre-saving
            // new ones is the background setting's job, not this tap's.
            prefetchEnabled = !manualPass && AppSettings.autoPrefetchHq.value,
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
        if (!runAllowed()) return
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
            manualPass = prefs.getBoolean(KEY_MANUAL, false)
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
            .putBoolean(KEY_MANUAL, manualPass)
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
        if (!HqLibraryUpgrade.runAllowed()) return Result.success()
        HqLibraryUpgrade.runOne(applicationContext)
        return Result.success()
    }
}
