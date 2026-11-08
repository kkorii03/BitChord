package com.music.bitchord.data.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select

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

/** Who answered once a proxy leg outlived its window and raced the rest of the walk. */
internal sealed class LateLegRace<out T> {
    /** The rest of the walk answered first ([value]), or the late leg failed and [value] is the walk's answer. */
    class Rest<out T>(val value: T?) : LateLegRace<T>()

    /** The late proxy leg answered first; the rest of the walk was stopped. */
    class Late<out T>(val value: T) : LateLegRace<T>()
}

/**
 * Races a proxy leg whose window has already closed ([late]) against what the
 * walk does next ([rest]).
 *
 * The proxy window used to cancel its leg when it closed, and the answer that
 * landed 0.1-2.6 s later was thrown away while the start waited for the
 * InnerTubeX walk (rs18-rs20: 71 times, up to 26 s of first sound). The leg is
 * now kept and raced here: the first usable answer wins and the loser is
 * cancelled, so nothing is asked twice and only one stream is handed back.
 *
 *  - no late leg                   -> the walk alone, unchanged
 *  - no [rest] (the walk gives up) -> the late leg alone, then nothing
 *  - the leg answers first         -> its stream; [rest] is cancelled
 *  - [rest] answers first          -> its stream; the leg is cancelled
 *  - the leg fails                 -> [rest]'s answer, as if it had been cut at the window
 *  - [rest] fails (null)           -> the leg's answer, or nothing
 *  - [rest] throws                 -> the leg is cancelled and the exception kept:
 *                                     a verdict about the recording ends the start
 *  - the caller is cancelled       -> both are cancelled
 *
 * The leg's own ceiling ([BlockedStartPolicy.lateProxyCeilingMs]) bounds the
 * wait; no timer or poll is added here. [rest] runs on the shared race scope,
 * like [raceForFirstSuccess]'s routes, so a win does not wait for a loser's
 * blocking HTTP call to come back.
 */
internal suspend fun <T : Any> raceLateLeg(
    late: Deferred<T?>?,
    rest: (suspend () -> T?)?,
): LateLegRace<T> {
    if (late == null) return LateLegRace.Rest(rest?.invoke())
    if (rest == null) {
        val alone = try {
            awaitLeg(late)
        } catch (cancelled: CancellationException) {
            late.cancel()
            throw cancelled
        }
        return if (alone != null) LateLegRace.Late(alone) else LateLegRace.Rest(null)
    }
    val restJob = raceScope.async { rest() }
    try {
        val restFirst = select<Boolean> {
            restJob.onJoin { true }
            late.onJoin { false }
        }
        if (restFirst) {
            // Rethrows the walk's own exception, which the catch below hands on.
            val walked = restJob.await()
            if (walked != null) {
                late.cancel()
                return LateLegRace.Rest(walked)
            }
            val proxied = awaitLeg(late)
            return if (proxied != null) LateLegRace.Late(proxied) else LateLegRace.Rest(null)
        }
        val proxied = awaitLeg(late)
        if (proxied != null) {
            restJob.cancel()
            return LateLegRace.Late(proxied)
        }
        return LateLegRace.Rest(restJob.await())
    } catch (t: Throwable) {
        restJob.cancel()
        late.cancel()
        throw t
    }
}

/** The leg's answer; a leg that failed or was stopped by someone else is no answer. */
private suspend fun <T : Any> awaitLeg(late: Deferred<T?>): T? = try {
    late.await()
} catch (cancelled: CancellationException) {
    // Our own cancellation is handed on; the leg's is just a missing answer.
    currentCoroutineContext().ensureActive()
    null
} catch (failure: Exception) {
    null
}
