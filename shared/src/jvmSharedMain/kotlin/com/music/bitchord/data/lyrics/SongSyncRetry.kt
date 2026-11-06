package com.music.bitchord.data.lyrics

/**
 * How a song check answers a 429, without Android or a coroutine.
 *
 * Whisper is the one expensive request a song check makes. Deepgram takes 3 of
 * them at once per project and turns the rest away with a 429 and no
 * `Retry-After` (its rate-limit page; measured 11-06), so a slot opens when a
 * request in flight ends: 5-15 s usually, up to 93 s. The old behaviour — treat a
 * 429 as "no answer", record the engine as spent, and move on — silently gave up
 * on a song until its lyrics changed. The rules here are the pure part: how long
 * to wait, whether another attempt is allowed, and whether that wait is short
 * enough to sit out. A longer wait is a deferral to the next playback, not a
 * timer.
 */
object SongSyncRetry {

    /**
     * Five retries after the first 429, about a minute in all. Being turned away
     * costs an upload, not an answer. Two retries gave up after 6 s, inside one
     * ordinary request, and the song had no check for the rest of its playback
     * (phone 11-06 10:43 and 10:53).
     */
    const val MAX_RETRIES = 5

    /** First wait when the server sent no `Retry-After`: 2s, then 4s. */
    const val BASE_DELAY_MS = 2_000L

    /** The computed backoff never grows past this. */
    const val MAX_DELAY_MS = 32_000L

    /** A stated delay is believed up to this sanity bound, not shortened to [MAX_DELAY_MS]. */
    const val MAX_STATED_DELAY_MS = 3_600_000L

    /**
     * Longer than this is not waited out inline: the song is marked for a
     * later playback instead, so a skipped track does not keep a coroutine
     * and a paid upload alive in the background. It covers the computed
     * backoff, so another song meeting the last hold waits instead of giving up.
     */
    const val MAX_INLINE_WAIT_MS = MAX_DELAY_MS

    /**
     * The wait before attempt [attempt] (0 for the first retry): `Retry-After`
     * when sent, otherwise a doubling backoff. The stated delay is respected;
     * only the computed one is capped at [MAX_DELAY_MS].
     */
    fun retryDelayMs(retryAfterMs: Long?, attempt: Int): Long {
        val stated = retryAfterMs?.takeIf { it > 0L }
        if (stated != null) return stated.coerceIn(0L, MAX_STATED_DELAY_MS)
        val backoff = BASE_DELAY_MS shl attempt.coerceIn(0, 5)
        return backoff.coerceIn(0L, MAX_DELAY_MS)
    }

    /** Whether a 429 may be retried at all, given how many retries already happened. */
    fun shouldRetry(httpCode: Int?, attempt: Int): Boolean =
        httpCode == 429 && attempt < MAX_RETRIES

    /** Whether [delayMs] can be spent inline while holding the single song-sync slot. */
    fun canWaitInline(delayMs: Long): Boolean = delayMs <= MAX_INLINE_WAIT_MS
}
