package com.music.bitchord.ko

import android.content.Context
import android.os.SystemClock
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.NextPrepPolicy
import com.music.bitchord.playback.smart.HqAudioVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [KO] UPG-4: the next row's YouTube reference profile, made at the end of the
 * next-track bundle (its sync and mix steps are what bring the row's opening
 * to the disk), so the verify at the row's first sound starts from a profile
 * in memory instead of a ninety-second decode at the gate.
 *
 * Only for a row the lossless step would serve ([wanted]: not a video or
 * video-radio row, not a file on the device, not a source row, not already
 * lossless or verified). Nothing is fetched: with no opening on disk,
 * [HqAudioVerify.prepareReferenceAhead] does nothing. Bounded by the
 * bundle's own end-of-track allowance.
 */
object KoReferenceAhead {

    /** Whether the reference is worth making now; see [KoReferenceAhead]. */
    fun shouldPrepare(wanted: Boolean, remainingMs: Long): Boolean =
        wanted && NextPrepPolicy.canStartStep(remainingMs)

    suspend fun prepare(context: Context, song: Song, wanted: Boolean, remainingMs: Long) {
        if (!shouldPrepare(wanted, remainingMs)) return
        val started = SystemClock.elapsedRealtime()
        val ready = try {
            withTimeoutOrNull(remainingMs) {
                HqAudioVerify.prepareReferenceAhead(context, song.videoId, song.title)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            TrackLog.d(TAG, "next-prep reference failed: ${failure.message}", about = song.videoId)
            return
        }
        TrackLog.d(
            TAG,
            "next-prep reference: ${when (ready) { true -> "ready"; false -> "none"; null -> "none(deadline)" }} " +
                "in ${SystemClock.elapsedRealtime() - started}ms",
            about = song.videoId,
        )
    }

    private const val TAG = "BitChord"
}
