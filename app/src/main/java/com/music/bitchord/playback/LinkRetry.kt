package com.music.bitchord.playback

/** A tapped link's one look-up (red: no retry yet). */
internal object LinkRetry {

    const val DEADLINE_MS = 45_000L

    suspend fun <T> resolve(ask: suspend () -> Result<T>): Result<T> = ask()
}
