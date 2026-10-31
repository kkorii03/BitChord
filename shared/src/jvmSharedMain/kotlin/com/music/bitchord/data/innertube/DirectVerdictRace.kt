package com.music.bitchord.data.innertube

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select

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
 * in-flight for the caller's existing fallback to consume.
 */
internal suspend fun <S> raceDirectDefinitive(
    direct: Deferred<DirectProbe<S>>,
    innerTubeX: Deferred<S?>,
): DirectVerdictRace<S> {
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
