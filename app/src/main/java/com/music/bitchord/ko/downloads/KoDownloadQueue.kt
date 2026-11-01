package com.music.bitchord.ko.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.HqAutoDownloadPolicy
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.download.DownloadProgress
import com.music.bitchord.download.DownloadService
import com.music.bitchord.download.DownloadSession
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads
import com.music.bitchord.startup.AppForeground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Korean fork's per-download queue state: the rung or verified pair a
 * replacement asked for, the enqueue generations that keep a retry's row from
 * staying on an older Failed/Done state, and the wake signal idle workers wait
 * on instead of polling.
 */
internal object KoDownloadQueue {

    private const val TAG = "BitChord"

    /**
     * Bumped whenever the queue may have changed, so idle workers sleep on a
     * change instead of polling every 100 ms.
     *
     * A revision rather than a channel: every waiting worker has to see the
     * same change, and a revision cannot be consumed by whichever worker woke
     * first. A worker reads it before looking at the queue, so an enqueue that
     * lands in between is not lost.
     */
    private val workRevision = MutableStateFlow(0L)

    /**
     * The enqueue generation each waiting id was inserted under, and the
     * generation a worker has since claimed.
     *
     * The Queued publish runs under [Downloads.lock] with the insert, so a fresh
     * claim cannot overtake it. When the two generations differ the row has been
     * claimed for this enqueue and any Running/Failed/Done state belongs to that
     * claim, not to an older attempt; when they match any old failure or Done
     * row must be reset so a retry reads as Queued.
     */
    private val enqueueGenerations = HashMap<String, Long>()
    private val claimedGenerations = HashMap<String, Long>()
    private var generationCounter = 0L

    /**
     * A rung asked for by a deliberate replacement rather than the standing
     * `AppSettings.downloadQuality`. Guarded by [Downloads.lock] while written,
     * and only read by [prepare] for the track it names.
     */
    private val qualityOverrides = HashMap<String, DownloadQuality>()

    /**
     * The verified source/track pair a replacement must use, when it is the
     * automatic high-quality pass asking rather than a listener tap.
     */
    private val hqOverrides = HashMap<String, HqDownloadSource>()

    /**
     * A source that must not be asked for this track's repair.
     *
     * The health check names the source that supplied a wrong recording, so
     * the re-download does not land on the very same row again.
     */
    private val avoidSources = HashMap<String, String>()

    /** Ids whose existing file must not be adopted, even under the same name. */
    private val forceReplaceIds = HashSet<String>()

    /** Ids that must be fetched from YouTube, never from a configured source. */
    private val youtubeOnlyIds = HashSet<String>()

    /**
     * File names another transfer in this process has already claimed.
     *
     * Two downloads of different songs can share a name and run at the same
     * time. The second must not choose the same destination while the first is
     * still writing it, even though neither final file exists yet.
     */
    private val reservedNames = HashSet<String>()

    /** A repair's minimum served rung. */
    private val minimumTiers = HashMap<String, HqTier>()

    /**
     * Downloads the automatic high-quality pass asked for, not a listener.
     *
     * Read once when the download starts. These stay out of [DownloadSession]
     * entirely: a server's back-pressure on work nobody requested must not
     * become a red failure badge, and the pass has its own progress line.
     */
    private val automaticIds = HashSet<String>()

    /**
     * Downloads that are repairing a file judged to be a music video's sound.
     *
     * Their whole point is to fetch a *different* upload. If the catalogue has
     * no song version the lookup falls back to the same id, and fetching that
     * would write the same MV stream again while clearing the warning.
     */
    private val mvSoundRepairIds = HashSet<String>()

    fun remember(
        id: String,
        quality: DownloadQuality?,
        hq: HqDownloadSource?,
        avoidSourceConfigId: String?,
        forceReplace: Boolean,
        youtubeOnly: Boolean,
        minimumTier: HqTier?,
        automatic: Boolean,
        mvSoundRepair: Boolean,
    ) {
        enqueueGenerations[id] = ++generationCounter
        if (quality != null) qualityOverrides[id] = quality
        if (hq != null) hqOverrides[id] = hq
        if (avoidSourceConfigId != null) avoidSources[id] = avoidSourceConfigId
        if (forceReplace) forceReplaceIds.add(id)
        if (youtubeOnly) youtubeOnlyIds.add(id)
        if (minimumTier != null) minimumTiers[id] = minimumTier
        if (automatic) automaticIds.add(id)
        if (mvSoundRepair) mvSoundRepairIds.add(id)
    }

    fun claim(id: String) {
        enqueueGenerations[id]?.let { claimedGenerations[id] = it }
    }

    fun forgetGenerations(id: String) {
        enqueueGenerations.remove(id)
        claimedGenerations.remove(id)
    }

    fun clear(id: String) {
        synchronized(Downloads.lock) {
            qualityOverrides.remove(id)
            hqOverrides.remove(id)
            avoidSources.remove(id)
            forceReplaceIds.remove(id)
            youtubeOnlyIds.remove(id)
            minimumTiers.remove(id)
            automaticIds.remove(id)
            mvSoundRepairIds.remove(id)
            enqueueGenerations.remove(id)
            claimedGenerations.remove(id)
        }
    }

    fun dropVerifiedPair(id: String) {
        synchronized(Downloads.lock) { hqOverrides.remove(id) }
    }

    fun takeAutomatic(id: String): Boolean = synchronized(Downloads.lock) { automaticIds.remove(id) }

    fun takeMvRepair(id: String): Boolean = synchronized(Downloads.lock) { mvSoundRepairIds.remove(id) }

