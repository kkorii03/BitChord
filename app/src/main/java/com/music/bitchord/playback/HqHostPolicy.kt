package com.music.bitchord.playback

import kotlinx.coroutines.CompletableDeferred
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * What to do when a stream host answers 429 or 503.
 *
 * The header is believed when it names a delay, in seconds or as an HTTP
 * date. Without one the wait doubles from a second: 1s, 2s, 4s. Three retries
 * is the whole budget; past that the caller has to find another host or let
 * the ordinary failure path take over.
 *
 * Pure so the arithmetic can be tested off-device, and shared by the addon
 * client (JSON endpoints) and the chunked stream data source (audio bytes).
 */
internal object HqRetryPolicy {

    /** How many retries follow the first attempt. */
    const val MAX_ATTEMPTS = 3

    /** How long a host that rate-limited us is left alone. */
    const val REST_MS = 60_000L

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

    /** What a refusal means: whether to retry, how long to wait, and how long to rest the host. */
    data class Decision(val retry: Boolean, val waitMs: Long, val restMs: Long)

    fun decide(header: String?, attempt: Int, nowMs: Long): Decision {
        val stated = retryAfterMs(header, nowMs)
        val wait = stated ?: backoffMs(attempt)
        val retry = attempt < MAX_ATTEMPTS
        return Decision(retry = retry, waitMs = if (retry) wait else 0L, restMs = maxOf(wait, REST_MS))
    }

    /** The `Retry-After` value from a response's headers, case-insensitively. */
    fun retryAfterHeader(headers: Map<String, List<String>>?): String? =
        headers?.entries
            ?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

/**
 * Host-level bookkeeping for high-quality streams.
 *
 * Two jobs, both request-driven rather than timer-driven:
 *
 *  - **One current download per host.** While the track being listened to is
 *    fetching its bytes from a host, the next track's read-ahead may not open
 *    a second request to that same host. It waits on an event that fires when
 *    the current download ends; another host is free to run in parallel.
 *  - **Recent health.** A host that rate-limited us, failed repeatedly, or
 *    delivered bytes very slowly is remembered and sorted behind healthier
 *    ones for the next candidate choice.
 *
 * Nothing here schedules itself: [awaitIdle] suspends on a deferred completed
 * by [end], and the health scores are read only when a caller asks.
 */
internal class HqHostGate(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private class State {
        val active = mutableSetOf<String>()
        var restUntilMs = 0L
        var lastRateLimitedAtMs = 0L
        var successes = 0
        var failures = 0
        var bytes = 0L
        var elapsedMs = 0L
    }

    private val states = mutableMapOf<String, State>()
    private val idle = mutableMapOf<String, CompletableDeferred<Unit>>()

    fun begin(trackKey: String, host: String?) {
        val key = normalize(host) ?: return
        synchronized(this) {
            states.getOrPut(key) { State() }.active += trackKey
        }
    }

    fun end(trackKey: String, host: String?) {
        val key = normalize(host) ?: return
        synchronized(this) {
            val state = states[key] ?: return
            state.active -= trackKey
            signalIdleIfFree(key, state)
        }
    }

    /** Drops every current download except [trackKey]'s — the queue moved on. */
    fun endAllExcept(trackKey: String?) {
        synchronized(this) {
            states.forEach { (host, state) ->
                if (trackKey == null) {
                    state.active.clear()
                } else {
                    state.active.retainAll(setOf(trackKey))
                }
                signalIdleIfFree(host, state)
            }
        }
    }

    fun isBusy(host: String?, exceptTrack: String? = null): Boolean {
        val key = normalize(host) ?: return false
        return synchronized(this) {
            val state = states[key]
            state != null && state.active.any { it != exceptTrack }
        }
    }

    /** Suspends until no download other than [exceptTrack] owns [host]. */
    suspend fun awaitIdle(host: String?, exceptTrack: String? = null) {
        val key = normalize(host) ?: return
        while (true) {
            val signal = synchronized(this) {
                val state = states[key]
                if (state == null || state.active.none { it != exceptTrack }) {
                    null
                } else {
                    idle.getOrPut(key) { CompletableDeferred() }
                }
            } ?: return
            signal.await()
        }
    }

    /** Records a 429/503 and rests the host for at least [HqRetryPolicy.REST_MS]. */
    fun noteRateLimited(host: String?, header: String?, code: Int, nowMs: Long = clock()): Long {
        val key = normalize(host) ?: return 0L
        val decision = HqRetryPolicy.decide(header, HqRetryPolicy.MAX_ATTEMPTS, nowMs)
        synchronized(this) {
            val state = states.getOrPut(key) { State() }
            state.lastRateLimitedAtMs = nowMs
            state.failures += 1
            state.restUntilMs = maxOf(state.restUntilMs, nowMs + decision.restMs)
        }
        return decision.restMs
    }

    fun rest(host: String?, durationMs: Long, nowMs: Long = clock()) {
        val key = normalize(host) ?: return
        synchronized(this) {
            val state = states.getOrPut(key) { State() }
            state.restUntilMs = maxOf(state.restUntilMs, nowMs + durationMs)
        }
    }

    fun isResting(host: String?, nowMs: Long = clock()): Boolean {
        val key = normalize(host) ?: return false
        return synchronized(this) {
            val state = states[key]
            if (state == null) {
                false
            } else if (state.restUntilMs > nowMs) {
                true
            } else {
                if (state.restUntilMs != 0L) state.restUntilMs = 0L
                false
            }
        }
    }

    fun noteTransfer(host: String?, bytes: Long, elapsedMs: Long, ok: Boolean) {
        val key = normalize(host) ?: return
        synchronized(this) {
            val state = states.getOrPut(key) { State() }
            if (ok) {
                state.successes += 1
                state.bytes += bytes.coerceAtLeast(0L)
                state.elapsedMs += elapsedMs.coerceAtLeast(0L)
            } else {
                state.failures += 1
            }
        }
    }

    fun noteFailure(host: String?) {
        val key = normalize(host) ?: return
        synchronized(this) {
            states.getOrPut(key) { State() }.failures += 1
        }
    }

    /**
     * How far behind a healthier host this one should sort. Zero is healthy;
     * larger is worse. Resting hosts are last.
     */
    fun penalty(host: String?, nowMs: Long = clock()): Int {
        val key = normalize(host) ?: return 0
        return synchronized(this) {
            val state = states[key]
            if (state == null) {
                0
            } else if (state.restUntilMs > nowMs) {
                3
            } else {
                var score = 0
                if (state.lastRateLimitedAtMs != 0L &&
                    nowMs - state.lastRateLimitedAtMs < 5 * 60_000L
                ) {
                    score += 2
                }
                val attempts = state.successes + state.failures
                if (attempts >= 3 && state.successes * 2 < attempts) score += 1
                if (state.bytes > 0 && state.elapsedMs > 0) {
                    val bytesPerSecond = state.bytes * 1000L / state.elapsedMs
                    if (bytesPerSecond < 200_000L) score += 1
                }
                score
            }
        }
    }

    internal fun resetForTest() {
        synchronized(this) {
            states.clear()
            idle.clear()
        }
    }

    private fun signalIdleIfFree(host: String, state: State) {
        if (state.active.isEmpty()) idle.remove(host)?.complete(Unit)
    }

    private fun normalize(host: String?): String? =
        host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

/** The process-wide [HqHostGate], plus the URL-to-host helper every caller needs. */
internal object HqHostTracker {

    private val gate = HqHostGate()

    private val AUTHORITY = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)")

    fun hostOf(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val authority = AUTHORITY.find(raw)?.groupValues?.getOrNull(1) ?: return null
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringAfterLast('@').substringBefore(':')
        }
        return host.lowercase().ifBlank { null }?.takeUnless { it == "watch" || it == "source" }
    }

