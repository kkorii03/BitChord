package com.music.bitchord.data.net

/**
 * REPRO: the bookkeeping exactly as task/o-proxyrace a4f3240ff..c10af08cc had it,
 * expressed in the new seam. The walk checks its own job and then writes,
 * unsynchronised and not closed by a proxy adoption; the proxy memory is
 * written as soon as the leg answers (resolveViaProxy -> markResolved).
 * Expected RED in ResolveBookingTest.
 */
internal class ResolveBooking {
    @Volatile private var key: String? = null
    @Volatile private var label: String? = null

    fun served(key: String, label: String, stillWalking: () -> Boolean = { true }): Boolean {
        if (!stillWalking()) return false
        this.key = key
        this.label = label
        return true
    }

    fun bookMiss(stillWalking: () -> Boolean, book: () -> Unit): Boolean {
        if (!stillWalking()) return false
        book()
        return true
    }

    fun proxyAnswered(remember: () -> Unit) = remember()

    fun adoptProxy(label: String) {
        key = null
        this.label = label
    }

    fun winnerKey(): String? = key

    fun winnerLabel(): String? = label
}