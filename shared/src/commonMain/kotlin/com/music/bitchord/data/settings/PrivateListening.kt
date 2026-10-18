package com.music.bitchord.data.settings

/**
 * The writes private listening is meant to silence.
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
 * Every sink is silenced today. The sink parameter is kept because "which
 * writes does private mode suppress" is the question this object exists to
 * answer, and a future exception belongs here rather than as a condition
 * beside one caller.
 */
object PrivateListening {
    @Suppress("UNUSED_PARAMETER")
    fun allows(sink: ListeningSink, privateListening: Boolean): Boolean {
        // Private listening currently suppresses every sink listed above; the
        // parameter stays because that is the decision this object owns.
        return !privateListening
    }
}
