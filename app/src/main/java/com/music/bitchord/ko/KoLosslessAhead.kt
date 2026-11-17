package com.music.bitchord.ko

import com.music.bitchord.playback.smart.HqVerificationStore
import kotlin.math.abs

/**
 * [KO] hq-lossless (review NR-2)
 *
 * Whether automix may open the next song's verified lossless copy directly in
 * the standby player.
 *
 * Every number the blend uses for the incoming side (the cue it opens at, its
 * beat grid, its mix-in point) was measured on the YouTube copy
 * (TrackAnalyzer analyses only the canonical YouTube rendition). The lossless
 * copy runs on its own clock, `candidate = offset + rate x youtube`
 * ([com.music.bitchord.playback.HqSwapPolicy.targetPositionMs]). Opened at the
 * YouTube cue, a copy with an offset starts early or late, its beats sit off
 * the outgoing song's, and its first word can be clipped.
 *
 * So the copy is opened ahead only when its clock is YouTube's within what a
 * blend cannot hear. Anything else keeps the YouTube copy in the standby; the
 * ordinary mid-song upgrade, which maps the position through the verdict
 * ([com.music.bitchord.playback.HqSwapPolicy.swapStartMs]), still moves it to
 * the lossless copy after the blend. A copy with no stored verdict has no known
 * clock and is not opened ahead either.
 */
internal object KoLosslessAhead {
    /** Below one 60 fps frame; a cue or beat this far off is not heard in a blend. */
    const val MAX_OFFSET_MS = 30L

    /** 0.1%: 30 ms of drift by 30 s into the song, the far end of a long blend. */
    const val MAX_RATE_ERROR = 0.001

    fun cueSafe(verified: HqVerificationStore.Verified?): Boolean {
        val v = verified ?: return false
        if (!v.rate.isFinite()) return false
        return abs(v.offsetMs) <= MAX_OFFSET_MS && abs(v.rate - 1.0) <= MAX_RATE_ERROR
    }
}
