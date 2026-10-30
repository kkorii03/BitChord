package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.net.AutomaticRetryPolicy
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.startup.AppForeground
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Whether a music-video-sound repair may use the connection in hand.
 *
 * The banner tap is the listener asking for the repair now, so it may spend
 * mobile data. The worker is background work and obeys the Wi-Fi-only download
 * setting like every other automatic download.
 */
internal fun mvRepairAllowsMetered(manual: Boolean, downloadsAllowedNow: Boolean): Boolean =
    manual || downloadsAllowedNow

/**
 * Whether a connection change is the event that should wake a repair parked by
 * the Wi-Fi-only setting.
 *
 * Only the change from "not allowed" to "allowed" is news: the repair was
 * already waiting, and this is what lets it run when Wi-Fi comes back without
 * waiting for the next app start or track. The reverse change needs nothing —
 * [scheduleIfEnabled] refuses to start a new repair while metered.
 */
internal fun mvRepairShouldReschedule(wasAllowed: Boolean, allowedNow: Boolean): Boolean =
    allowedNow && !wasAllowed

/**
 * Fetches the song versions of downloads that turned out to hold a music
 * video's sound, when the listener has left the repair to the app.
 *
 * One one-shot piece of work, not a poll: it is scheduled when a verdict is
 * first remembered, on app start and on playback. It runs as soon as a network
 * is available, whatever the charger is doing. The Wi-Fi-only download setting
 * still applies to this automatic pass; a banner tap is the listener asking and
 * may use the connection in hand. The download itself goes through the ordinary
 * [Downloads] queue and [DownloadService], so it is the same work a tap would
 * have started: one file at a time, and the next app start or playback picks up
 * whatever is still remembered after an interruption.
 */
object MvSoundRedownload {

    private const val WORK_NAME = "mv-sound-redownload"
    private const val PREFS = "mv_sound_redownload"
    private const val KEY_FAILURES = "failures"

    private val failureLock = Any()

    private data class Failure(val attempts: Int, val lastFailureMs: Long)

    /**
     * Whether this suspect may be attempted now.
     *
     * A repair that failed is not retried on the next app start or track
     * transition: it rests for [AutomaticRetryPolicy.COOLDOWN_MS], and after
     * [AutomaticRetryPolicy.MAX_FAILURES] automatic attempts only the banner
     * tap ([manual]) may start it again.
     */
    fun mayAttempt(context: Context, videoId: String, nowMs: Long, manual: Boolean): Boolean {
        val failure = readFailures(context)[videoId] ?: return true
        return AutomaticRetryPolicy.mayRetry(
            attempts = failure.attempts,
            lastFailureMs = failure.lastFailureMs,
            nowMs = nowMs,
            manual = manual,
        )
    }

    fun markFailed(context: Context, videoId: String) {
        synchronized(failureLock) {
            val failures = readFailures(context).toMutableMap()
            val previous = failures[videoId]?.attempts ?: 0
            failures[videoId] = Failure(previous + 1, System.currentTimeMillis())
            writeFailures(context, failures)
        }
    }

    fun clearFailed(context: Context, videoId: String) {
        synchronized(failureLock) {
            val failures = readFailures(context)
            if (videoId !in failures) return
            writeFailures(context, failures - videoId)
        }
    }

