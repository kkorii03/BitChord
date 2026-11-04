package com.music.bitchord.data.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The two-route race kept out of the resolver: when a proxy is usable, the
 * proxy walk runs beside the whole direct ladder instead of waiting for the
 * direct clients to refuse one by one, and whichever returns a stream first
 * wins. The losing coroutine is cancelled on a win so a rejected route does
 * not keep extracting in the background.
 *
 * The routes are launched in a shared scope rather than a `coroutineScope`, so
 * a winner returns without waiting for the loser's blocking HTTP call. The
 * loser still sees its cancellation once that call comes back.
 *
 * A `null` means that route failed. If both fail, the caller keeps the first
 * failure and falls through to its existing extraction failsafe.
 */
private val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal suspend fun <T> raceForFirstSuccess(
    direct: suspend () -> T?,
    proxy: suspend () -> T?,
): Pair<String, T?> {
    val result = CompletableDeferred<Pair<String, T?>>()
    val directJob = raceScope.async {
        val r = try {
            "direct" to direct()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            "direct" to null
        }
        result.complete(r)
        r
    }
    val proxyJob = raceScope.async {
        val r = try {
            "proxy" to proxy()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            "proxy" to null
        }
        result.complete(r)
        r
    }
    val first = try {
        result.await()
    } catch (cancelled: CancellationException) {
        directJob.cancel()
        proxyJob.cancel()
        throw cancelled
    }
    if (first.second != null) {
        if (first.first == "direct") proxyJob.cancel() else directJob.cancel()
        return first
    }
    // The first answer was a failure. The other route may still succeed, and
    // its result is the one the caller can play; if it fails too, the caller
    // gets the first failure and handles it once.
    val otherJob = if (first.first == "direct") proxyJob else directJob
    val other = try {
        otherJob.await()
    } catch (cancelled: CancellationException) {
        // The caller (for example a withTimeoutOrNull around this race) has
        // cancelled us. Both routes live on the shared scope, not as children
        // of this coroutine, so they must be stopped explicitly or the slow
        // proxy/InnerTubeX walk keeps running past the caller's budget.
        directJob.cancel()
        proxyJob.cancel()
        throw cancelled
    } catch (t: Throwable) {
        first
    }
    return if (other.second != null) other else first
}

/**
 * Gives a route that outlived the early race another chance.
 *
 * The early race closes at its own window, but the proxy often answers a
 * second later while extraction is still fetching the watch page. Running
 * [fallback] beside the still-running [late] race and returning the first
 * stream uses that answer instead of discarding it.
 *
 * The late route is accepted only while [deadlineRemainingMs] has time left,
 * the same rescue deadline every other rung obeys. At the deadline it is
 * stopped, but [fallback] is not: extraction keeps its existing retries.
 */
internal suspend fun <T> raceLateAnswerOrFallback(
    late: Deferred<Pair<String, T?>>,
    deadlineRemainingMs: () -> Long,
    fallback: suspend () -> T?,
): Pair<String, T?>? {
    var fallbackFailure: Throwable? = null
    val result = raceForFirstSuccess<T>(
        direct = {
            withTimeoutOrNull(deadlineRemainingMs().coerceAtLeast(0L)) { late.await().second }
                .also { if (it == null) late.cancel() }
        },
        proxy = {
            try {
                fallback()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                fallbackFailure = t
                null
            }
        },
    )
    if (result.first == "proxy") {
        late.cancel()
        if (result.second == null && fallbackFailure != null) throw fallbackFailure!!
        return "fallback" to result.second
    }
    // The late route won: keep the route stamp it carried.
    val lateAnswer = if (late.isCompleted && !late.isCancelled) late.getCompleted() else result
    if (lateAnswer.second == null && fallbackFailure != null) throw fallbackFailure!!
    return lateAnswer
}
