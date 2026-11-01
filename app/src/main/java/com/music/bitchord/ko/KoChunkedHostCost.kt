package com.music.bitchord.ko

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.net.HqRetryPolicy
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Whether [e] is a cancelled transfer rather than a failed one.
 *
 * A cancelled read-ahead arrives as an [InterruptedIOException] and must not
 * count against the host. A [SocketTimeoutException] also extends
 * [InterruptedIOException], but a timeout is the network failing: leaving it
 * out of the failure record let a dead host keep its place, and [close] then
 * credited it with a success.
 */
internal fun isTransferCancellation(e: Throwable): Boolean =
    e is InterruptedIOException && e !is SocketTimeoutException

/**
 * [KO] hq-host-cost
 *
 * Keeps the per-open host accounting and the rate-limit retry that the fork
 * added to [ChunkedDataSource]. The upstream data source still owns the byte
 * ranges; this only records which host served them and how a refusal was
 * counted.
 */
internal class KoChunkedHostCost(private val upstream: DataSource) {

    /** The host currently being read, and what it has cost, for [HqHostTracker]. */
    private var currentHost: String? = null
    private var openedAtMs = 0L
    private var bytesRead = 0L

    /**
     * Set when any part of this open failed before the caller was done with it.
     *
     * [close] used to report every open as a successful transfer, so a host
     * that answered 403 or truncated a range still gained throughput credit
     * and sorted ahead of a host that had actually served the bytes.
     */
    private var transferFailed = false

    /** True when [HqHostTracker.noteFailure] already recorded this failure. */
    private var failureNoted = false

    /** The last exception [openReplacement] saw, for the recovery loop. */
    var lastOpenFailure: Exception? = null

    fun beginOpen(uri: String) {
        currentHost = HqHostTracker.hostOf(uri)
        openedAtMs = System.currentTimeMillis()
        bytesRead = 0L
        transferFailed = false
        failureNoted = false
    }

    fun readPassthrough(read: () -> Int): Int {
        val result = try {
            read()
        } catch (e: Exception) {
            noteReadFailure(e)
            throw e
        }
        if (result > 0) bytesRead += result
        return result
    }

    fun readChunk(read: () -> Int): Int {
        val result = try {
            read()
        } catch (e: Exception) {
            noteReadFailure(e)
            throw e
        }
        if (result > 0) bytesRead += result
        return result
    }

    private fun noteReadFailure(e: Exception) {
        if (!isTransferCancellation(e)) {
            transferFailed = true
            HqHostTracker.noteFailure(currentHost)
            failureNoted = true
        }
    }

    fun noteFailedOpen(e: Exception) {
        transferFailed = true
        // A 429/502/503/504 was already counted by the gate; a second note
        // here penalised the same host twice.
        val refusal = rateLimitedCause(e)
        val countedByGate = refusal != null &&
            (HqRetryPolicy.isRateLimited(refusal.responseCode) ||
                HqRetryPolicy.isSleepingGateway(refusal.responseCode))
        if (!countedByGate) HqHostTracker.noteFailure(currentHost)
        failureNoted = true
    }

    fun markRecovered() {
        // Recovered from the refusal: this open is not a host failure.
        transferFailed = false
    }

    /**
     * Credits the rest of this open to the host that actually served the
     * replacement.
     *
     * The original URL was refused, so [finish] must not hand its replacement's
     * throughput to the dead host. The bytes already read before the refusal
     * stay with the refused host (which earned a failure, not credit), so the
     * replacement's accounting starts fresh.
     */
    fun adoptRecoveredHost(url: String) {
        currentHost = HqHostTracker.hostOf(url)
        openedAtMs = System.currentTimeMillis()
        bytesRead = 0L
    }

