package com.music.bitchord.ko

import com.music.bitchord.playback.HqSwapPolicy
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.smart.HqVerificationStore
import com.music.bitchord.data.sources.SourceStream

/**
 * [KO] The length a replacement copy has to report before it may keep the track.
 *
 * The outgoing copy's length carried back onto YouTube's clock through the
 * playing verdict, then onto the incoming copy's through its own: a copy whose
 * intro is fifteen seconds shorter than YouTube's is fifteen seconds shorter as
 * a file, and comparing it with the raw length refused it on every attempt
 * (review NR2b-2). The mid-song lossless swap already did this; the audition
 * upgrade and its proof now read the same figure. A copy with no verdict, or a
 * length not known yet, keeps the raw figure.
 */
internal object KoSwapLength {

    fun expectedMs(
        previousDurationMs: Long,
        currentOffsetMs: Long,
        currentRate: Double,
        incoming: HqVerificationStore.Verified?,
    ): Long = if (previousDurationMs <= 0L || incoming == null) {
        previousDurationMs
    } else {
        HqSwapPolicy.swapStartMs(previousDurationMs, currentOffsetMs, currentRate, incoming.offsetMs, incoming.rate)
    }
}

/** [expectedMs][KoSwapLength.expectedMs] for [stream] replacing the copy of [mediaId] playing now. Main thread. */
internal fun PlaybackService.koExpectedSwapLengthMs(mediaId: String, stream: SourceStream, previousDurationMs: Long): Long {
    val playing = koCurrentPlayingVerification(mediaId)
    return KoSwapLength.expectedMs(
        previousDurationMs,
        playing?.offsetMs ?: 0L,
        playing?.rate ?: 1.0,
        HqVerificationStore.get(this, mediaId, stream.sourceConfigId, stream.trackId),
    )
}
