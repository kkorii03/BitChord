package com.music.bitchord.data.net

/**
 * The rules for the automatic high-quality pass's downloads.
 *
 * Unlike a tap, nobody is waiting for these: a 429 from the catalogue is a
 * reason to come back later, not a failed download to put in front of the
 * listener. The waits below are deliberately long — the Monochrome edge in
 * front of the pass answered four tracks inside four seconds on 10-27 — and
 * they are scheduled through WorkManager rather than slept in-process.
 *
 * Pure so the arithmetic and the "is this worth showing" question can be
 * tested off-device.
 */
object HqAutoDownloadPolicy {

    /** One automatic target at a time, whichever hosts its plan names. */
    const val MAX_CONCURRENT = 1

    /** And one request per host, shared with playback's own host gate. */
    const val MAX_CONCURRENT_PER_HOST = 1

    /** First wait when a refusal names none. */
    const val BASE_RETRY_MS = 60_000L

    /** The exponential ceiling; a server may still name a longer Retry-After. */
    const val MAX_RETRY_MS = 30 * 60_000L

    private val HTTP_STATUS = Regex("HTTP\\s*(\\d{3})", RegexOption.IGNORE_CASE)

    private val TRANSIENT_MARKERS = listOf(
        "stream was reset",
        "internal_error",
        "connection reset",
        "reset by peer",
        "unexpected end of stream",
        "broken pipe",
        "timed out",
        "timeout",
    )

    /** 1m, 2m, 4m … capped at [MAX_RETRY_MS]. */
    fun backoffMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 5)
        return (BASE_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    /**
     * How long before retrying a refused automatic download.
     *
     * A stated `Retry-After` is obeyed as sent. Without one the wait doubles
     * from a minute; [attempt] is how many times this target has already been
     * deferred, so the first retry is one minute and the fourth is eight.
     */
    fun retryDelayMs(retryAfterHeader: String?, attempt: Int, nowMs: Long): Long {
        val stated = HqRetryPolicy.retryAfterMs(retryAfterHeader, nowMs)
        if (stated != null) return stated.coerceAtLeast(0L)
        return backoffMs(attempt)
    }

    /** The HTTP status buried in a download error message, when there is one. */
    fun statusCode(message: String?): Int? =
        HTTP_STATUS.find(message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * Whether a failure is the server asking for a pause rather than a verdict
     * about the file. A reset stream is included: the bytes did not arrive, but
     * nothing about the recording was proved wrong.
     */
    fun isTransientFailure(code: Int?, message: String?): Boolean {
        if (code != null && HqRetryPolicy.isRateLimited(code)) return true
        val text = message?.lowercase() ?: return false
        if (statusCode(text)?.let(HqRetryPolicy::isRateLimited) == true) return true
        return TRANSIENT_MARKERS.any(text::contains)
    }

    /** A user sees only a download they asked for; the automatic pass stays quiet. */
    fun countsAsUserFailure(automatic: Boolean): Boolean = !automatic

    /**
     * Whether an automatic target may start now. A user-requested download is
     * never held back by this; playback is the reason an automatic one waits,
     * and it is woken by the pause event rather than by a poll.
     */
    fun shouldStartNow(
        automatic: Boolean,
        playbackActive: Boolean,
        hostBusy: Boolean,
        autoRunning: Int,
    ): Boolean {
        if (!automatic) return true
        return !playbackActive && !hostBusy && autoRunning < MAX_CONCURRENT
    }
}