    fun beginDownload(trackKey: String, host: String?) = gate.begin(trackKey, host)

    fun endDownload(trackKey: String, host: String?) = gate.end(trackKey, host)

    fun endAllExcept(trackKey: String?) = gate.endAllExcept(trackKey)

    fun isBusy(host: String?, exceptTrack: String? = null) = gate.isBusy(host, exceptTrack)

    suspend fun awaitIdle(host: String?, exceptTrack: String? = null) =
        gate.awaitIdle(host, exceptTrack)

    fun noteRateLimited(host: String?, header: String?, code: Int): Long =
        gate.noteRateLimited(host, header, code)

    fun rest(host: String?, durationMs: Long) = gate.rest(host, durationMs)

    fun isResting(host: String?) = gate.isResting(host)

    fun noteTransfer(host: String?, bytes: Long, elapsedMs: Long, ok: Boolean) =
        gate.noteTransfer(host, bytes, elapsedMs, ok)

    fun noteFailure(host: String?) = gate.noteFailure(host)

    fun penalty(host: String?) = gate.penalty(host)

    internal fun resetForTest() = gate.resetForTest()
}

/** One already-resolved candidate for continuing a track after its host refused it. */
internal data class HqResumeCandidate(
    val sourceConfigId: String?,
    val trackId: String?,
    val url: String,
    val durationSec: Int? = null,
    val verifiedOffsetMs: Long? = null,
    val verifiedRate: Double? = null,
)

/**
 * Picks the next source that is the *same recording* as the one whose host
 * just refused to keep serving it.
 *
 * The rules are deliberately conservative, because this continues a track
 * mid-play:
 *
 *  - never the source or host that just refused;
 *  - the catalogue length has to agree (the caller already applied that bar);
 *  - when the H1 verification store has an offset for the failed stream, a
 *    candidate with no offset — or a different one — is not the same cut and
 *    must not be spliced in.
 */
internal object HqResumePolicy {

    const val OFFSET_TOLERANCE_MS = 2_000L
    const val RATE_TOLERANCE = 0.02

    fun sameRecording(
        failedOffsetMs: Long?,
        failedRate: Double?,
        candidateOffsetMs: Long?,
        candidateRate: Double?,
    ): Boolean {
        if (failedOffsetMs != null) {
            if (candidateOffsetMs == null) return false
            if (abs(candidateOffsetMs - failedOffsetMs) > OFFSET_TOLERANCE_MS) return false
        }
        if (failedRate != null && candidateRate != null &&
            abs(candidateRate - failedRate) > RATE_TOLERANCE
        ) {
            return false
        }
        return true
    }

    fun choose(
        failedSourceConfigId: String?,
        failedHost: String?,
        failedOffsetMs: Long?,
        failedRate: Double?,
        candidates: List<HqResumeCandidate>,
        hostPenalty: (String?) -> Int = { 0 },
    ): HqResumeCandidate? = candidates
        .asSequence()
        .filter { it.sourceConfigId != failedSourceConfigId }
        .filter { HqHostTracker.hostOf(it.url) != failedHost }
        .filter { sameRecording(failedOffsetMs, failedRate, it.verifiedOffsetMs, it.verifiedRate) }
        .sortedWith(
            compareBy<HqResumeCandidate>(
                { hostPenalty(HqHostTracker.hostOf(it.url)) },
                { if (it.verifiedOffsetMs != null) 0 else 1 },
            ),
        )
        .firstOrNull()
}
