package com.music.bitchord.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings

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

    fun scheduleIfEnabled(context: Context) {
        if (!AppSettings.autoRedownloadMvSound.value) return
        val request = OneTimeWorkRequestBuilder<MvSoundRedownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }.onFailure {
            TrackLog.w("BitChord", "could not schedule MV-sound repair: ${it.message}")
        }
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
        val queued = Downloads.redownloadSuspected(applicationContext)
        if (queued > 0) {
            TrackLog.i("BitChord", "song version: repairing $queued downloaded file(s) in the background")
        }
        return Result.success()
    }
}
