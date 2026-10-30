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
}
