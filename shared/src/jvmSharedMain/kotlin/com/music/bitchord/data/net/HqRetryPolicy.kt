package com.music.bitchord.data.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * What to do when a stream host answers 429 or 503.
 *
 * The header is believed when it names a delay, in seconds or as an HTTP
 * date. Without one the wait doubles from a second: 1s, 2s, 4s. Three retries
 * is the whole budget; past that the caller has to find another host or let
 * the ordinary failure path take over.
 *
 * A `Retry-After` longer than [MAX_WAIT_MS] is not waited out at all: holding
 * ExoPlayer's loader thread for minutes is indistinguishable from a hung
 * track, and the caller can spend that time walking another client or the
 * proxy instead. The host is still rested for as long as it asked, so later
 * requests steer around it.
 *
 * Pure so the arithmetic can be tested off-device, and shared by the addon
 * client (JSON endpoints) and the chunked stream data source (audio bytes).
 */
object HqRetryPolicy {

    /** How many retries follow the first attempt. */
    const val MAX_ATTEMPTS = 3

    /** How long a host that rate-limited us is left alone. */
    const val REST_MS = 60_000L

    /**
     * Ceiling for the doubling below. A host that keeps refusing gets longer
     * and longer to recover, but never more than ten minutes of being skipped
     * on every later request.
     */
    const val MAX_REST_MS = 10 * 60_000L

    /**
     * The longest a loader thread may be held on a `Retry-After`.
     *
     * A stated delay past this is treated as "come back later": the request
     * fails immediately so recovery can try a different route, while the host
     * is rested for the server's own interval.
     */
    const val MAX_WAIT_MS = 8_000L

    fun isRateLimited(code: Int): Boolean = code == 429 || code == 503

    /**
     * A gateway answer that usually means a free-tier instance is asleep or
     * waking rather than a limit on how often it may be asked.
     *
     * `502`/`504` are the edge failing to reach the instance; `503` is the
     * instance itself saying it is not ready. Sending the request again while
     * the container is still booting only queues more work for the same cold
     * start, so the addon path wakes it once and leaves it alone.
     */
    fun isSleepingGateway(code: Int): Boolean = code == 502 || code == 503 || code == 504

    /**
     * What an addon JSON call should do with [code].
     *
     * A sleeping gateway gets exactly one request: no in-call retry, and a
     * host rest long enough for later callers to steer around it. A `429` is
     * still the loader's ordinary ladder because the server is awake and
     * merely asking for spacing.
     */
    fun decideAddon(code: Int, header: String?, attempt: Int, nowMs: Long): Decision =
        if (isSleepingGateway(code)) {
            val rest = decide(header, MAX_ATTEMPTS, nowMs)
            Decision(retry = false, waitMs = 0L, restMs = rest.restMs)
        } else {
            decide(header, attempt, nowMs)
        }

    /** `Retry-After` as milliseconds from [nowMs], or null when it did not say. */
    fun retryAfterMs(header: String?, nowMs: Long): Long? {
        val text = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = statedSeconds(text)
        if (seconds != null) {
            if (seconds <= 0L) return 0L
            // A value this build cannot express in milliseconds is not a
            // small wait; null keeps it out of the arithmetic entirely.
            if (seconds > Long.MAX_VALUE / 1000L) return null
            return seconds * 1000L
        }
        val at = runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: return null
        return (at - nowMs).coerceAtLeast(0L)
    }

    private fun statedSeconds(text: String): Long? =
        text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()

    /**
     * Whether the header names a wait past [MAX_WAIT_MS], including a value
     * too large to represent as milliseconds. [retryAfterMs] returns null for
     * that overflow, so [decide] checks the seconds themselves rather than
     * treating the absence as "no instruction".
     */
    private fun retryAfterIsTooLong(header: String?): Boolean {
        val text = header?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val seconds = statedSeconds(text) ?: return false
        return seconds > MAX_WAIT_MS / 1000L
    }

    /** 1s, 2s, 4s — the wait before retry [attempt] (0 is the first retry). */
    fun backoffMs(attempt: Int): Long = 1_000L shl attempt.coerceIn(0, MAX_ATTEMPTS - 1)

    /**
     * How long to leave a host alone after its [consecutiveRateLimits]-th
     * refusal in a row.
     *
     * The first refusal rests for [REST_MS]; each further one doubles that,
     * up to [MAX_REST_MS]. A server's own longer `Retry-After` is still
     * believed: [statedRestMs] wins when it is past the escalation. A success
     * resets the caller's streak, so a recovered host starts at [REST_MS]
     * again rather than carrying the old penalty forever.
     */
    fun escalatingRestMs(consecutiveRateLimits: Int, statedRestMs: Long = 0L): Long {
        val streak = consecutiveRateLimits.coerceAtLeast(1)
        val doublings = (streak - 1).coerceIn(0, 20)
        val escalated = REST_MS * (1L shl doublings)
        return maxOf(statedRestMs, escalated.coerceAtMost(MAX_REST_MS))
    }

    /**
     * What a refusal means: whether to retry, how long to wait, and how long
     * to rest the host.
     *
     * [retryAfterTooLong] distinguishes "the retry budget is spent" from "the
     * server asked for longer than a loader thread may sleep"; both leave
     * [retry] false, but only the latter carries a useful log line.
     */
    data class Decision(
        val retry: Boolean,
        val waitMs: Long,
        val restMs: Long,
        val retryAfterTooLong: Boolean = false,
    )

    fun decide(header: String?, attempt: Int, nowMs: Long): Decision {
        val stated = retryAfterMs(header, nowMs)
        val tooLong = retryAfterIsTooLong(header) ||
            (stated != null && stated > MAX_WAIT_MS)
        val wait = stated ?: backoffMs(attempt)
        val retry = attempt < MAX_ATTEMPTS && !tooLong
        return Decision(
            retry = retry,
            waitMs = if (retry) wait.coerceAtMost(MAX_WAIT_MS) else 0L,
            restMs = maxOf(wait, REST_MS),
            retryAfterTooLong = tooLong,
        )
    }

    /** The `Retry-After` value from a response's headers, case-insensitively. */
    fun retryAfterHeader(headers: Map<String, List<String>>?): String? =
        headers?.entries
            ?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}
