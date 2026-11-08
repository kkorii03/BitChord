package com.music.bitchord.data.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * The YouTube walk of a track, started beside its addon lookup instead of after it.
 *
 * Phone 11-08 (user log, 1.9.1): a replayed track whose cache held YouTube's
 * Opus waited for the addon lookup first (a cold addon timed out at 4 s), and
 * only then started its ~2.4 s YouTube walk: 6.3 s and 6.6 s to first sound
 * on two Home taps. Direct mode's own resolve already runs both at once; this
 * is the same rule for the cache-entry settle.
 *
 * [racesYouTube] is a row the listener is already waiting on: whichever of the
 * addon and YouTube can serve first wins, and a losing addon keeps running for
 * the second look. Any other row waits the addon window as before, with the
 * YouTube URL already in hand when the window closes empty.
 *
 * The walk is coalesced and cached by the resolver, so on the addon path it
 * only warms a URL the second look and hq-verify ask for anyway.
 */
object FirstSoundRace {

    class Settle<A : Any>(
        /** The addon stream that won, or null: play [youTube]'s answer. */
        val addon: A?,
        val youTube: Deferred<Result<String>>,
    )

    suspend fun <A : Any> settle(
        scope: CoroutineScope,
        racesYouTube: Boolean,
        youTube: suspend () -> String,
        lookup: suspend () -> A?,
        race: suspend (Deferred<Result<String>>) -> A?,
    ): Settle<A> {
        val walk = scope.async { runCatching { youTube() } }
        val addon = try {
            if (racesYouTube) race(walk) else lookup()
        } catch (failure: Throwable) {
            walk.cancel()
            throw failure
        }
        return Settle(addon, walk)
    }
}
