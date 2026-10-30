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
 * Fetches the song versions of downloads that turned out to hold a music
 * video's sound, when the listener has left the repair to the app.
 *
 * One one-shot piece of work, not a poll: it is scheduled when a verdict is
 * first remembered, on app start and on playback. It runs as soon as a network
 * is available, whatever the charger is doing. Wi-Fi is deliberately not a
 * constraint — the setting exists because data and storage are not the scarce
 * thing here. The download itself goes through the ordinary [Downloads] queue
 * and [DownloadService], so it is the same work a tap would have started: one
 * file at a time, and the next app start or playback picks up whatever is still
 * remembered after an interruption.
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
     * APPEND_OR_REPLACE starts a fresh one.
     */
    internal val WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

    private val pauseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Which pause is current. A pause that was queued before the app came back
     * must not cancel the pass the foreground event has just enqueued.
     */
    private val pauseGeneration = AtomicInteger(0)

    fun scheduleIfEnabled(context: Context) {
        if (!AppSettings.autoRedownloadMvSound.value) return
        // The repair goes through the download service, which cannot be started
        // without a window. The foreground event schedules it again.
        if (!AppForeground.isForeground) return
        // Every suspect still cooling down is not work. Scheduling a worker
        // for it would run the same scan on every app start and playback
        // without ever changing anything.
        if (!Downloads.hasRepairableMvSuspects(context, System.currentTimeMillis())) return
        val request = OneTimeWorkRequestBuilder<MvSoundRedownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        // Invalidate any pause still querying: this work is for the window that
        // is on screen now, not for the background it was queued in.
        pauseGeneration.incrementAndGet()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, WORK_POLICY, request)
        }.onFailure {
            TrackLog.w("BitChord", "could not schedule MV-sound repair: ${it.message}")
        }
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
                cancelTargets(current).forEach { workManager.cancelWorkById(it) }
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
        if (!AppSettings.autoRedownloadMvSound.value) return Result.success()
        // WorkManager can run a network-constrained request after the app has
        // gone away. Queueing downloads then would only leave rows waiting for
        // a service Android will not let this process start.
        if (!AppForeground.isForeground) return Result.success()
        val queued = Downloads.redownloadSuspected(applicationContext)
        if (queued > 0) {
            TrackLog.i("BitChord", "song version: repairing $queued downloaded file(s) in the background")
        }
        return Result.success()
    }
}
