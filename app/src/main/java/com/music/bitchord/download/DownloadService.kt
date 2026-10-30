package com.music.bitchord.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.BatteryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the download queue drains, and says so.
 *
 * A download is the one thing this app does that a user starts and then leaves:
 * they tap it and put the phone in a pocket. A coroutine on a ViewModel scope
 * would be killed the moment the activity goes, and a plain background service
 * on a modern Android is killed almost as fast — so this is a foreground
 * service, which is also the only honest arrangement, since a notification is
 * exactly what the user should get for work happening out of sight.
 *
 * It owns no state. The queue and everything known about it live in
 * [Downloads]; this drives that queue and reflects it into a notification, and
 * stops itself the moment there is nothing left to do.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var drain: Job? = null
    private var notifier: Job? = null

    /** Whether a drain is running, and whether another start arrived meanwhile. */
    private val drainGate = DrainGate()

    /** Distinguishes this drain from one a late start request just began. */
    private var drainGeneration = 0L

    /** The id of the latest start request, so stopping cannot discard a newer one. */
    private val startIds = StartIdLedger()

    /** What the notification is currently about. */
    @Volatile
    private var current: Song? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within a few seconds of the start request whatever the
        // intent turns out to be, the cancel below included — a service started
        // with startForegroundService and never promoted takes the app down
        // with it.
        promote()
        val latestStartId = startIds.record(startId)

        if (intent?.action == ACTION_CANCEL_ALL) {
            Downloads.active.value.keys.toList().forEach(Downloads::cancel)
            shutdown(stopWork = true)
            return START_NOT_STICKY
        }

        ensureDrain(latestStartId)
        // Not sticky: a queue is a list of things asked for in a session, and
        // reviving the service without one would put up a notification about
        // nothing.
        return START_NOT_STICKY
    }

    /**
     * [WORKERS] tracks at a time, each pulling from the same queue.
     *
     * This used to be one, on the reasoning that these are ranged fetches
     * already served at line rate and two at once would finish neither sooner.
     * That reasoning was about the *transfer*, and the transfer turned out to
     * be the small half. Working out where a lossless track's bytes come from
     * — a search across every module in the index, then a stream endpoint
     * opened against the winner, then another when that one answers with a
     * lossy copy — is tens of seconds a track, and none of it is bandwidth. On
     * a 300-track queue drained one at a time, that is a connection sitting
     * idle for the great majority of the run: measured at ~19s a track against
     * a few seconds of actual transfer.
     *
     * It is latency, so the answer is overlap. Four in flight means four
     * lookups outstanding at once, and the module engines they land on are
     * pooled to match — see `QuickJsExecutor.ENGINES_PER_MODULE`, without which
     * this would be four workers taking turns on one interpreter and no faster
     * than one.
     *
     * A worker that finds the queue empty waits [IDLE_GRACE_MS] before giving
     * up rather than exiting on the spot. The queue is filled by a loop of
     * [Downloads.enqueue] calls and the first of them is what starts this
     * service, so at the moment the workers spin up there may be exactly one
     * track in it — and workers that took "empty" for "finished" would leave a
     * 300-track download being drained by however many happened to win that
     * race.
     */
    private suspend fun drainQueue() = coroutineScope {
        repeat(WORKERS) { launch { work() } }
    }

    /**
     * Start a drain unless one is already running.
     *
     * A start request that lands while the last drain is finishing is not
     * dropped: the finishing coroutine checks the queue again and starts
     * another drain instead of stopping the service. The old code only asked
     * whether [drain] was null, so a request in that window was accepted by
     * Android and then silently ignored — the track sat in the queue with no
     * worker and no later event guaranteed to wake it.
     */
    private fun ensureDrain(startId: Int = startIds.latest()) {
        if (!drainGate.begin()) return
        val generation = ++drainGeneration
        val drainStartId = startId
        if (notifier?.isActive != true) {
            notifier = scope.launch { reflectProgress() }
        }
        drain = scope.launch {
            drainQueue()
            // A start request may have begun a newer drain between the last
            // worker returning and this line. That one owns the service now;
            // stopping it here would recreate the lost wake-up this exists for.
            if (generation != drainGeneration) return@launch
            if (drainGate.finish(queueBusy = Downloads.busy())) {
                ensureDrain()
            } else {
                // A start request that arrived while this drain was running
                // found the gate closed and therefore did not begin a drain.
                // Hand it one now; stopping with an older id would let Android
                // stop the service and strand whatever it queued.
                val stopId = startIds.stopIdAfter(drainStartId)
                if (stopId == null) {
                    ensureDrain()
                } else {
                    // Not shutdown(stopWork = true): this is the drain
                    // coroutine, and cancelling its own job here would be
                    // cancelling itself. stopSelf(id) also refuses to stop
                    // when a newer start request has already been delivered.
                    shutdown(stopWork = false, stopId = stopId)
                }
            }
        }
    }

    private suspend fun work() {
        var idleSince = 0L
        while (true) {
            // Read the revision before looking at the queue: an enqueue that
            // lands between the two is seen as a change and not lost.
            val seen = Downloads.workRevision()
            val song = Downloads.takeNext()
            if (song == null) {
                // Nothing to take, but something may still be arriving — or
                // another worker may fail a track back into view. Only a queue
                // that stays empty, with nothing else in flight, is finished.
                val now = SystemClock.elapsedRealtime()
                if (idleSince == 0L) idleSince = now
                val idleFor = now - idleSince
                if (BatteryPolicy.downloadWorkerShouldStop(idleFor, IDLE_GRACE_MS, Downloads.busy())) {
                    return
                }
                // During the start-of-queue grace the timeout re-checks the
                // deadline; after it, only a running track keeps a worker here
                // and that track's completion is the event that wakes it.
                if (idleFor < IDLE_GRACE_MS) {
                    Downloads.awaitWorkChange(seen, IDLE_GRACE_MS - idleFor)
                } else {
                    Downloads.awaitWorkChange(seen)
                }
                continue
            }
            idleSince = 0L
            current = song
            postNotification()

            // Its own job, so one track can be cancelled out from under the
            // loop without taking the rest of the queue with it.
            val job = scope.launch { Downloads.run(this@DownloadService, song) }
            Downloads.onRunning(song.videoId, job)
            job.join()
            Downloads.onIdle(song.videoId)
        }
    }

    /** Repost as the running track advances, slowly enough not to thrash the shade. */
    private suspend fun reflectProgress() {
        Downloads.active.collect {
            postNotification()
            delay(PROGRESS_REFRESH_MS)
        }
    }

    private fun shutdown(stopWork: Boolean, stopId: Int? = null) {
        if (stopWork) {
            drain?.cancel()
            drainGate.reset()
            notifier?.cancel()
            drain = null
            notifier = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (stopId == null) {
            notifier?.cancel()
            drain = null
            notifier = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // stopSelf(id) refuses when a newer start arrived after
        // stopIdAfter() chose this id. Tearing the notification down before
        // learning that left the new drain running with no foreground
        // notification. Wait for the stop to be confirmed; a refusal means
        // the newer start owns the service, so keep the notifier and hand it
        // a drain instead.
        if (!startIds.stopConfirmed(stopId, ::stopSelfResult)) {
            ensureDrain()
            return
        }
        notifier?.cancel()
        drain = null
        notifier = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        scope.cancel()
        drainGate.reset()
        Downloads.onStopped()
        super.onDestroy()
    }

    // ---- Notification -------------------------------------------------------

    private fun promote() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun postNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val active = Downloads.active.value
        val runningStates = active.values.filterIsInstance<DownloadState.Running>()
        val waiting = active.count { it.value is DownloadState.Queued }

        // Several tracks are in flight, so the bar is the average across them
        // rather than any one track's — a bar that jumped backwards every time
        // a different worker happened to report last would be worse than no bar.
        val percent = runningStates
            .takeIf { it.isNotEmpty() }
            ?.let { states -> states.sumOf { it.fraction.toDouble() } / states.size }
            ?.times(100)?.toInt()
            ?: 0

        val song = current
        val title = when {
            runningStates.size > 1 -> resources.getQuantityString(
                R.plurals.downloading_song_count,
                runningStates.size,
                runningStates.size,
            )
            else -> song?.title ?: getString(R.string.download_notification_title)
        }
        val text = when {
            runningStates.size > 1 && waiting > 0 ->
                getString(R.string.download_notification_more_queued, waiting)
            runningStates.size > 1 -> song?.title.orEmpty()
            song == null -> getString(R.string.download_notification_starting)
            waiting > 0 -> getString(R.string.download_notification_queued, song.artist, waiting)
            else -> song.artist
        }

        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            // Indeterminate until something has a length to measure against.
            .setProgress(100, percent, runningStates.isEmpty())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                // Progress, not news. It belongs in the shade without a sound
                // or a heads-up every time a track finishes.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_channel_description)
                setShowBadge(false)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "downloads"

        /** Distinct from playback's, which Media3 owns. */
        const val NOTIFICATION_ID = 0x8175

        const val ACTION_CANCEL_ALL = "com.music.bitchord.download.CANCEL_ALL"

        /** Four updates a second is smooth; the shade coalesces anything faster anyway. */
        const val PROGRESS_REFRESH_MS = 250L

        /**
         * How many tracks are fetched at once.
         *
         * Sized against what is actually scarce. Bandwidth is not: four
         * lossless tracks at once is comfortably inside a home connection, and
         * the transfers were never the bottleneck. Lookup latency is, and four
         * is where the module engines stop being the limit — the pool behind
         * them is three deep per module, so a fifth worker would mostly be
         * queueing for an interpreter rather than resolving anything. It is
         * also the point past which a failure gets hard to read: eight rows
         * moving at once is a wall of text, not a download.
         */
        const val WORKERS = 4

        /**
         * How long a worker keeps looking at an empty queue before it accepts
         * the queue is empty.
         *
         * The window this covers is the start: the first [Downloads.enqueue] is
         * what starts this service, and the other 371 land over the following
         * moments. Workers that spun up in that gap and took an empty queue for
         * a finished one would leave the whole batch to whoever won the race.
         */
        const val IDLE_GRACE_MS = 2_000L
    }
}

