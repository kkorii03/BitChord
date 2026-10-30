package com.music.bitchord.playback

import android.net.Uri
import android.util.Log
import com.music.bitchord.data.TrackLog
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.innertube.PlayerClient
import com.music.bitchord.data.net.HqRetryPolicy
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.net.ProxyPlayback
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Fetches a stream as a run of bounded ranges instead of one open-ended read.
 *
 * googlevideo paces a continuous response down to roughly playback speed:
 * ask for a whole track in one request and it arrives at about 15kB/s, barely
 * ahead of the rate it is being consumed at. Ask for the same bytes as bounded
 * ranges and each one is served at line rate ??measured on the same track,
 * over the same connection, seconds apart:
 *
 * ```
 *   GET (unbounded)               15.5 kB/s
 *   Range: bytes=0-2097151         5.7 MB/s
 * ```
 *
 * That is the difference between a player that can build a buffer and one that
 * can only just keep up, and it is why a stall never recovers into a cushion:
 * there is no spare bandwidth to catch up with.
 *
 * [AudioCache] already fetches this way ??it is why read-ahead can put a whole
 * track on disk in the time between songs. This puts the player's own reads on
 * the same footing, which matters for the track being listened to right now:
 * read-ahead deliberately never touches it (Media3 locks a cache entry to one
 * writer), so without this it is the one track that always streams throttled.
 *
 * Transparent to everything above it. [CacheDataSource][androidx.media3.datasource.cache.CacheDataSource]
 * sees one continuous stream of the length it asked for. Bounded requests are
 * split too, so no caller can exceed [PlayerClient.rangeBytesFor].
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long,
    private val positionMsFor: ((String) -> Long?)? = null,
) : DataSource {

    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var rangeBytes = chunkBytes

    /** The host currently being read, and what it has cost, for [HqHostTracker]. */
    private var currentHost: String? = null
    private var openedAtMs = 0L
    private var bytesRead = 0L

    /** Set when the request can't be improved on, and is simply forwarded. */
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    /**
     * The total size has to be known up front, or there is no way to say where
     * the last range ends. Every progressive googlevideo URL carries it as
     * `clen`, which costs nothing to read; anything else is forwarded as-is.
     */
    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position
        currentHost = HqHostTracker.hostOf(dataSpec.uri.toString())
        openedAtMs = System.currentTimeMillis()
        bytesRead = 0L

        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (total == null) {
            passthrough = true
            chunkOpen = true
            // Reported, not just thrown. Nothing but googlevideo carries `clen`,
            // so every module stream comes through this branch ??and this was
            // the one open in the app whose refusal was neither logged nor
            // handed anywhere. A dead Tidal URL surfaced as a bare
            // ExoPlaybackException with not one line naming the server that
            // produced it or the status it produced.
            try {
                return openWithRateLimitRetry(dataSpec) { upstream.open(dataSpec) }
            } catch (e: Exception) {
                if (e !is InterruptedIOException) {
                    // `host` is null exactly when the URL was too broken to
                    // parse, which is the case most in need of naming ??so fall
                    // back to the string itself rather than logging "null".
                    val who = dataSpec.uri.host ?: dataSpec.uri.toString().take(120)
                    TrackLog.w(TAG, "$who refused the stream: ${e.message}")
                    HqHostTracker.noteFailure(currentHost)
                    report(dataSpec, e)
                }
                throw e
            }
        }

        passthrough = false
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            total
        } else {
            minOf(total, position + dataSpec.length)
        }
        bytesRemaining = (end - position).coerceAtLeast(0L)
        rangeBytes = minOf(chunkBytes, PlayerClient.rangeBytesFor(dataSpec.uri.toString()))
        if (bytesRemaining > 0) openChunk()
        return bytesRemaining
    }

    private fun openChunk() {
        val length = minOf(rangeBytes, bytesRemaining)
        val spec = requireNotNull(baseSpec).buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        try {
            openWithRateLimitRetry(spec) { upstream.open(spec) }
        } catch (e: Exception) {
            // Which client minted the URL is the first thing worth knowing when
            // a range is refused, and it isn't recoverable from anywhere else by
            // the time this surfaces as a playback error. A cancelled read-ahead
            // arrives here too and means nothing ??see [AudioCache.prefetchQueue].
            if (e !is InterruptedIOException) {
                TrackLog.w(
                    TAG,
                    "range $position-${position + length - 1} refused for " +
                        "${spec.uri.getQueryParameter("c")}: ${e.message}",
                )
                if (e is HttpDataSource.InvalidResponseCodeException) {
                    val resumed = recoverMidPlay(e.responseCode, spec.uri.toString())
                    if (resumed != null) {
                        chunkRemaining = resumed
                        chunkOpen = true
                        return
                    }
                }
                // This is the only place a refusal of the *real* fetch is seen.
                // Left here it is just a failed track; handed back, it is the
                // one piece of evidence that retiring the client rests on.
                report(spec, e)
            }
            throw e
        }
        chunkRemaining = length
        chunkOpen = true
    }

    /**
     * A range was refused after the track had started. Ask for the same
     * videoId again and keep reading from the byte that failed.
     *
     * The URL that was refused is not retried first: it has already been
     * refused, and the reported failure is exactly what a five-second retry
     * loop against it looks like. The replacement is another direct client,
     * then the proxy, and only then ??if neither produced a new URL ??the
     * original URL is retried, at most [StreamRefusalRecovery.MAX_SAME_URL_RETRIES]
     * times.
     *
     * @return the length opened on success, or null when the caller should let
     *   the ordinary failure path have the exception.
     */
    private fun recoverMidPlay(responseCode: Int, refusedUrl: String): Long? {
        // Position zero is the start of a load, not a mid-play continuation;
        // the resolver and the failure path own that case.
        if (position <= 0L) return null
        if (!StreamRefusalRecovery.isRefusal(responseCode)) return null
        val videoId = StreamResolver.videoIdFor(refusedUrl) ?: return null
        if (!StreamRefusalRecovery.canAttempt(videoId)) {
            TrackLog.w(
                TAG,
                "stream: $videoId refused mid-play at ${atLabel(videoId)}; " +
                    "recovery budget spent, leaving it to the failure path",
                about = videoId,
            )
            return null
        }
        val refused = StreamResolver.refuseMidPlay(refusedUrl, responseCode) ?: return null
        // The pin must go before the re-resolve, or the next resolver call
        // would hand back the same dead URL. It is restored on success.
        StreamChoice.forget(refused.videoId)
        StreamRefusalRecovery.noteAttempt(refused.videoId)

        var state = StreamRefusalRecovery.stateFor(refused.videoId)
        var currentUrl = refusedUrl
        var currentCode = responseCode
        var gaveUpReason: String? = null

        for (attempt in 0 until MAX_RECOVERY_STEPS) {
            val step = StreamRefusalRecovery.next(
                responseCode = currentCode,
                state = state,
                canReresolve = true,
                canProxy = ProxyPlayback.canResolveViaProxy(),
            )
            when (step) {
                MidPlayRefusalStep.RERESOLVE_DIRECT,
                MidPlayRefusalStep.RERESOLVE_PROXY -> {
                    val viaProxy = step == MidPlayRefusalStep.RERESOLVE_PROXY
                    state = state.copy(
                        directTried = state.directTried || !viaProxy,
                        proxyTried = state.proxyTried || viaProxy,
                    )
                    StreamRefusalRecovery.update(refused.videoId, state)
                    val replacement = runBlocking {
                        withTimeoutOrNull(MID_PLAY_RESOLVE_TIMEOUT_MS) {
                            StreamResolver.reResolve(refused, forceProxy = viaProxy)
                        }
                    }
                    if (replacement == null || replacement.url == currentUrl) {
                        gaveUpReason = "re-resolve via ${if (viaProxy) "proxy" else "direct"} failed"
                        continue
                    }
                    if (!StreamContainer.sameRendition(currentUrl, replacement.url)) {
                        gaveUpReason = "re-resolved to a different rendition (mime/clen)"
                        continue
                    }
                    baseSpec = requireNotNull(baseSpec).buildUpon()
                        .setUri(Uri.parse(replacement.url))
                        .setHttpRequestHeaders(replacement.headers)
                        .build()
                    rangeBytes = minOf(chunkBytes, PlayerClient.rangeBytesFor(replacement.url))
                    val opened = openReplacement()
                    if (opened != null) {
                        StreamResolver.rememberServed(refused.videoId, replacement.url)
                        StreamChoice.remember(
                            refused.videoId,
                            com.music.bitchord.data.sources.SourceStream(
                                replacement.url,
                                headers = replacement.headers,
                            ),
                            substituted = false,
                        )
                        StreamContainer.served(refused.videoId, replacement.url)
                        StreamRefusalRecovery.clear(refused.videoId)
                        TrackLog.w(
                            TAG,
                            "stream: ${refused.videoId} refused mid-play at ${atLabel(refused.videoId)}; " +
                                "re-resolved via ${if (replacement.viaProxy) "proxy" else replacement.client} " +
                                "and resumed",
                            about = refused.videoId,
                        )
                        return opened
                    }
                    // The replacement was refused too: forget it, so the next
                    // step asks for a different client rather than the same one.
                    val failure = lastOpenFailure
                    if (failure is HttpDataSource.InvalidResponseCodeException &&
                        StreamRefusalRecovery.isRefusal(failure.responseCode)
                    ) {
                        StreamResolver.refuseMidPlay(replacement.url, failure.responseCode)
                        currentUrl = replacement.url
                        currentCode = failure.responseCode
                        gaveUpReason = "replacement refused with ${failure.responseCode}"
                    } else {
                        gaveUpReason = "replacement could not be opened"
                        break
                    }
                }

                MidPlayRefusalStep.RETRY_SAME_URL -> {
                    state = state.copy(sameUrlRetries = state.sameUrlRetries + 1)
                    StreamRefusalRecovery.update(refused.videoId, state)
                    runBlocking { delay(StreamRefusalRecovery.backoffMs(state.sameUrlRetries)) }
                    val opened = openReplacement()
                    if (opened != null) {
                        StreamResolver.rememberServed(refused.videoId, currentUrl)
                        StreamChoice.remember(
                            refused.videoId,
                            com.music.bitchord.data.sources.SourceStream(
                                currentUrl,
                                headers = baseSpec?.httpRequestHeaders ?: emptyMap<String, String>(),
                            ),
                            substituted = false,
                        )
                        StreamContainer.served(refused.videoId, currentUrl)
                        StreamRefusalRecovery.clear(refused.videoId)
                        TrackLog.w(
                            TAG,
                            "stream: ${refused.videoId} refused mid-play at ${atLabel(refused.videoId)}; " +
                                "the same URL answered on retry ${state.sameUrlRetries} and resumed",
                            about = refused.videoId,
                        )
                        return opened
                    }
                    val failure = lastOpenFailure
                    if (failure is HttpDataSource.InvalidResponseCodeException &&
                        StreamRefusalRecovery.isRefusal(failure.responseCode)
                    ) {
                        currentCode = failure.responseCode
                        gaveUpReason = "same URL still refused with ${failure.responseCode}"
                    } else {
                        gaveUpReason = "same URL could not be opened"
                        break
                    }
                }

                MidPlayRefusalStep.GIVE_UP -> {
                    gaveUpReason = gaveUpReason ?: "no path left"
                    break
                }
            }
        }

        TrackLog.w(
            TAG,
            "stream: ${refused.videoId} refused mid-play at ${atLabel(refused.videoId)}; " +
                "re-resolve failed (${gaveUpReason ?: "no path left"})",
            about = refused.videoId,
        )
        return null
    }

    /**
     * Where playback was when the range failed, in the unit the log line asks
     * for. The byte offset is the fallback for read-ahead, where the track is
     * not the one playing and no playback time exists for it yet.
     */
    private fun atLabel(videoId: String): String {
        val ms = positionMsFor?.invoke(videoId)
        return if (ms != null && ms >= 0L) "${ms}ms" else "byte $position"
    }

    /** The last exception [openReplacement] saw, for the recovery loop above. */
    private var lastOpenFailure: Exception? = null

    /** Opens the chunk currently described by [baseSpec], or null on failure. */
    private fun openReplacement(): Long? {
        val length = minOf(rangeBytes, bytesRemaining)
        if (length <= 0L) return 0L
        val spec = requireNotNull(baseSpec).buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        return try {
            lastOpenFailure = null
            // The failed open may have left a source holding a socket; a
            // reopen on the same factory must start from a clean one.
            runCatching { upstream.close() }
            openWithRateLimitRetry(spec) { upstream.open(spec) }
            length
        } catch (e: Exception) {
            lastOpenFailure = e
            null
        }
    }

    /**
     * Hands a refusal back to whoever minted the URL that was refused.
     *
     * [StreamResolver] sorts out whether the URL was one of its own ??see
     * [StreamResolver.onPlaybackRefused]. Anything that isn't a refusal, or
     * isn't from a client it models, costs nothing here.
     */
    private fun report(spec: DataSpec, e: Exception) {
        if (e is HttpDataSource.InvalidResponseCodeException) {
            StreamResolver.onPlaybackRefused(spec.uri.toString(), e.responseCode)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) {
            val read = upstream.read(buffer, offset, length)
            if (read > 0) bytesRead += read
            return read
        }
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // A range that ends early is re-opened for the part that didn't
        // arrive, which is also how the step to the next range happens. The
        // attempt limit is what stops a server that has decided to send
        // nothing from spinning here forever.
        repeat(MAX_EMPTY_RANGES) {
            if (chunkRemaining == 0L) {
                closeChunk()
                openChunk()
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                chunkRemaining -= read
                bytesRemaining -= read
                bytesRead += read
                return read
            }
            chunkRemaining = 0L
        }
        // Every re-open at this position also ended before the bytes that were
        // asked for. Reporting EOF here is what made a cut stream look like a
        // finished song: ExoPlayer advanced the queue with no error and no
        // recovery. A short read is a transport failure, not the end of the
        // track, because `clen` already said how many bytes there are.
        val message = "truncated stream at $position ($bytesRemaining bytes missing)"
        TrackLog.w(TAG, "hq-stream: $message")
        throw IOException(message)
    }

    private fun closeChunk() {
        if (chunkOpen) {
            upstream.close()
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        if (openedAtMs != 0L) {
            HqHostTracker.noteTransfer(
                currentHost,
                bytesRead,
                System.currentTimeMillis() - openedAtMs,
                ok = true,
            )
        }
        baseSpec = null
        bytesRemaining = 0L
        chunkRemaining = 0L
        currentHost = null
        openedAtMs = 0L
        bytesRead = 0L
    }

    /**
     * Opens [spec], waiting out a 429/503 according to its `Retry-After` or,
     * when it sent none, 1s/2s/4s — at most three retries. The host is rested
     * for the longer of that wait and [HqRetryPolicy.REST_MS] so candidate
     * ordering and the next track's read-ahead steer around it.
     */
    private fun openWithRateLimitRetry(spec: DataSpec, open: () -> Long): Long {
        var attempt = 0
        while (true) {
            try {
                return open()
            } catch (e: Exception) {
                val refusal = rateLimitedCause(e) ?: throw e
                if (!HqRetryPolicy.isRateLimited(refusal.responseCode)) throw e
                val host = HqHostTracker.hostOf(spec.uri.toString()) ?: spec.uri.host
                val header = HqRetryPolicy.retryAfterHeader(refusal.headerFields)
                val decision = HqRetryPolicy.decide(header, attempt, System.currentTimeMillis())
                HqHostTracker.noteRateLimited(host, header, refusal.responseCode)
                val statedMs = HqRetryPolicy.retryAfterMs(header, System.currentTimeMillis())
                TrackLog.w(
                    TAG,
                    "hq-stream: ${host ?: "?"} ${refusal.responseCode} " +
                        "retry-after=${(statedMs ?: decision.waitMs) / 1000}s; " +
                        when {
                            decision.retryAfterTooLong ->
                                "over ${HqRetryPolicy.MAX_WAIT_MS / 1000}s cap, switching route"
                            decision.retry ->
                                "retrying (${attempt + 1}/${HqRetryPolicy.MAX_ATTEMPTS})"
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

    private fun rateLimitedCause(error: Throwable?): HttpDataSource.InvalidResponseCodeException? {
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

        /** Enough to ride out a truncated range, not enough to hang on a dead one. */
        const val MAX_EMPTY_RANGES = 3

        /**
         * One refusal can walk direct and proxy, then two same-URL retries;
         * the cap is only a guard against a resolver that keeps handing back
         * refused URLs, not a path the ordinary case reaches.
         */
        const val MAX_RECOVERY_STEPS = 8

        /** Long enough for a client walk and a proxy round trip, short enough to keep the buffer. */
        const val MID_PLAY_RESOLVE_TIMEOUT_MS = 10_000L
    }


    /** Wraps [upstream]'s sources so everything opened through it is ranged. */
    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long,
        private val positionMsFor: ((String) -> Long?)? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstream.createDataSource(), chunkBytes, positionMsFor)
    }
}
