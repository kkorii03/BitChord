package com.music.bitchord.playback

import com.music.bitchord.data.innertube.UnplayableReason

/**
 * What a failed playback attempt should do next.
 *
 * Kept as a pure decision so it can be tested without Android: the caller
 * supplies the resolver's verdict (a definitive "nobody can serve this" rather
 * than a transient network fault) and whether another upload of the same
 * recording was found. A definitive refusal must never spend the retry budget
 * on the same id again.
 */
internal enum class UnplayableRecoveryAction {
    /** Transient: keep the existing recover-and-retry path. */
    RETRY,

    /** Definitive, and another version of the recording is available. */
    SWAP_ALTERNATE,

    /** Definitive, and there is nothing else to play. */
    SKIP_NEXT,
}

internal object UnplayableRecovery {
    /**
     * Whether the failure is a definitive refusal — all clients UNPLAYABLE,
     * a stream-resolver "unavailable", LOGIN_REQUIRED or a region block — as
     * opposed to a timeout, a dropped socket, or a 403 on a URL that may have
     * expired.
     */
    fun isDefinitive(permanentReason: String?, alreadyMarkedUnplayable: Boolean): Boolean =
        alreadyMarkedUnplayable || UnplayableReason.isDefinitive(permanentReason)

    fun decide(definitive: Boolean, hasAlternate: Boolean): UnplayableRecoveryAction = when {
        definitive && hasAlternate -> UnplayableRecoveryAction.SWAP_ALTERNATE
        definitive -> UnplayableRecoveryAction.SKIP_NEXT
        else -> UnplayableRecoveryAction.RETRY
    }

    /**
     * How far a run of definitive refusals may carry the queue before it
     * stops. Verdicts are normally allowed to walk straight through a queue
     * of region-locked or age-gated tracks, but a repeated queue row (or
     * repeat-one) gives the same media id back after every skip, and without a
     * bound that is an endless error/skip loop rather than progress. The
     * allowance is deliberately larger than [MAX_CONSECUTIVE_SKIPS]: a whole
     * album of unavailable rows is still a run a listener expects to pass.
     */
    const val MAX_CONSECUTIVE_VERDICT_SKIPS = 20

    /** Whether one more definitive skip is still progress rather than a loop. */
    fun allowsVerdictSkip(consecutiveVerdictSkips: Int): Boolean =
        consecutiveVerdictSkips < MAX_CONSECUTIVE_VERDICT_SKIPS
}
