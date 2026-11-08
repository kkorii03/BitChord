package com.music.bitchord.data.net

/**
 * What one stream resolve teaches, decided once and under one lock: which path
 * served (for [com.music.bitchord.data.innertube.ResolutionOrder]), whether the
 * learned path missed, and whether the track is remembered as needing the proxy.
 *
 * A proxy leg now races the InnerTubeX walk past its window, and a cancelled
 * walk can still finish its blocking HTTP call and report what it found. A
 * check of the walk's job followed by a separate write let that report land
 * after the proxy had won, booking a proxy start as a direct winner. Here the
 * check and the write are one step, and adopting a proxy stream closes the
 * booking for good, so whichever happens second loses.
 *
 * The proxy's own memory ([ProxyPlayback.markResolved]: the URL's route
 * registration and the "remembered" shortcut) waits for [adoptProxy] too. A leg
 * that answers just after the walk has won is never adopted, so a stream that
 * played nowhere cannot send the next start of the track through the proxy.
 */
internal class ResolveBooking {
    private val lock = Any()
    private var proxyAdopted = false
    private var key: String? = null
    private var label: String? = null
    private var pendingProxyMemory: (() -> Unit)? = null

    /**
     * Books [key] as the path that served, unless a proxy stream has already
     * been adopted or [stillWalking] says the path was stopped. Returns whether
     * it was booked.
     */
    fun served(key: String, label: String, stillWalking: () -> Boolean = { true }): Boolean =
        synchronized(lock) {
            if (proxyAdopted || !stillWalking()) return false
            this.key = key
            this.label = label
            true
        }

    /** Runs [book] (a miss of a path) unless a proxy was adopted or the path was stopped. */
    fun bookMiss(stillWalking: () -> Boolean, book: () -> Unit): Boolean = synchronized(lock) {
        if (proxyAdopted || !stillWalking()) return false
        book()
        true
    }

    /** A proxy leg answered: [remember] is held until its stream is adopted. */
    fun proxyAnswered(remember: () -> Unit) = synchronized(lock) {
        pendingProxyMemory = remember
    }

    /**
     * The proxy's stream is the one handed to the player. No path learns from
     * this start, nothing booked later counts, and the proxy memory is written
     * now, once.
     */
    fun adoptProxy(label: String) {
        val memory = synchronized(lock) {
            proxyAdopted = true
            key = null
            this.label = label
            pendingProxyMemory.also { pendingProxyMemory = null }
        }
        memory?.invoke()
    }

    fun winnerKey(): String? = synchronized(lock) { key }

    fun winnerLabel(): String? = synchronized(lock) { label }
}