    private fun readFailures(context: Context): Map<String, Failure> =
        prefs(context).getString(KEY_FAILURES, null).orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val attempts = parts[1].toIntOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
                val lastFailureMs = parts[2].toLongOrNull()?.coerceAtLeast(0L) ?: return@mapNotNull null
                parts[0] to Failure(attempts, lastFailureMs)
            }
            .toMap()

    private fun writeFailures(context: Context, failures: Map<String, Failure>) {
        prefs(context).edit()
            .putString(
                KEY_FAILURES,
                failures.entries.joinToString("\n") { (id, failure) ->
                    "$id\t${failure.attempts}\t${failure.lastFailureMs}"
                },
            )
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * A new schedule queues behind a repair pass already in flight.
     *
     * The old [ExistingWorkPolicy.REPLACE] cancelled that pass and started the
     * same scan again on every app start, playback transition and verdict. A
     * library with several suspects could therefore never finish the queue it
     * had already started. Appending is what HqLibraryUpgrade does for the
     * same reason; if the chain has already finished or been cancelled,
     * APPEND_OR_REPLACE starts a fresh one. The allowed-connection transition
     * is the one place that replaces the chain instead (see [scheduleIfEnabled]):
     * it may have a worker parked in retry backoff that would otherwise delay
     * the repair it just woke.
     */
    internal val WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

    private val pauseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Which pause is current. A pause that was queued before the app came back
     * must not cancel the pass the foreground event has just enqueued.
     */
    private val pauseGeneration = AtomicInteger(0)

    /**
     * Serialises "a foreground schedule is queued" with "a pause checks the
     * generation and cancels". Without it a pause could read the chain just
     * before a foreground schedule appended new work and then cancel that new
     * work after its own generation check.
     */
    private val scheduleLock = Any()

    /**
     * Whether a repair pass should be queued now.
     *
     * [hasSuspects] is a lambda rather than a Boolean so the scan is skipped
     * entirely when there is no window to run the repair in.
     */
    internal fun shouldSchedule(
        enabled: Boolean,
        foreground: Boolean,
        hasSuspects: () -> Boolean,
    ): Boolean = enabled && foreground && hasSuspects()

    fun scheduleIfEnabled(context: Context, replace: Boolean = false) {
        if (!AppSettings.autoRedownloadMvSound.value) return
        if (!AppSettings.downloadsAllowedNow) {
            TrackLog.i("BitChord", "mv-sound: waiting for an unmetered connection")
            return
        }
        if (!shouldSchedule(
                enabled = true,
                foreground = AppForeground.isForeground,
                hasSuspects = {
                    Downloads.hasRepairableMvSuspects(context, System.currentTimeMillis())
                },
            )
        ) return
        // With Wi-Fi-only on, let WorkManager hold the work until an unmetered
        // network exists instead of waking on cellular just to retry.
        val network = if (AppSettings.wifiOnlyDownloads.value) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<MvSoundRedownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    .build(),
            )
            .build()
        // Invalidate any pause still querying: this work is for the window that
        // is on screen now, not for the background it was queued in.
        synchronized(scheduleLock) {
            pauseGeneration.incrementAndGet()
            // A connection that just became allowed must not wait behind a worker
            // parked in retry backoff; REPLACE clears that stale request. Ordinary
            // app-start and playback schedules still append.
            val policy = if (replace) ExistingWorkPolicy.REPLACE else WORK_POLICY
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(WORK_NAME, policy, request)
            }.onFailure {
                TrackLog.w("BitChord", "could not schedule MV-sound repair: ${it.message}")
            }
        }
    }

    /**
     * The app no longer has a window.
     *
     * A network-constrained worker left queued would wake the process later
     * with no window to run the repair in — a background start that can only
     * return without doing anything. [pause] cancels it; [scheduleIfEnabled]
     * queues it again from the next foreground event.
     */
    fun onBackground(context: Context) {
        pause(context)
    }

    /**
     * Drops repair work that has not started, and lets a pass already running
     * finish.
     *
     * [WorkManager.cancelUniqueWork] also cancels the worker that is scanning
     * right now, so leaving the app with several suspects made the next
     * foreground start the same scan again — the failure [APPEND_OR_REPLACE]
     * fixed. Cancelling by id only the ENQUEUED/BLOCKED entries stops
     * WorkManager waking the process for work that cannot start a service,
     * while a pass in flight keeps the suspects it has already read.
     *
     * A queued successor is left in place while any part of the chain runs:
     * cancelling it would make it the chain's CANCELLED leaf, and the next
     * [APPEND_OR_REPLACE] treats a cancelled leaf as a dead chain and replaces
     * it — taking the still-running pass with it. The successor then returns
     * at once if the app is still in the background ([MvSoundRedownloadWorker]).
     */
    fun pause(context: Context) {
        val generation = pauseGeneration.incrementAndGet()
        val appContext = context.applicationContext
        pauseScope.launch {
            runCatching {
                val workManager = WorkManager.getInstance(appContext)
                val infos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                // The window may have come back while this query ran; that
                // foreground pass is wanted, not this pause's target.
                if (generation != pauseGeneration.get()) return@launch
                // Re-read immediately before deciding: a queued worker may
                // have started since the list was read, and cancelling it then
                // would stop a real pass mid-scan.
                val current = infos.map { info ->
                    info.id to runCatching {
                        workManager.getWorkInfoById(info.id).get()?.state
                    }.getOrNull()
                }
                synchronized(scheduleLock) {
                    // A foreground schedule may have incremented the generation
                    // while the reads above ran; it owns the chain now.
                    if (generation != pauseGeneration.get()) return@launch
                    // Read each state again under the same lock that schedules
                    // new work, so a queued worker that just turned RUNNING is
                    // not cancelled out from under the pass.
                    val latest = current.map { (id, _) ->
                        id to runCatching {
                            workManager.getWorkInfoById(id).get()?.state
                        }.getOrNull()
                    }
                    cancelTargets(latest).forEach { workManager.cancelWorkById(it) }
                }
            }.onFailure {
                TrackLog.w("BitChord", "could not pause MV-sound repair: ${it.message}")
            }
        }
    }

    /** A pass that is running is left alone; only work that cannot have started is dropped. */
    internal fun cancelBeforeStart(state: WorkInfo.State): Boolean =
        state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED

    /**
     * Which works of the unique chain may be cancelled, using the states read
     * just before cancellation.
     *
     * Nothing is cancelled while any work in the chain is RUNNING, because a
     * CANCELLED successor would turn the chain's leaf into a cancelled one and
     * the next [APPEND_OR_REPLACE] would replace the whole chain. A work whose
     * state is no longer ENQUEUED/BLOCKED (or has disappeared, state null) is
     * skipped for the same reason.
     */
    internal fun cancelTargets(
        entries: List<Pair<UUID, WorkInfo.State?>>,
    ): List<UUID> {
        if (entries.any { it.second == WorkInfo.State.RUNNING }) return emptyList()
        return entries
            .filter { (_, state) -> state != null && cancelBeforeStart(state) }
            .map { it.first }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}

class MvSoundRedownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.autoRedownloadMvSound.value) {
            TrackLog.d("BitChord", "mv-sound repair: skipped; switch is off")
            return Result.success()
        }
        // WorkManager can run a network-constrained request after the app has
        // gone away. Queueing downloads then would only leave rows waiting for
        // a service Android will not let this process start. The foreground
        // event schedules the repair again, so finishing here does not drop it.
        if (!AppForeground.isForeground) {
            TrackLog.d("BitChord", "mv-sound repair: skipped; app has no window")
            return Result.success()
        }
        if (!mvRepairAllowsMetered(manual = false, AppSettings.downloadsAllowedNow)) {
            // The connection changed after the request was scheduled. Retrying
            // here would keep waking this worker on a connection it can never
            // use. The allowed-connection and foreground events both schedule
            // the repair again, so finishing is safe.
            TrackLog.i("BitChord", "mv-sound: waiting for an unmetered connection")
            return Result.success()
        }
        val queued = Downloads.redownloadSuspected(applicationContext)
        if (queued > 0) {
            TrackLog.i("BitChord", "song version: repairing $queued downloaded file(s) in the background")
        } else {
            TrackLog.d("BitChord", "mv-sound repair: no repairable suspect left")
        }
        return Result.success()
    }
}
