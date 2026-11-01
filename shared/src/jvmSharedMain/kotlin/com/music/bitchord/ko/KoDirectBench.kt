package com.music.bitchord.ko

import com.music.bitchord.data.innertube.UnplayableReason

/**
 * Whether a stream-resolver refusal should count against the path that made
 * the request, as opposed to naming the recording itself.
 *
 * The path bench exists for a path that is useless right now: a region or
 * Premium gate, a bot challenge, a timeout or an HTTP failure is about the
 * request, so those still count. A claim, a deletion, a private upload or the
 * player's own "UNPLAYABLE"/"track unavailable" answer is the recording's
 * verdict and says nothing about the path; counting one would bench the direct
 * path for the next playable track. FIXLIST 80: after one copyright-blocked
 * track the direct path sat out, and the next track started on the slow
 * default walk (8.6 s to first sound).
 */
internal object KoDirectBench {

    fun countsAgainstPath(reason: String?): Boolean {
        val text = reason?.trim().orEmpty()
        if (text.isEmpty()) return true
        if (UnplayableReason.isRegionGate(text)) return true
        if (UnplayableReason.isBotChallenge(text)) return true
        return !UnplayableReason.isDefinitive(text)
    }
}
