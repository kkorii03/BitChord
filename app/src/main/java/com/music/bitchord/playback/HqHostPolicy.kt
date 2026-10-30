package com.music.bitchord.playback

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.net.HqRetryPolicy
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.StreamFormat
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.abs

/**
 * One host's health, in a form that can outlive the process.
 *
 * Only counts and timestamps: no URL, track, token or credential. The record
 * is what [HqHostTracker] writes to the device, so a server that was asleep or
 * rate limiting yesterday is still sorted behind a healthy one on the first
 * track after a restart.
 */
internal data class HostHealthRecord(
    val restUntilMs: Long = 0L,
    val lastRateLimitedAtMs: Long = 0L,
    val consecutiveRateLimits: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val bytes: Long = 0L,
    val elapsedMs: Long = 0L,
    val responseSamples: Int = 0,
    val totalResponseMs: Long = 0L,
    val lastResponseMs: Long = 0L,
    val lastActivityMs: Long = 0L,
)

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
        var consecutiveRateLimits = 0
        var successes = 0
        var failures = 0
        var bytes = 0L
        var elapsedMs = 0L
        var responseSamples = 0
        var totalResponseMs = 0L
        var lastResponseMs = 0L
        var lastActivityMs = 0L
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

    /**
     * Records a 429/503 and rests the host for at least [HqRetryPolicy.REST_MS].
     *
     * Repeated refusals from the same host double that rest, up to
     * [HqRetryPolicy.MAX_REST_MS]; a server's longer `Retry-After` is still
     * honoured. [noteSuccess] clears the streak.
     */
    fun noteRateLimited(host: String?, header: String?, code: Int, nowMs: Long = clock()): Long {
        val key = normalize(host) ?: return 0L
        val decision = HqRetryPolicy.decide(header, HqRetryPolicy.MAX_ATTEMPTS, nowMs)
        return synchronized(this) {
            val state = states.getOrPut(key) { State() }
            state.lastRateLimitedAtMs = nowMs
            state.lastActivityMs = nowMs
            state.failures += 1
            state.consecutiveRateLimits += 1
            val restMs = HqRetryPolicy.escalatingRestMs(
                consecutiveRateLimits = state.consecutiveRateLimits,
                statedRestMs = decision.restMs,
            )
            state.restUntilMs = maxOf(state.restUntilMs, nowMs + restMs)
            restMs
        }
    }

    /** A request to [host] succeeded: the next refusal starts its rest over. */
    fun noteSuccess(host: String?) {
        val key = normalize(host) ?: return
        synchronized(this) {
            val state = states.getOrPut(key) { State() }
            state.consecutiveRateLimits = 0
            state.successes += 1
            state.lastActivityMs = clock()
        }
    }

    /**
     * One addon JSON round trip: whether it answered, and how long it took.
     *
     * Kept apart from [noteTransfer] on purpose. A manifest or search answer
     * is a few kilobytes, so dividing its size by its latency would report
     * every working addon as a 5 kB/s host and sort it behind one that had
     * never been asked. The average here is the server's response time, which
     * is the figure that decides whether asking it will fit inside the
     * resolver's four-second window.
     */
    fun noteResponse(host: String?, elapsedMs: Long, ok: Boolean, nowMs: Long = clock()) {
        val key = normalize(host) ?: return
        synchronized(this) {
            val state = states.getOrPut(key) { State() }
            val elapsed = elapsedMs.coerceAtLeast(0L)
            state.lastActivityMs = nowMs
            state.responseSamples += 1
            state.totalResponseMs += elapsed
            state.lastResponseMs = elapsed
            if (ok) {
                state.consecutiveRateLimits = 0
                state.successes += 1
            } else {
                state.failures += 1
            }
        }
    }

    fun rest(host: String?, durationMs: Long, nowMs: Long = clock()) {
        val key = normalize(host) ?: return
        synchronized(this) {
            val state = states.getOrPut(key) { State() }
            state.restUntilMs = maxOf(state.restUntilMs, nowMs + durationMs)
            state.lastActivityMs = nowMs
        }
    }

    fun isResting(host: String?, nowMs: Long = clock()): Boolean =
        restRemainingMs(host, nowMs) > 0L

    /** How much longer [host] must rest, or zero when it may be asked again. */
    fun restRemainingMs(host: String?, nowMs: Long = clock()): Long {
        val key = normalize(host) ?: return 0L
        return synchronized(this) {
            val state = states[key] ?: return@synchronized 0L
            if (state.restUntilMs > nowMs) {
                state.restUntilMs - nowMs
            } else {
                if (state.restUntilMs != 0L) state.restUntilMs = 0L
                0L
            }
        }
    }

    fun noteTransfer(host: String?, bytes: Long, elapsedMs: Long, ok: Boolean) {
        val key = normalize(host) ?: return
        synchronized(this) {
            val state = states.getOrPut(key) { State() }
            state.lastActivityMs = clock()
            if (ok) {
                state.consecutiveRateLimits = 0
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
            val state = states.getOrPut(key) { State() }
            state.failures += 1
            state.lastActivityMs = clock()
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
                if (state.responseSamples >= 3 &&
                    state.totalResponseMs / state.responseSamples >= SLOW_RESPONSE_MS
                ) {
                    score += 1
                }
                score
            }
        }
    }

    /** The counts to keep on disk; safe to call from any thread. */
    fun snapshot(): Map<String, HostHealthRecord> = synchronized(this) {
        states.mapValues { (_, state) ->
            HostHealthRecord(
                restUntilMs = state.restUntilMs,
                lastRateLimitedAtMs = state.lastRateLimitedAtMs,
                consecutiveRateLimits = state.consecutiveRateLimits,
                successes = state.successes,
                failures = state.failures,
                bytes = state.bytes,
                elapsedMs = state.elapsedMs,
                responseSamples = state.responseSamples,
                totalResponseMs = state.totalResponseMs,
                lastResponseMs = state.lastResponseMs,
                lastActivityMs = state.lastActivityMs,
            )
        }
    }

    /**
     * Merges a record kept from an earlier process.
     *
     * A live state always wins: the caller may have init'ed after a request
     * had already been counted in this run, and replacing it with yesterday's
     * numbers would erase that evidence. Rows older than [MAX_AGE_MS] are
     * dropped so a server that was bad months ago does not stay last forever.
     */
    fun restore(records: Map<String, HostHealthRecord>, nowMs: Long = clock()) {
        synchronized(this) {
            records.forEach { (host, record) ->
                val key = normalize(host) ?: return@forEach
                if (states.containsKey(key)) return@forEach
                if (record.lastActivityMs == 0L ||
                    nowMs - record.lastActivityMs > MAX_AGE_MS
                ) {
                    return@forEach
                }
                states[key] = State().apply {
                    restUntilMs = record.restUntilMs
                    lastRateLimitedAtMs = record.lastRateLimitedAtMs
                    consecutiveRateLimits = record.consecutiveRateLimits
                    successes = record.successes
                    failures = record.failures
                    bytes = record.bytes
                    elapsedMs = record.elapsedMs
                    responseSamples = record.responseSamples
                    totalResponseMs = record.totalResponseMs
                    lastResponseMs = record.lastResponseMs
                    lastActivityMs = record.lastActivityMs
                }
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

    private companion object {
        /** Three responses averaging this long mean the four-second resolver window is at risk. */
        const val SLOW_RESPONSE_MS = 5_000L

        /** A health record older than this describes a server that may have been fixed since. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/** The process-wide [HqHostGate], plus the URL-to-host helper every caller needs. */
internal object HqHostTracker {

    private val gate = HqHostGate()

    /**
     * Where the counts survive a restart.
     *
     * Null until [init] is called (and in plain JVM tests), which keeps every
     * arithmetic path free of Android. The file holds host names and counts
     * only — never a URL, a token or a track id.
     */
    @Volatile
    private var prefs: SharedPreferences? = null

    private const val PREFS_NAME = "hq_host_health"
    private const val KEY_HOSTS = "hosts"

    /** Called once at startup; event-driven writes only, no timer is installed. */
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        gate.restore(decode(store.getString(KEY_HOSTS, null)))
        prefs = store
    }

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
        gate.noteRateLimited(host, header, code).also { persist() }

    fun noteSuccess(host: String?) {
        gate.noteSuccess(host)
        persist()
    }

    fun noteResponse(host: String?, elapsedMs: Long, ok: Boolean) {
        gate.noteResponse(host, elapsedMs, ok)
        persist()
    }

    fun rest(host: String?, durationMs: Long) {
        gate.rest(host, durationMs)
        persist()
    }

    fun isResting(host: String?) = gate.isResting(host)

    fun restRemainingMs(host: String?) = gate.restRemainingMs(host)

    fun noteTransfer(host: String?, bytes: Long, elapsedMs: Long, ok: Boolean) {
        gate.noteTransfer(host, bytes, elapsedMs, ok)
        persist()
    }

    fun noteFailure(host: String?) {
        gate.noteFailure(host)
        persist()
    }

    fun penalty(host: String?) = gate.penalty(host)

    internal fun snapshotForTest(): Map<String, HostHealthRecord> = gate.snapshot()

    internal fun resetForTest() {
        gate.resetForTest()
        prefs?.edit()?.remove(KEY_HOSTS)?.apply()
    }

    private fun persist() {
        val store = prefs ?: return
        // A long session can meet many CDN hosts. The store is a sorting hint,
        // not an audit log, so only the most recently seen are worth keeping.
        val recent = gate.snapshot().entries
            .sortedByDescending { it.value.lastActivityMs }
            .take(MAX_PERSISTED_HOSTS)
            .associate { it.key to it.value }
        store.edit().putString(KEY_HOSTS, encode(recent)).apply()
    }

    /** Enough for every addon and streaming host one listener is likely to use. */
    private const val MAX_PERSISTED_HOSTS = 128

    /**
     * A line per host, tab-separated. Hosts cannot contain a tab or newline,
     * so no escaping is needed; an entry with the wrong field count is simply
     * skipped rather than taking the rest of the file down.
     */
    private fun encode(records: Map<String, HostHealthRecord>): String =
        records.entries.joinToString("\n") { (host, r) ->
            listOf(
                host,
                r.restUntilMs,
                r.lastRateLimitedAtMs,
                r.consecutiveRateLimits,
                r.successes,
                r.failures,
                r.bytes,
                r.elapsedMs,
                r.responseSamples,
                r.totalResponseMs,
                r.lastResponseMs,
                r.lastActivityMs,
            ).joinToString("\t")
        }

    private fun decode(raw: String?): Map<String, HostHealthRecord> {
        val text = raw?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return buildMap {
            text.lineSequence().forEach { line ->
                val parts = line.split('\t')
                if (parts.size < 12) return@forEach
                val host = parts[0].trim().lowercase().takeIf { it.isNotEmpty() } ?: return@forEach
                put(
                    host,
                    HostHealthRecord(
                        restUntilMs = parts[1].toLongOrNull() ?: 0L,
                        lastRateLimitedAtMs = parts[2].toLongOrNull() ?: 0L,
                        consecutiveRateLimits = parts[3].toIntOrNull() ?: 0,
                        successes = parts[4].toIntOrNull() ?: 0,
                        failures = parts[5].toIntOrNull() ?: 0,
                        bytes = parts[6].toLongOrNull() ?: 0L,
                        elapsedMs = parts[7].toLongOrNull() ?: 0L,
                        responseSamples = parts[8].toIntOrNull() ?: 0,
                        totalResponseMs = parts[9].toLongOrNull() ?: 0L,
                        lastResponseMs = parts[10].toLongOrNull() ?: 0L,
                        lastActivityMs = parts[11].toLongOrNull() ?: 0L,
                    ),
                )
            }
        }
    }
}

/** One already-resolved candidate for continuing a track after its host refused it. */
internal data class HqResumeCandidate(
    val sourceConfigId: String?,
    val trackId: String?,
    val url: String,
    val durationSec: Int? = null,
    val verifiedOffsetMs: Long? = null,
    val verifiedRate: Double? = null,
    val format: StreamFormat? = null,
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

    /**
     * Whether a replacement is at the quality already chosen or better.
     *
     * A missing side is not evidence of a downgrade, so it is left to the
     * other rules rather than rejected here. Both known sides use the same
     * ladder as every other quality decision, so a lossy copy cannot replace a
     * lossless one just because its host is healthy.
     */
    fun sameOrBetterRendition(failed: StreamFormat?, candidate: StreamFormat?): Boolean {
        if (failed == null || candidate == null) return true
        return HqQuality.isSameOrBetter(candidate, failed)
    }

    fun choose(
        failedSourceConfigId: String?,
        failedHost: String?,
        failedOffsetMs: Long?,
        failedRate: Double?,
        candidates: List<HqResumeCandidate>,
        hostPenalty: (String?) -> Int = { 0 },
        failedFormat: StreamFormat? = null,
    ): HqResumeCandidate? = candidates
        .asSequence()
        .filter { it.sourceConfigId != failedSourceConfigId }
        .filter { HqHostTracker.hostOf(it.url) != failedHost }
        .filter { sameRecording(failedOffsetMs, failedRate, it.verifiedOffsetMs, it.verifiedRate) }
        .filter { sameOrBetterRendition(failedFormat, it.format) }
        .sortedWith(
            compareBy<HqResumeCandidate>(
                { hostPenalty(HqHostTracker.hostOf(it.url)) },
                { if (it.verifiedOffsetMs != null) 0 else 1 },
            ),
        )
        .firstOrNull()
}