    fun failTruncated(position: Long, missing: Long): Nothing {
        // Every re-open at this position also ended before the bytes that were
        // asked for. Reporting EOF here is what made a cut stream look like a
        // finished song: ExoPlayer advanced the queue with no error and no
        // recovery. A short read is a transport failure, not the end of the
        // track, because `clen` already said how many bytes there are.
        val message = "truncated stream at $position ($missing bytes missing)"
        transferFailed = true
        HqHostTracker.noteFailure(currentHost)
        failureNoted = true
        TrackLog.w(TAG, "hq-stream: $message")
        throw IOException(message)
    }

    fun finish() {
        if (openedAtMs != 0L) {
            if (transferFailed) {
                // A failed open has already been recorded where it happened; a
                // truncated or aborted read has not. Record it once either way.
                if (!failureNoted) HqHostTracker.noteFailure(currentHost)
            } else {
                HqHostTracker.noteTransfer(
                    currentHost,
                    bytesRead,
                    System.currentTimeMillis() - openedAtMs,
                    ok = true,
                )
            }
        }
        currentHost = null
        openedAtMs = 0L
        bytesRead = 0L
        transferFailed = false
        failureNoted = false
    }

    /**
     * Opens [spec], letting an awake server's 429 set the pace but waking a
     * sleeping gateway only once.
     *
     * A 429 is waited out according to its `Retry-After` or, when it sent none,
     * 1s/2s/4s — at most three retries. A 502/503/504 means a free-tier
     * instance is asleep or booting: the request already sent is the wake-up,
     * so it is not repeated, and the host is rested for [HqRetryPolicy.REST_MS]
     * (or its stated `Retry-After`) so candidate ordering and the next track's
     * read-ahead steer around it.
     */
    fun openWithRateLimitRetry(spec: DataSpec): Long {
        var attempt = 0
        while (true) {
            try {
                return upstream.open(spec)
            } catch (e: Exception) {
                val refusal = rateLimitedCause(e) ?: throw e
                val code = refusal.responseCode
                if (!HqRetryPolicy.isRateLimited(code) &&
                    !HqRetryPolicy.isSleepingGateway(code)
                ) {
                    throw e
                }
                val host = HqHostTracker.hostOf(spec.uri.toString()) ?: spec.uri.host
                val header = HqRetryPolicy.retryAfterHeader(refusal.headerFields)
                val decision = HqRetryPolicy.decideAddon(
                    code = code,
                    header = header,
                    attempt = attempt,
                    nowMs = System.currentTimeMillis(),
                )
                val restMs = HqHostTracker.noteRateLimited(host, header, code)
                val statedMs = HqRetryPolicy.retryAfterMs(header, System.currentTimeMillis())
                TrackLog.w(
                    TAG,
                    "hq-stream: ${host ?: "?"} $code " +
                        "retry-after=${(statedMs ?: decision.waitMs) / 1000}s; " +
                        when {
                            decision.retryAfterTooLong ->
                                "over ${HqRetryPolicy.MAX_WAIT_MS / 1000}s cap, switching route"
                            decision.retry ->
                                "retrying (${attempt + 1}/${HqRetryPolicy.MAX_ATTEMPTS})"
                            HqRetryPolicy.isSleepingGateway(code) ->
                                "sleeping gateway; one wake request, resting ${restMs / 1000}s"
                            else -> "giving up"
                        },
                )
                if (!decision.retry) {
                    HqHostTracker.noteFailure(host)
                    // A long Retry-After, or the retry budget spent, must not
                    // leave the same URL in the resolver's cache: the playback
                    // recovery re-opens this item in 350ms and would otherwise
                    // be handed the exact URL the host just asked us to leave
                    // alone.
                    StreamResolver.onRateLimited(spec.uri.toString())
                    throw e
                }
                runCatching { upstream.close() }
                runBlocking { delay(decision.waitMs) }
                attempt++
            }
        }
    }

    fun rateLimitedCause(error: Throwable?): HttpDataSource.InvalidResponseCodeException? {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < 8) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause
            cause = cause.cause?.takeIf { it !== cause }
        }
        return null
    }

    private companion object {
        const val TAG = "BitChord"
    }
}