    fun qualityFor(id: String): DownloadQuality? = synchronized(Downloads.lock) { qualityOverrides[id] }

    fun hqFor(id: String): HqDownloadSource? = synchronized(Downloads.lock) { hqOverrides[id] }

    fun avoidSourceFor(id: String): String? = synchronized(Downloads.lock) { avoidSources[id] }

    fun forceReplaceFor(id: String): Boolean = synchronized(Downloads.lock) { forceReplaceIds.contains(id) }

    fun youtubeOnlyFor(id: String): Boolean = synchronized(Downloads.lock) { youtubeOnlyIds.contains(id) }

    fun minimumTierFor(id: String): HqTier? = synchronized(Downloads.lock) { minimumTiers[id] }

    fun reserveName(candidate: String): Boolean = synchronized(Downloads.lock) { reservedNames.add(candidate) }

    fun releaseName(name: String) {
        synchronized(Downloads.lock) { reservedNames.remove(name) }
    }

    /** The current work generation; the same revision [workRevision] exposes. */
    fun workVersion(): Long = workRevision.value

    /** Wake every waiting worker after the queue or its running set changes. */
    fun wakeWorkers() {
        workRevision.update { it + 1 }
    }

    fun workRevision(): Long = workRevision.value

    suspend fun awaitWorkChange(seen: Long) {
        workRevision.first { it != seen }
    }

    suspend fun awaitWorkChange(seen: Long, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { awaitWorkChange(seen) } != null

    /**
     * Publish Queued only while [song]'s id is still waiting or was claimed by
     * the service.
     *
     * [onForeground] can take the row back after the enqueue inserted it but
     * before this publish; writing Queued then would overwrite the refusal
     * while no pending row remains. Conversely, [Downloads.takeNext] can claim
     * the row for an already-running service before this publish; the session
     * row must still be created, or the later running/done writes have nothing
     * to update and the track silently disappears from the manager.
     */
    fun publishQueuedIfPending(
        song: Song,
        from: String?,
        publishSession: Boolean,
    ) {
        synchronized(Downloads.lock) {
            val id = song.videoId
            if (id !in Downloads.pending && id !in Downloads.running) return
            val enqueueGeneration = enqueueGenerations[id]
            val claimedGeneration = claimedGenerations[id]
            // Only a row that was not claimed after this enqueue can carry a
            // state from an older attempt. In that case Queued must win, so a
            // retry of a Failed or Done download does not keep showing the old
            // row until the worker happens to start. A row already claimed for
            // this enqueue may have advanced past Queued and must not be moved
            // back.
            val freshUnclaimed = enqueueGeneration != null &&
                claimedGeneration != enqueueGeneration
            if (!freshUnclaimed) {
                val activeState = Downloads._active.value[id]
                val sessionState = if (publishSession) DownloadSession.progressOf(id) else null
                val alreadyAdvanced = activeState is DownloadState.Running ||
                    activeState is DownloadState.Failed ||
                    (sessionState != null && sessionState !is DownloadProgress.Queued)
                if (alreadyAdvanced) return
            }
            Downloads._active.update { it + (id to DownloadState.Queued) }
            if (publishSession) DownloadSession.queued(song, from)
            enqueueGenerations.remove(id)
            claimedGenerations.remove(id)
        }
    }

    /**
     * The app has a window again: drain anything queued while Android would
     * not let this process start the download service.
     */
    fun onForeground(context: Context) {
        if (!AppForeground.isForeground) return
        val app = Downloads.applicationContext() ?: context.applicationContext
        val waiting = synchronized(Downloads.lock) { Downloads.pending.keys.toList() }
        if (waiting.isEmpty()) return
        val startFailure = startService(app) ?: return
        // The service that was to drain these never started. Leaving them
        // Queued would look accepted forever, so take each back and show the
        // same refusal a foreground enqueue shows. There is no timer; the next
        // tap or foreground event can ask again.
        Log.w(TAG, "could not start the download service: ${startFailure.message}")
        for (id in waiting) {
            if (abandonPending(id)) Downloads.fail(id, "지금은 다운로드를 시작할 수 없습니다")
        }
    }

    /**
     * Take a track back out of the queue when its service could not start.
     *
     * Returns whether it was still waiting: a cancel that won the race must not
     * be turned back into a failure row.
     */
    fun abandonPending(videoId: String): Boolean = synchronized(Downloads.lock) {
        if (Downloads.pending.remove(videoId) == null) return@synchronized false
        qualityOverrides.remove(videoId)
        hqOverrides.remove(videoId)
        avoidSources.remove(videoId)
        forceReplaceIds.remove(videoId)
        youtubeOnlyIds.remove(videoId)
        minimumTiers.remove(videoId)
        automaticIds.remove(videoId)
        mvSoundRepairIds.remove(videoId)
        enqueueGenerations.remove(videoId)
        claimedGenerations.remove(videoId)
        wakeWorkers()
        true
    }

    fun startService(context: Context): Throwable? =
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }.exceptionOrNull()
    /**
     * Start the service for a just-enqueued track, or take the row back if
     * Android refuses. A background process leaves it queued for [onForeground];
     * there is no timer.
     */
    fun startAfterEnqueue(context: Context, id: String) {
        if (!AppForeground.isForeground) return
        val app = context.applicationContext
        val startFailure = startService(app) ?: return
        // Refused only when the app has no window and no exemption, which means
        // the queue has nothing to drain it and would sit there looking
        // accepted forever.
        Log.w(TAG, "could not start the download service: ${startFailure.message}")
        if (abandonPending(id)) {
            Downloads.fail(id, "지금은 다운로드를 시작할 수 없습니다")
        }
    }

}
