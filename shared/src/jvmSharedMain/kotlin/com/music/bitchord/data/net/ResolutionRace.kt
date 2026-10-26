package com.music.bitchord.data.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

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
 *
 * [proxyStart] makes the proxy route wait for a signal instead of starting at
 * once. Stream resolution passes the first probe refusal here: the direct
 * ladder keeps walking its remaining clients while the proxy starts on the
 * first one that was refused, rather than after the whole ladder has failed.
 * If direct fails without ever signalling, the proxy is started then, so the
 * route is still tried exactly once.
 */
private val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal suspend fun <T> raceForFirstSuccess(
    direct: suspend () -> T?,
    proxy: suspend () -> T?,
    proxyStart: Deferred<Unit>? = null,
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
    val proxyJob = raceScope.async(start = CoroutineStart.LAZY) {
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
    val starter = if (proxyStart == null) {
        proxyJob.start()
        null
    } else {
        raceScope.launch {
            proxyStart.await()
            proxyJob.start()
        }
    }
    val first = try {
        result.await()
    } catch (cancelled: CancellationException) {
        starter?.cancel()
        directJob.cancel()
        proxyJob.cancel()
        throw cancelled
    }
    if (first.second != null) {
        starter?.cancel()
        if (first.first == "direct") proxyJob.cancel() else directJob.cancel()
        return first
    }
    // The first answer was a failure. If the direct route never reached its
    // first refusal, the proxy has not started yet; start it now so a direct
    // failure is still followed by the proxy attempt it has always had.
    starter?.cancel()
    proxyJob.start()
    // The other route may still succeed, and its result is the one the caller
    // can play; if it fails too, the caller gets the first failure and handles
    // it once.
    val otherJob = if (first.first == "direct") proxyJob else directJob
    val other = try {
        otherJob.await()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        first
    }
    return if (other.second != null) other else first
}
