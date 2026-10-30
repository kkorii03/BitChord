package com.music.bitchord.data.sources

import com.music.bitchord.data.net.HqRetryPolicy

/**
 * The pure half of the high-quality verification gates.
 *
 * A manual "Upgrade quality" request is the listener asking for an answer now:
 * it must not be turned away by the automatic path's retry cooldown, and a
 * reference that failed to open earlier in this play is worth trying again
 * rather than being remembered as a permanent no.
 *
 * A missing reference is also not a verdict about the candidate. Only a real
 * measurement that fell below [HqMatchPolicy]'s bar may be stored as a
 * rejection; otherwise a slow network would teach the app that a perfectly good
 * recording is a cover.
 */
object HqVerifyPolicy {

    /**
     * How many bytes of the candidate's opening to fetch for a comparison.
     *
     * A source that states its bitrate gets the arithmetic for [seconds] of
     * that stream plus [HEADROOM_FRACTION] to absorb container overhead. A
     * source that states nothing is assumed to be a FLAC at its worst
     * reasonable size and capped at [UNSTATED_KBPS_MAX_BYTES]; fetching more
     * than the comparison can use only spends data and disk.
     */
    fun bytesToFetch(format: StreamFormat?, seconds: Double): Long {
        if (seconds <= 0.0 || !seconds.isFinite()) return 0L
        // A lossless stream's real rate is the PCM rate less what FLAC saves,
        // and an inferred figure (a module mapping "HIGH" to 320) is no rate at
        // all; either way the stated number would fetch too short a head and
        // leave the pair unmeasurable on every attempt.
        val pcmKbps = if (format?.isLossless == true) {
            (format.sampleRateHz ?: 44_100).toLong() * (format.bitDepth ?: 16) * 2 / 1_000
        } else {
            null
        }
        val stated = format?.kbps?.takeIf { it > 0 && !format.kbpsInferred }?.toLong()
        val kbps = listOfNotNull(stated, pcmKbps).maxOrNull()
            ?: return UNSTATED_KBPS_MAX_BYTES
        val bytes = kbps.toDouble() * 1_000.0 / 8.0 * seconds * HEADROOM_FRACTION
        return bytes.toLong().coerceIn(1L, LOSSLESS_MAX_BYTES)
    }

    /** Bytes fetched for a source that never stated a bitrate (a FLAC-sized head). */
    const val UNSTATED_KBPS_MAX_BYTES = 40L * 1024L * 1024L

    /** Ceiling for any fetch: ninety-odd seconds of 24-bit/192 kHz stereo PCM rate. */
    const val LOSSLESS_MAX_BYTES = 120L * 1024L * 1024L

    /** Extra bytes for container headers and a little slack at the tail. */
    const val HEADROOM_FRACTION = 1.2

    /**
     * How long a manual request still has to wait, or zero when it may start.
     *
     * [manual] is the whole point: an explicit request ignores the automatic
     * retry cooldown. Null [lastAcceptedAtMs] means nothing has been accepted
     * yet, so there is no wait to charge.
     */
    fun cooldownRemaining(
        nowMs: Long,
        lastAcceptedAtMs: Long?,
        cooldownMs: Long,
        manual: Boolean,
    ): Long {
        if (manual || lastAcceptedAtMs == null || cooldownMs <= 0L) return 0L
        return (cooldownMs - (nowMs - lastAcceptedAtMs)).coerceAtLeast(0L)
    }

    /**
     * Whether a failed comparison belongs in [HqVerificationStore] as a
     * rejection.
     *
     * [referenceAvailable] is false when the YouTube reference could not be
     * opened, decoded or reached in time. That is a failure of this attempt,
     * not evidence about the candidate, so it is never persisted. Only a
     * measured mismatch is.
     *
     * [measured] is false when the candidate itself could not be decoded or
     * its opening was too short to compare. An unmeasurable candidate is
     * Unavailable, not a rejection: the same URL may work on the next play, and
     * a stored rejection would hide it forever.
     */
    fun shouldPersistRejection(
        referenceAvailable: Boolean,
        accepted: Boolean,
        measured: Boolean = true,
    ): Boolean = measured && referenceAvailable && !accepted

    /** A remembered missing reference may only short-circuit an automatic attempt. */
    fun shouldRetryMissingReference(manual: Boolean): Boolean = manual

    /**
     * What to do after a candidate download is rate limited.
     *
     * Verification runs beside playback, so it may wait longer than a loader
     * thread: up to [MAX_CANDIDATE_RETRY_WAIT_MS]. It still retries only once —
     * a second refusal is the server saying the same thing, and the attempt
     * stays unmeasured rather than becoming a stored rejection.
     */
    data class CandidateRetry(val retry: Boolean, val waitMs: Long)

    fun retryCandidateDownload(
        retryAfterHeader: String?,
        attempt: Int,
        nowMs: Long,
    ): CandidateRetry {
        if (attempt >= MAX_CANDIDATE_RETRIES) return CandidateRetry(retry = false, waitMs = 0L)
        val stated = HqRetryPolicy.retryAfterMs(retryAfterHeader, nowMs)
        // No Retry-After (Cloudflare in front of Monochrome sends none): the
        // loader's one-second backoff came back 429 every time on the phone
        // (10-26, 22:23-22:30, 14 of 14). A refusal from that edge lasts tens
        // of seconds, so the one retry waits long enough to mean something.
        val wait = (stated ?: UNSTATED_RETRY_WAIT_MS)
            .coerceIn(0L, MAX_CANDIDATE_RETRY_WAIT_MS)
        return CandidateRetry(retry = true, waitMs = wait)
    }

    /**
     * The order to try a song's candidates in when some of their hosts are
     * resting after a 429.
     *
     * Every candidate on a healthy host comes first, in the caller's original
     * order; candidates on resting hosts come last. A song with another addon
     * answer therefore gets that answer tried before it waits for the tired
     * host, and a song whose only answer is resting keeps its one candidate
     * rather than losing it. Pure so the ordering can be tested without a
     * server or a clock.
     */
    fun candidateOrder(
        hosts: List<String?>,
        isResting: (String?) -> Boolean,
    ): List<Int> {
        val healthy = hosts.indices.filterNot { isResting(hosts[it]) }
        val resting = hosts.indices.filter { isResting(hosts[it]) }
        return healthy + resting
    }

    /** One retry only: no loop, and no timer beyond the server's own wait. */
    const val MAX_CANDIDATE_RETRIES = 1

    /** Verification may sleep longer than a loader thread, but never a minute. */
    const val MAX_CANDIDATE_RETRY_WAIT_MS = 60_000L

    /** The wait when the server says nothing about how long to stay away. */
    const val UNSTATED_RETRY_WAIT_MS = 20_000L
}
