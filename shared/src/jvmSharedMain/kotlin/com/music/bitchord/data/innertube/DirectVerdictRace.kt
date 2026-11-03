package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.raceForFirstSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * What one direct-client attempt learned before its stream was probed.
 *
 * [Gone] is the only answer that may jump the queue: it names the recording
 * itself (a claim, takedown, private or deleted upload), which every client
 * reads the same way. [Other] keeps its refusal for the caller's ordinary
 * ordering and proxy decisions instead of settling the track early.
 */
internal sealed class DirectProbe<out S> {
    class Stream<out S>(val value: S) : DirectProbe<S>()
    class Gone(val reason: String) : DirectProbe<Nothing>()
    class Other(val refused: Boolean, val reason: String?) : DirectProbe<Nothing>()
}

/** The outcome of watching a direct probe beside an InnerTubeX walk. */
internal sealed class DirectVerdictRace<out S> {
    class Gone(val reason: String) : DirectVerdictRace<Nothing>()
    class ViaInnerTubeX<out S>(val value: S) : DirectVerdictRace<S>()
    object None : DirectVerdictRace<Nothing>()
}

/**
 * Whether an anonymous direct client's private answer may really be the
 * signed-in user's own upload. The direct ladder sends no cookie, so for a
 * known upload or a signed-in session the cookie-backed walk may still serve
 * it; that verdict must not settle the track or cancel the walk.
 */
internal fun anonymousPrivateMayBeOwned(
    reason: String?,
    mayBeOwnPrivateUpload: Boolean,
): Boolean = mayBeOwnPrivateUpload && UnplayableReason.isPrivateOnly(reason)

private sealed class RaceEvent<out S> {
    class Direct<out S>(val value: DirectProbe<S>) : RaceEvent<S>()
    class InnerTubeX<out S>(val value: S?) : RaceEvent<S>()
}

/**
 * Lets a direct probe run beside an InnerTubeX walk without changing which
 * path leads.
 *
 * Only a [DirectProbe.Gone] answer pre-empts InnerTubeX: the walk is cancelled
 * and the caller ends the track immediately. A playable direct stream, an
 * ordinary refusal or a transport failure all leave InnerTubeX in front, so a
 * normal track's first sound still comes from the learned path and its
 * ordering/learning is unchanged. When InnerTubeX wins, the direct probe is
 * cancelled; when InnerTubeX answers nothing, the direct result is left
 * in-flight for the caller's existing fallback to consume. A null [direct]
 * means there is no probe to watch, so InnerTubeX is the only ask.
 */
internal suspend fun <S> raceDirectDefinitive(
    direct: Deferred<DirectProbe<S>>?,
    innerTubeX: Deferred<S?>,
): DirectVerdictRace<S> {
    if (direct == null) {
        return innerTubeX.await()?.let { DirectVerdictRace.ViaInnerTubeX(it) }
            ?: DirectVerdictRace.None
    }
    while (true) {
        val event = select<RaceEvent<S>> {
            direct.onAwait { RaceEvent.Direct(it) }
            innerTubeX.onAwait { RaceEvent.InnerTubeX(it) }
        }
        when (event) {
            is RaceEvent.Direct -> {
                val probe = event.value
                if (probe is DirectProbe.Gone) {
                    innerTubeX.cancel()
                    return DirectVerdictRace.Gone(probe.reason)
                }
                val value = innerTubeX.await()
                return if (value != null) {
                    DirectVerdictRace.ViaInnerTubeX(value)
                } else {
                    DirectVerdictRace.None
                }
            }
            is RaceEvent.InnerTubeX -> {
                return if (event.value != null) {
                    direct.cancel()
                    DirectVerdictRace.ViaInnerTubeX(event.value)
                } else {
                    DirectVerdictRace.None
                }
            }
        }
    }
}

/** The outcome of the bounded proxy race the resolver runs before its final race. */
internal sealed class ProxyEarlyRace<out S> {
    class Won<out S>(val route: String, val value: S) : ProxyEarlyRace<S>()
    class ProxyDown(val failure: Throwable) : ProxyEarlyRace<Nothing>()
    class Unplayable(val reason: String) : ProxyEarlyRace<Nothing>()
    object Inconclusive : ProxyEarlyRace<Nothing>()
}

/**
 * Races the proxy against a still-running InnerTubeX walk under one ceiling.
 *
 * The walk belongs to the resolver, not to this race: an expired window leaves
 * it running for the caller's final race. A track that is settled as
 * unplayable, or lost to a dead proxy, must not keep extracting in the
 * background, so those paths cancel it here.
 */
internal suspend fun <T, S> raceProxyEarly(
    itx: Deferred<T?>,
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    proxyTimeoutMs: Long,
    skipExtraction: Boolean,
    mapDirect: (T) -> S,
    resolveProxy: suspend () -> S?,
    isProxyDown: (Throwable) -> Boolean,
    unplayableReason: () -> String,
): ProxyEarlyRace<S> {
    val itxStream = scope.async(context) { itx.await()?.let(mapDirect) }
    val proxyFailure = AtomicReference<Throwable?>()
    val racedPair = try {
        withTimeoutOrNull(proxyTimeoutMs) {
            raceForFirstSuccess(
                direct = { itxStream.await() },
                proxy = {
                    try {
                        withTimeoutOrNull(proxyTimeoutMs) { resolveProxy() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        proxyFailure.set(failure)
                        null
                    }
                },
            )
        }
    } catch (cancelled: CancellationException) {
        itxStream.cancel()
        itx.cancel()
        throw cancelled
    }
    val winner = racedPair?.first
    val won = racedPair?.second
    if (winner == "proxy") {
        itxStream.cancel()
        itx.cancel()
    } else if (racedPair == null) {
        // The proxy window closed without an answer. Leave the InnerTubeX walk
        // running for the final race instead of cancelling it: awaiting a
        // cancelled deferred would throw and abort the whole resolve.
        itxStream.cancel()
    }
    if (won != null && winner != null) return ProxyEarlyRace.Won(winner, won)
    val deadProxy = proxyFailure.get()
    if (deadProxy != null && isProxyDown(deadProxy)) {
        itxStream.cancel()
        itx.cancel()
        return ProxyEarlyRace.ProxyDown(deadProxy)
    }
    if (skipExtraction) {
        itx.cancel()
        return ProxyEarlyRace.Unplayable(unplayableReason())
    }
    return ProxyEarlyRace.Inconclusive
}
