package com.music.bitchord.data.sources

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
        val kbps = format?.kbps?.takeIf { it > 0 }
            ?: return UNSTATED_KBPS_MAX_BYTES
        val bytes = kbps.toDouble() * 1_000.0 / 8.0 * seconds * HEADROOM_FRACTION
        return bytes.toLong().coerceAtLeast(1L)
    }

    /** Bytes fetched for a source that never stated a bitrate (a FLAC-sized head). */
    const val UNSTATED_KBPS_MAX_BYTES = 40L * 1024L * 1024L

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
}
