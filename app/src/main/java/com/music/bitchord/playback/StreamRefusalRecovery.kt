package com.music.bitchord.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * What to do when googlevideo refuses a range in the middle of a track.
 *
 * The reported failure is a URL that served the opening of a track and then
 * answered 403 to every later range. Retrying that URL is pointless — it was
 * already refused — but the track is not: the same videoId can be resolved
 * again, through a different client or the proxy, and the read can continue
 * from the byte that failed. This is not the "never change the recording
 * mid-song" rule: the id is unchanged, so it is the same recording.
 *
 * Kept pure so the retry budget and the path order can be tested off-device.
 * [next] is the whole policy:
 *
 *  - a refusal is answered by re-resolving the same videoId immediately, while
 *    there is still buffer;
 *  - if no direct client can mint a new URL, the proxy is asked once;
 *  - only then is the refused URL itself retried, at most
 *    [MAX_SAME_URL_RETRIES] times with a backoff;
 *  - after that the stream is left to the ordinary failure path.
 *
 * The same-URL cap is deliberately after the re-resolves rather than before:
 * the URL has already been refused, so a retry of it is the least likely thing
 * to work, and the five-second loop the report shows is exactly what happens
 * when it is tried first.
 */
internal enum class MidPlayRefusalStep {
    RERESOLVE_DIRECT,
    RERESOLVE_PROXY,
    RETRY_SAME_URL,
    GIVE_UP,
}

internal data class MidPlayRefusalState(
    val directTried: Boolean = false,
    val proxyTried: Boolean = false,
    val sameUrlRetries: Int = 0,
)

internal object StreamRefusalRecovery {

    /** 403/404/410 are googlevideo's refusals; anything else is a different failure. */
    private val REFUSAL_CODES = setOf(403, 404, 410)

    /** Never more than this many retries of the URL that was already refused. */
    const val MAX_SAME_URL_RETRIES = 2

    /** A recovery round is a direct re-resolve plus its proxy fallback. */
    const val MAX_RECOVERY_ROUNDS = 2

    private const val STATE_TTL_MS = 2 * 60 * 1000L

    fun isRefusal(responseCode: Int): Boolean = responseCode in REFUSAL_CODES

    fun next(
        responseCode: Int,
        state: MidPlayRefusalState,
        canReresolve: Boolean,
        canProxy: Boolean,
    ): MidPlayRefusalStep = when {
        !isRefusal(responseCode) -> MidPlayRefusalStep.GIVE_UP
        !state.directTried && canReresolve -> MidPlayRefusalStep.RERESOLVE_DIRECT
        !state.proxyTried && canProxy -> MidPlayRefusalStep.RERESOLVE_PROXY
        state.sameUrlRetries < MAX_SAME_URL_RETRIES -> MidPlayRefusalStep.RETRY_SAME_URL
        else -> MidPlayRefusalStep.GIVE_UP
    }

    /** Short first pause, then a full second; never a five-second polling loop. */
    fun backoffMs(attempt: Int): Long = if (attempt <= 1) 250L else 1_000L

    private class Entry(val at: Long, val state: MidPlayRefusalState)

    private val states = ConcurrentHashMap<String, Entry>()
    private val rounds = ConcurrentHashMap<String, Int>()

    fun stateFor(key: String, now: Long = System.currentTimeMillis()): MidPlayRefusalState {
        val entry = states[key] ?: return MidPlayRefusalState()
        if (now - entry.at > STATE_TTL_MS) {
            states.remove(key, entry)
            return MidPlayRefusalState()
        }
        return entry.state
    }

    fun update(key: String, state: MidPlayRefusalState, now: Long = System.currentTimeMillis()) {
        if (states.size >= MAX_REMEMBERED) states.clear()
        states[key] = Entry(now, state)
    }

    /** Whether another direct-plus-proxy recovery round is allowed for [key]. */
    fun canAttempt(key: String): Boolean = (rounds[key] ?: 0) < MAX_RECOVERY_ROUNDS

    fun noteAttempt(key: String) {
        if (rounds.size >= MAX_REMEMBERED) rounds.clear()
        rounds.merge(key, 1) { old, _ -> old + 1 }
    }

    fun clear(key: String) {
        states.remove(key)
        rounds.remove(key)
    }

    // ── A module stream that refused its bytes ──────────────────────────────

    /**
     * How long a refused stream URL is kept.
     *
     * Long enough to cover every open one play makes — the player's own, a
     * read-ahead, a re-prepare after a skip back — and far shorter than the
     * life of the signed URLs this holds.
     */
    private const val DEAD_STREAM_TTL_MS = 10 * 60 * 1000L

    /**
     * How many *different* streams a host has to refuse before it counts as
     * down rather than as one bad row.
     */
    const val HOST_DOWN_AFTER_STREAMS = 2

    private class DeadStream(val at: Long, val error: Exception)

    private val deadStreams = ConcurrentHashMap<String, DeadStream>()

    /** Distinct refused stream URLs per host, and when the count started. */
    private class HostRefusals(val at: Long, val urls: MutableSet<String>)

    private val refusedByHost = ConcurrentHashMap<String, HostRefusals>()

    /**
     * The refusal [url] already answered with, or null when it has not been
     * refused.
     *
     * A module stream that answered 403/404/410 is dead: the lookup that minted
     * the URL is deterministic, so every re-open is the same refused request.
     * Handing the recorded exception straight back is what stops a dead URL
     * being opened again — six refusals over seven seconds on the report that
     * prompted this, all of them before the player was allowed to fall back.
     */
    fun refusedStream(url: String, now: Long = System.currentTimeMillis()): Exception? {
        val entry = deadStreams[url] ?: return null
        if (now - entry.at > DEAD_STREAM_TTL_MS) {
            deadStreams.remove(url, entry)
            return null
        }
        return entry.error
    }

    /**
     * Records that [url] on [host] refused its bytes, and whether that is now
     * evidence about the *host*.
     *
     * A 403 is usually a signature that expired between minting and use, and a
     * 404/410 a file that is not there. Neither says the server is down, so
     * resting the whole host on one of them takes every other track on a shared
     * CDN host with it. Only [HOST_DOWN_AFTER_STREAMS] different streams refused
     * the same way is a verdict about the server.
     *
     * @return true when the host has refused enough different streams to rest.
     */
    fun noteRefusedStream(
        url: String,
        host: String?,
        error: Exception,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (deadStreams.size >= MAX_REMEMBERED) deadStreams.clear()
        deadStreams[url] = DeadStream(now, error)
        val key = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val count = synchronized(refusedByHost) {
            val fresh = refusedByHost[key]?.takeIf { now - it.at <= DEAD_STREAM_TTL_MS }
                ?: HostRefusals(now, mutableSetOf<String>()).also {
                    if (refusedByHost.size >= MAX_REMEMBERED) refusedByHost.clear()
                    refusedByHost[key] = it
                }
            fresh.urls += url
            fresh.urls.size
        }
        return count >= HOST_DOWN_AFTER_STREAMS
    }

    internal fun resetForTest() {
        states.clear()
        rounds.clear()
        deadStreams.clear()
        refusedByHost.clear()
    }

    private const val MAX_REMEMBERED = 32
}
