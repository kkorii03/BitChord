package com.music.bitchord.playback.smart

/**
 * When a song check that failed in transport may be asked once more, and when a
 * hearing request is still worth sending. Pure rules; the callers hold the state.
 *
 * 11-08 5cBNNlHoPSg: every request on the emulator failed for 8 s, the check's
 * hearing came back with no HTTP reply at all, and the check stayed spent for the
 * playback, so the song was never measured.
 */
internal object SongCheckRetry {

    /** No reply and no words: the request never reached an answer. A real "no match" has a code or words. */
    fun transportFailure(httpCode: Int?): Boolean = httpCode == null

    /**
     * One re-ask per track, on the next event that brings audio: not before the
     * failure, never a second time, only for the same track and when heavy work
     * (low power, quotas) is allowed.
     */
    fun mayRetry(
        pendingFor: String?,
        checkKey: String,
        retriesDone: Int,
        sameTrack: Boolean,
        heavyAllowed: Boolean,
    ): Boolean = pendingFor != null && pendingFor == checkKey && retriesDone < 1 && sameTrack && heavyAllowed

    /**
     * A hearing request for [videoId] is worth sending while it is the playing or
     * the next row. When neither is known yet (a track is starting) it is.
     */
    fun hearingWanted(videoId: String, currentId: String?, nextId: String?): Boolean =
        (currentId == null && nextId == null) || LyricsTiming.measurementWanted(videoId, currentId, nextId)
}
