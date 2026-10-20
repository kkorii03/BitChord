package com.music.bitchord.data.innertube

/**
 * Remembers which stream-resolution path actually worked, so the next track
 * tries it first instead of re-walking every client that was refused.
 *
 * Measured on the Korean no-Premium path: the anonymous IOS/ANDROID `/player`
 * asks are refused in about 400ms each, InnerTubeX then walks the dead web and
 * embedded profiles, and the one profile that answers
 * (`WEB_CREATOR__po`) is tried last — 2.7-5.1s per start. The winner is stable
 * for hours at a time, so it is worth remembering rather than rediscovering.
 *
 * The rule is deliberately small and pure (no Android, no clock of its own):
 *
 *  - a path that serves a track becomes the winner and is kept for
 *    [WINNER_TTL_MS], lightly persisted across processes;
 *  - two consecutive misses by the winner clear it, and the next success is
 *    learned afresh;
 *  - the anonymous direct path is benched for [DIRECT_BENCH_MS] once it has
 *    been refused for [DIRECT_WINDOW] of the last [DIRECT_WINDOW] tracks (five
 *    of six), because each of those refusals costs a round trip on every start;
 *  - the extraction failsafe is never learned: it is slow by design and only
 *    runs when nothing else answered.
 *
 * Paths are plain strings so they can be persisted and logged:
 * [DIRECT], `itx:<manifest id>` (InnerTubeX) and [NEW_PIPE].
 */
class ResolutionOrder(
    store: Store? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** Where the learned winner is kept between runs — the phone's SharedPreferences. */
    interface Store {
        fun getString(key: String): String?
        fun getLong(key: String): Long?
        fun putString(key: String, value: String)
        fun putLong(key: String, value: Long)
    }

    companion object {
        /** The anonymous IOS/ANDROID `/player` request. */
        const val DIRECT = "direct"

        /** NewPipe's watch-page extraction — the last resort, never learned. */
        const val NEW_PIPE = "newpipe"

        private const val ITX_PREFIX = "itx:"

        /** A path InnerTubeX served, keyed by its stable manifest id (`WEB_CREATOR`, not `...__po`). */
        fun itx(manifestId: String): String = ITX_PREFIX + manifestId

        /** The manifest id inside a learned InnerTubeX path, or null for direct/NewPipe. */
        fun itxManifest(path: String?): String? =
            path?.takeIf { it.startsWith(ITX_PREFIX) }?.removePrefix(ITX_PREFIX)?.takeIf { it.isNotBlank() }

        /** How long a learned winner is trusted before it has to prove itself again. */
        const val WINNER_TTL_MS = 24 * 60 * 60 * 1000L

        /** Two misses in a row is a pattern, not a bad afternoon. */
        const val WINNER_MISS_LIMIT = 2

        /** Tracks considered when deciding to bench the direct path. */
        const val DIRECT_WINDOW = 6

        /** Refusals within that window that make the direct path not worth asking. */
        const val DIRECT_REFUSAL_LIMIT = 5

        /** How long the direct path sits out once benched. */
        const val DIRECT_BENCH_MS = 30 * 60 * 1000L

        private const val KEY_WINNER = "stream_order.winner"
        private const val KEY_WINNER_AT = "stream_order.winner_at"

        /** How many recent outcomes are kept per path for `refusalsRecently` and diagnostics. */
        private const val RECENT_LIMIT = 8
    }

    private val lock = Any()

    private var store: Store? = store
    private var winner: String? = null
    private var winnerAt: Long = 0L
    private var winnerMisses: Int = 0

    /** Direct-path outcomes, oldest first: true = refused. */
    private val recentDirect = ArrayDeque<Boolean>()
    private var directBenchedUntil: Long = 0L

    private val recentRefusals = HashMap<String, ArrayDeque<Boolean>>()

    init {
        synchronized(lock) { load() }
    }

    /** Attaches the persistent store after construction; reloads whatever it already holds. */
    fun attach(store: Store) {
        synchronized(lock) {
            this.store = store
            load()
        }
    }

    /**
     * The path to try first, or null for the default client order.
     *
     * Null when nothing has been learned, when the learned winner has outlived
     * [WINNER_TTL_MS], or when the winner is the direct path and that path is
     * currently benched.
     */
    fun preferred(): String? = synchronized(lock) {
        val path = winner ?: return null
        if (now() - winnerAt >= WINNER_TTL_MS) {
            clearWinner()
            return null
        }
        if (path == DIRECT && !directAllowedLocked()) return null
        path
    }

    /** Records that [path] served this track: it becomes the winner and is tried first next time. */
    fun noteServed(path: String) = synchronized(lock) {
        if (path == NEW_PIPE) return
        winner = path
        winnerAt = now()
        winnerMisses = 0
        persist()
    }

    /** Records that [path] was asked and answered nothing for this track. */
    fun noteRefused(path: String) = synchronized(lock) {
        recordRefusalLocked(path)
        if (path != winner) return
        winnerMisses++
        if (winnerMisses >= WINNER_MISS_LIMIT) {
            // Two tracks in a row where the remembered path did not deliver:
            // stop leading with it and let the next success be the teacher.
            clearWinner()
        }
    }

    /** How many of the last [RECENT_LIMIT] asks of [path] were refused. */
    fun refusalsRecently(path: String): Int = synchronized(lock) {
        recentRefusals[path]?.count { it } ?: 0
    }

    /** Whether the anonymous direct path is worth asking right now. */
    fun directAllowed(): Boolean = synchronized(lock) { directAllowedLocked() }

    /** Records that the direct path was asked for this track and refused it. */
    fun noteDirectRefused() = synchronized(lock) {
        recordDirectLocked(refused = true)
        if (recentDirect.size >= DIRECT_WINDOW && recentDirect.count { it } >= DIRECT_REFUSAL_LIMIT) {
            directBenchedUntil = now() + DIRECT_BENCH_MS
        }
    }

    /** Records that the direct path served this track. */
    fun noteDirectServed() = synchronized(lock) {
        recordDirectLocked(refused = false)
        recentRefusals.getOrPut(DIRECT) { ArrayDeque() }.also { ring ->
            ring.addLast(false)
            while (ring.size > RECENT_LIMIT) ring.removeFirst()
        }
    }

    private fun directAllowedLocked(): Boolean = now() >= directBenchedUntil

    private fun recordDirectLocked(refused: Boolean) {
        recentDirect.addLast(refused)
        while (recentDirect.size > DIRECT_WINDOW) recentDirect.removeFirst()
    }

    private fun recordRefusalLocked(path: String) {
        val ring = recentRefusals.getOrPut(path) { ArrayDeque() }
        ring.addLast(true)
        while (ring.size > RECENT_LIMIT) ring.removeFirst()
    }

    private fun clearWinner() {
        winner = null
        winnerAt = 0L
        winnerMisses = 0
        persist()
    }

    private fun load() {
        val path = store?.getString(KEY_WINNER)?.takeIf { it.isNotBlank() }
        val at = store?.getLong(KEY_WINNER_AT) ?: 0L
        if (path != null && at > 0L && now() - at < WINNER_TTL_MS) {
            winner = path
            winnerAt = at
            winnerMisses = 0
        } else {
            winner = null
            winnerAt = 0L
            winnerMisses = 0
        }
    }

    private fun persist() {
        val target = store ?: return
        val path = winner
        if (path == null) {
            target.putString(KEY_WINNER, "")
            target.putLong(KEY_WINNER_AT, 0L)
        } else {
            target.putString(KEY_WINNER, path)
            target.putLong(KEY_WINNER_AT, winnerAt)
        }
    }
}
