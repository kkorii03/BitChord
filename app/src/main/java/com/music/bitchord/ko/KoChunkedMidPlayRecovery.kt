package com.music.bitchord.ko

import android.net.Uri
import androidx.media3.datasource.HttpDataSource
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.PlayerClient
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.net.ProxyPlayback
import com.music.bitchord.playback.ChunkedDataSource
import com.music.bitchord.playback.MidPlayRefusalStep
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.StreamContainer
import com.music.bitchord.playback.StreamRefusalRecovery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [KO] mid-play-recovery
 *
 * A range was refused after the track had started. Ask for the same videoId
 * again and keep reading from the byte that failed.
 *
 * The URL that was refused is not retried first: it has already been refused,
 * and the reported failure is exactly what a five-second retry loop against it
 * looks like. The replacement is another direct client, then the proxy, and
 * only then — if neither produced a new URL — the original URL is retried, at
 * most [StreamRefusalRecovery.MAX_SAME_URL_RETRIES] times.
 *
 * @return the length opened on success, or null when the caller should let the
 *   ordinary failure path have the exception.
 */
internal fun ChunkedDataSource.koRecoverMidPlay(
    responseCode: Int,
    refusedUrl: String,
): Long? {
    // Position zero is the start of a load, not a mid-play continuation;
    // the resolver and the failure path own that case.
    if (position <= 0L) return null
    if (!StreamRefusalRecovery.isRefusal(responseCode)) return null
    val videoId = StreamResolver.videoIdFor(refusedUrl) ?: return null
    if (!StreamRefusalRecovery.canAttempt(videoId)) {
        TrackLog.w(
            KO_CHUNKED_RECOVERY_TAG,
            "stream: $videoId refused mid-play at ${koAtLabel(videoId)}; " +
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
                val opened = koOpenReplacement()
                if (opened != null) {
                    koHostCost.adoptRecoveredHost(replacement.url)
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
                        KO_CHUNKED_RECOVERY_TAG,
                        "stream: ${refused.videoId} refused mid-play at ${koAtLabel(refused.videoId)}; " +
                            "re-resolved via ${if (replacement.viaProxy) "proxy" else replacement.client} " +
                            "and resumed",
                        about = refused.videoId,
                    )
                    koHostCost.markRecovered()
                    chunkRemaining = opened
                    chunkOpen = true
                    return opened
                }
                // The replacement was refused too: forget it, so the next
                // step asks for a different client rather than the same one.
                val failure = koHostCost.lastOpenFailure
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
                val opened = koOpenReplacement()
                if (opened != null) {
                    koHostCost.adoptRecoveredHost(currentUrl)
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
                        KO_CHUNKED_RECOVERY_TAG,
                        "stream: ${refused.videoId} refused mid-play at ${koAtLabel(refused.videoId)}; " +
                            "the same URL answered on retry ${state.sameUrlRetries} and resumed",
                        about = refused.videoId,
                    )
                    koHostCost.markRecovered()
                    chunkRemaining = opened
                    chunkOpen = true
                    return opened
                }
                val failure = koHostCost.lastOpenFailure
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
        KO_CHUNKED_RECOVERY_TAG,
        "stream: ${refused.videoId} refused mid-play at ${koAtLabel(refused.videoId)}; " +
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
private fun ChunkedDataSource.koAtLabel(videoId: String): String {
    val ms = positionMsFor?.invoke(videoId)
    return if (ms != null && ms >= 0L) "${ms}ms" else "byte $position"
}

/** Opens the chunk currently described by [baseSpec], or null on failure. */
private fun ChunkedDataSource.koOpenReplacement(): Long? {
    val length = minOf(rangeBytes, bytesRemaining)
    if (length <= 0L) return 0L
    val spec = requireNotNull(baseSpec).buildUpon()
        .setPosition(position)
        .setLength(length)
        .build()
    return try {
        koHostCost.lastOpenFailure = null
        // The failed open may have left a source holding a socket; a
        // reopen on the same factory must start from a clean one.
        runCatching { upstream.close() }
        koHostCost.openWithRateLimitRetry(spec)
        length
    } catch (e: Exception) {
        koHostCost.lastOpenFailure = e
        null
    }
}

private const val KO_CHUNKED_RECOVERY_TAG = "BitChord"

/**
 * One refusal can walk direct and proxy, then two same-URL retries;
 * the cap is only a guard against a resolver that keeps handing back
 * refused URLs, not a path the ordinary case reaches.
 */
private const val MAX_RECOVERY_STEPS = 8

/** Long enough for a client walk and a proxy round trip, short enough to keep the buffer. */
private const val MID_PLAY_RESOLVE_TIMEOUT_MS = 10_000L
