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
     */
    fun shouldPersistRejection(referenceAvailable: Boolean, accepted: Boolean): Boolean =
        referenceAvailable && !accepted

    /** A remembered missing reference may only short-circuit an automatic attempt. */
    fun shouldRetryMissingReference(manual: Boolean): Boolean = manual
}
