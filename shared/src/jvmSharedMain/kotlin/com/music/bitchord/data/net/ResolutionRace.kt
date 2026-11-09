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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

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
 * The leg's ceiling ([BlockedStartPolicy.lateProxyCeilingMs], handed in as
 * [aloneWithinMs]) bounds only the wait on the leg alone (no [rest], or a
 * [rest] that found nothing); the only timer is that one `withTimeoutOrNull`,
 * no poll. [rest] runs on the shared race scope,
 * like [raceForFirstSuccess]'s routes, so a win does not wait for a loser's
 * blocking HTTP call to come back.
 */
internal suspend fun <T : Any> raceLateLeg(
    late: Deferred<T?>?,
    rest: (suspend () -> T?)?,
    // How much of the leg's ceiling is left, read when the leg is waited for
    // alone. While [rest] is still asking the leg is not cut by it.
    aloneWithinMs: () -> Long = { Long.MAX_VALUE },
): LateLegRace<T> {
    if (late == null) return LateLegRace.Rest(rest?.invoke())
    if (rest == null) {
        val alone = try {
            awaitLegAlone(late, aloneWithinMs())
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
            val proxied = awaitLegAlone(late, aloneWithinMs())
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

/**
 * The proxy walk as a leg of its own. It has no clock of its own: while the
 * walk it races is still asking, its first usable answer wins whenever it
 * lands. rs22 182a T1tl66trXTQ (Hello): the proxy stream came 4.2 s after the
 * window, 1.2 s past the 3 s grace that used to cut the leg, while the walk sat
 * 143 s behind the cipher queue; the row never made a sound. A walk win, a
 * verdict, a track change or the resolve's finally stops it, and waiting for
 * it alone is bounded by [raceLateLeg]'s `aloneWithinMs`. A spent window
 * ([ceilingMs] <= 0) asks nothing, as before.
 */
internal fun <T : Any> startProxyLeg(
    scope: CoroutineScope,
    context: CoroutineContext,
    ceilingMs: Long,
    ask: suspend () -> T?,
): Deferred<T?> = scope.async(context) {
    if (ceilingMs <= 0L) null else withTimeoutOrNull(ceilingMs) { ask() } // REPRO: old cut at the ceiling
}

/** The leg's answer when nothing else is asking: at most [withinMs] more, then it is stopped. */
private suspend fun <T : Any> awaitLegAlone(late: Deferred<T?>, withinMs: Long): T? {
    if (late.isCompleted || withinMs == Long.MAX_VALUE) return awaitLeg(late)
    val answer = withTimeoutOrNull(withinMs.coerceAtLeast(0L)) { awaitLeg(late) }
    if (answer == null) late.cancel()
    return answer
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
