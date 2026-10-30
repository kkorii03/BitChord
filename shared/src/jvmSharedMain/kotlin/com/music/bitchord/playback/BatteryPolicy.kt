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

    /**
     * Whether Automix may decode and run its models right now.
     *
     * A whole-track decode plus two ONNX passes is the heaviest background
     * work the player does, so it waits out low power like every other heavy
     * job. The crossfade ticker asks again on its next tick once power saving
     * is off, which is why deferring needs no timer of its own. A party never
     * mixes, so it stops the work for the same reason it always did.
     */
    fun automixAnalysisActive(lowPower: Boolean, inParty: Boolean): Boolean =
        !lowPower && !inParty

    /**
     * Whether read-ahead may spend bytes on the next track.
     *
     * Resolving its stream URL is still worth doing — a few small round trips
     * — but downloading the track is not while the phone is saving power.
     * Metered connections were already refused by the caller; keeping low
     * power out of that decision is what makes it testable here.
     */
    fun cachePrefetchBytesAllowed(metered: Boolean, lowPower: Boolean): Boolean =
        !metered && !lowPower

    /**
     * Whether the background artist backfill may spend a request right now.
     *
     * The Replay is a page, so an answer nobody is looking at can wait. Running
     * the lookups only with a window and out of power saving is the same
     * event-driven rule as the download queue: the foreground/low-power flows
     * wake the worker, not a timer.
     */
    fun artistBackfillActive(lowPower: Boolean, foreground: Boolean): Boolean =
        !lowPower && foreground

    /**
     * Whether a download worker may leave.
     *
     * The grace window is the start-of-queue race: the first enqueue starts the
     * service while the rest of a batch is still arriving. Only after that
     * window, with nothing queued or in flight, is an empty queue finished.
     */
    fun downloadWorkerShouldStop(idleForMs: Long, graceMs: Long, busy: Boolean): Boolean =
        idleForMs >= graceMs && !busy
}
