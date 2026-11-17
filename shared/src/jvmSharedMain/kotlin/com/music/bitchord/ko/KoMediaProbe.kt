package com.music.bitchord.ko

import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * [KO] review T1-A: a media probe that googlevideo answered, but not with
 * media for now.
 *
 * Since review T1 a proxy walk whose media probe "failed" counts the route as
 * stalled, and a walk with no stream then reaches
 * `ProxyHealthMonitor.recordUnreachable`: with no backup the proxy is bypassed
 * for five minutes. That is right for a probe that timed out or lost its
 * connection, but a 429, a 503 or a 500 is an HTTP answer that travelled the
 * whole route (inside the CONNECT tunnel, where the proxy cannot write one;
 * the proxy's own refusal of the tunnel arrives as a transport IOException).
 * So the proxy is up, and the answer is about this URL for now, not about the
 * recording either: such a walk ends as a transient failure that neither
 * marks the proxy down nor is stored as an unplayable verdict.
 */
internal object KoMediaProbe {

    /**
     * Gateway errors keep the T1 reading (the bytes leg failing past the
     * proxy), as the review asked; see `ship/review-1115/T17.md`.
     */
    private val GATEWAY_CODES = setOf(502, 504)

    /**
     * Whether a probe's HTTP [code] is googlevideo's back-pressure: 429, 503,
     * or any other 5xx except a gateway's 502/504.
     */
    fun isTransientCode(code: Int): Boolean =
        code == 429 || (code in 500..599 && code !in GATEWAY_CODES)

    /**
     * The failure for a proxy walk that found no stream although a probe on it
     * got a transient answer, or null. It wins over every other reading of
     * the walk: a minted URL that googlevideo only asked to wait on means the
     * track plays through this route, so a stored verdict would block a
     * playable track for ten minutes, and the answer itself proves the route
     * carries bytes. The message names neither the proxy nor a timeout, so
     * `ProxyFailure.isProxyDown` does not match it, and `UnplayableReason`
     * reads it as transient (it carries "429"/"503").
     */
    fun transientFailure(mediaTransient: Boolean): IOException? =
        if (mediaTransient) MediaProbeTransientException(TRANSIENT_MESSAGE) else null

    /** Whether [failure] (or a wrapper of it, e.g. a coroutine stack-trace copy) is [transientFailure]'s. */
    fun isTransient(failure: Throwable?): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause = failure
        while (cause != null && seen.add(cause)) {
            if (cause is MediaProbeTransientException) return true
            cause = cause.cause
        }
        return false
    }

    /** No videoId on purpose: a digit run such as "407" in one would read as a proxy failure. */
    private const val TRANSIENT_MESSAGE =
        "googlevideo answered the media probe with back-pressure (HTTP 429/503/5xx); try again later"
}

/** [KO] review T1-A: see [KoMediaProbe.transientFailure]. */
internal class MediaProbeTransientException(message: String) : IOException(message)
