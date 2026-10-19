package com.music.bitchord.data.settings

/**
 * The writes private mode considers, local and account-visible alike.
 *
 * Named individually rather than left as a bare boolean so the call sites read
 * as "this is a history write" instead of "this is gated too", and so the
 * matrix can be pinned by a test if a future sink is added.
 */
enum class ListeningSink {
    /** The account-visible YouTube watch-history pings in `PlaybackTracker`. */
    YOUTUBE_HISTORY,

    /** This device's minutes/plays under `ListeningStats`, and Replay by extension. */
    LISTENING_STATS,

    /** The recommendation store's plays, completions, skips and ratings. */
    TASTE,

    /** Last.fm and ListenBrainz submissions. */
    SCROBBLING,

    /** The local `SearchHistory` list. */
    SEARCH_HISTORY,

    /** The song Discord shows on the listener's profile. */
    DISCORD_PRESENCE,
}

/**
 * The single decision every history-bearing sink consults.
 *
 * Deliberately pure: [sink] and [privateListening] are the whole input, so the
 * policy can be tested without a player, a store or an Android context. The
 * caller reads the persisted flag once per event; there is no timer, poll or
 * background watcher behind private mode.
 *
 * Private mode is about what leaves the device, not what the device remembers.
 * Local sinks — this device's listening stats and Replay, the local taste
 * store, and the in-app search list — stay on. Sinks that send data to an
 * account are the ones that are silenced.
 */
object PrivateListening {
    /**
     * How long a private session lasts before it turns itself off.
     *
     * The same idea as Spotify's private session: the switch is a mode, not a
     * promise to remember it forever. Six hours covers an evening and expires
     * before the next one, so a listener cannot leave it on for a week without
     * noticing.
     */
    const val SESSION_MS = 6L * 60L * 60L * 1000L

    fun allows(sink: ListeningSink, privateListening: Boolean): Boolean = when (sink) {
        // Local records: nothing here leaves the device, so private mode must
        // not take Recents, Replay, recommendations or in-app search away.
        ListeningSink.LISTENING_STATS,
        ListeningSink.TASTE,
        ListeningSink.SEARCH_HISTORY -> true

        // Account-visible writes: YouTube history, Last.fm/ListenBrainz and
        // Discord presence are exactly what private mode exists to silence.
        ListeningSink.YOUTUBE_HISTORY,
        ListeningSink.SCROBBLING,
        ListeningSink.DISCORD_PRESENCE -> !privateListening
    }

    /**
     * Whether a private session that started at [sinceMs] is still running at
     * [nowMs].
     *
     * Pure so the six-hour rule can be tested without a clock or a preference
     * file. [sinceMs] is expected to be a real start time; callers that find a
     * missing one record the current time first, so an old install is treated
     * as having enabled private listening on this update.
     */
    fun isActive(enabled: Boolean, sinceMs: Long, nowMs: Long): Boolean =
        enabled && nowMs - sinceMs < SESSION_MS
}