/**
 * Whether a drain should start, and whether one arrived while it was finishing.
 *
 * Kept separate from the service so the "start during finish" case — the one
 * that used to leave a queued track with nothing to run it — can be exercised
 * without Android.
 */
internal class DrainGate {

    private var active = false

    /** @return true when the caller should launch a drain now. */
    @Synchronized
    fun begin(): Boolean {
        if (active) return false
        active = true
        return true
    }

    /**
     * @param queueBusy whether a track arrived after the workers gave up.
     * @return true when another drain must follow this one instead of stopping.
     */
    @Synchronized
    fun finish(queueBusy: Boolean): Boolean {
        active = false
        return queueBusy
    }

    @Synchronized
    fun reset() {
        active = false
    }
}

/**
 * The latest start id the service has been handed.
 *
 * `stopSelf()` stops the service regardless of newer start requests that have
 * already arrived; `stopSelf(id)` only stops when [id] is still the latest.
 * This remembers that id and tells the finishing drain whether a later request
 * arrived while it ran.
 */
internal class StartIdLedger {

    private var latest = 0

    /** Record a start request and return the latest id, which monotonic ids make the max. */
    @Synchronized
    fun record(startId: Int): Int {
        if (startId > latest) latest = startId
        return latest
    }

    @Synchronized
    fun latest(): Int = latest

    /**
     * The id to pass to `stopSelf` after a drain that started at
     * [drainStartId], or null when a newer request has arrived.
     */
    @Synchronized
    fun stopIdAfter(drainStartId: Int): Int? =
        if (latest > drainStartId) null else latest

    /**
     * Whether a stop request will actually stop the service.
     *
     * [requestStop] is the service's `stopSelfResult`: it returns false when a
     * newer start has arrived since [candidate] was chosen. That window is
     * real — the drain reads [stopIdAfter], then a start lands, then it asks
     * Android to stop — so a false answer must leave the notifier and drain
     * alone and hand the service to the newer generation.
     */
    @Synchronized
    fun stopConfirmed(candidate: Int, requestStop: (Int) -> Boolean): Boolean {
        if (latest > candidate) return false
        return requestStop(latest)
    }
}
