package com.music.bitchord.playback

/**
 * Pure decisions for the periodic work the battery audit found.
 *
 * Kept here so the rules can be tested without Android or a running player.
 * Every caller supplies the current state; none of these read settings or
 * power state themselves.
 */
object BatteryPolicy {

    /** The party reconciler only needs a tick while a party is actually playing. */
    fun partyTickerActive(inParty: Boolean, partyPlaying: Boolean): Boolean =
        inParty && partyPlaying

    /**
     * The health poll exists to fill in a server status while the socket is
     * not proving reachability. Off-screen, outside a party, or with a live
     * socket there is nothing for it to do.
     */
    fun healthMonitorActive(
        screenActive: Boolean,
        inParty: Boolean,
        connectionLive: Boolean,
    ): Boolean = screenActive && inParty && !connectionLive

    /**
     * Whether a cache entry is one [AudioCache.dropForeignEntries] may remove.
     *
     * [foreignOrigin] names the addon/module origin that is never kept.
     * Analysis-only entries are those Automix downloaded but playback never
     * read and never played; the same entry becomes keepable the moment either
     * of those flags is set.
     */
    fun cacheEntryNeedsPurge(
        origin: String?,
        foreignOrigin: String,
        hasAnalysis: Boolean,
        hasPlayback: Boolean,
        hasPlayed: Boolean,
    ): Boolean {
        if (origin == foreignOrigin) return true
        return hasAnalysis && !hasPlayback && !hasPlayed
    }
}
