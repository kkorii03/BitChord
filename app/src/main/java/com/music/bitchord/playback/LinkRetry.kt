package com.music.bitchord.playback

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A tapped link's look-up, asked again when the connection dropped.
 *
 * One look-up already tries three times in about four seconds, and a link has
 * only that one chance: the request is spent on a toast and the song never
 * starts. A dropped route is usually back within seconds (11-06 smoke: 5 of 134
 * songs lost this way, the next link on the same route answered). An HTTP status
 * or an empty answer would not change, so only a connection failure goes again.
 * The caller's effect ends when another link arrives, and these waits with it.
 */
internal object LinkRetry {

    private val WAITS_MS = longArrayOf(3_000L, 6_000L, 12_000L)

    const val DEADLINE_MS = 45_000L

    suspend fun <T> resolve(ask: suspend () -> Result<T>): Result<T> {
        var last = Result.failure<T>(IOException("no answer within $DEADLINE_MS ms"))
        withTimeoutOrNull(DEADLINE_MS) {
            for (wait in WAITS_MS) {
                last = ask()
                if (last.exceptionOrNull() !is IOException) return@withTimeoutOrNull
                delay(wait)
            }
            last = ask()
        }
        return last
    }
}
