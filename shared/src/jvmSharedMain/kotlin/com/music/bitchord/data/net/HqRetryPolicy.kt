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
     * The longest a loader thread may be held on a `Retry-After`.
     *
     * A stated delay past this is treated as "come back later": the request
     * fails immediately so recovery can try a different route, while the host
     * is rested for the server's own interval.
     */
    const val MAX_WAIT_MS = 8_000L

    fun isRateLimited(code: Int): Boolean = code == 429 || code == 503

    /** `Retry-After` as milliseconds from [nowMs], or null when it did not say. */
    fun retryAfterMs(header: String?, nowMs: Long): Long? {
        val text = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
        if (seconds != null) return (seconds * 1000L).coerceAtLeast(0L)
        val at = runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: return null
        return (at - nowMs).coerceAtLeast(0L)
    }

    /** 1s, 2s, 4s — the wait before retry [attempt] (0 is the first retry). */
    fun backoffMs(attempt: Int): Long = 1_000L shl attempt.coerceIn(0, MAX_ATTEMPTS - 1)

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
        val tooLong = stated != null && stated > MAX_WAIT_MS
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
