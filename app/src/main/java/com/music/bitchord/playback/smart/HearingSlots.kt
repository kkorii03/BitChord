package com.music.bitchord.playback.smart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore

/**
 * Who gets the next of [SongHearing]'s request slots, as plain lists, so the rule is tested
 * without coroutines. A row is the playing one, the next one, or gone (neither).
 */
internal object HearingPriority {

    /**
     * The waiter to hand a free slot to, by its index in [waiting] (oldest first), or null.
     * The playing row's oldest waiter goes first. A gone row does not start while the playing
     * row waits or holds a slot: its answer is only kept for a replay, and its upload would
     * share the line with the words someone is watching for. The next row is not held back by
     * a playing row in flight, only by one waiting.
     */
    fun nextIndex(waiting: List<String>, holding: List<String>, playing: String?, upNext: String?): Int? {
        if (waiting.isEmpty()) return null
        if (playing == null) return 0
        waiting.indexOf(playing).takeIf { it >= 0 }?.let { return it }
        val playingHolds = playing in holding
        return waiting.indices.firstOrNull { i -> waiting[i] == upNext || !playingHolds }
    }

    /** A slot holder as [yieldIndex] sees it. */
    data class Held(val videoId: String, val startedSeq: Long, val mayYield: Boolean)

    /**
     * The holder that gives its slot to the playing row's waiter when none is free, by index,
     * or null. Never the playing row's own; a gone row before the next one; of those the one
     * started last, which has sent the least.
     */
    fun yieldIndex(held: List<Held>, playing: String?, upNext: String?): Int? {
        if (playing == null) return null
        val candidates = held.indices.filter { held[it].mayYield && held[it].videoId != playing }
        return candidates.maxWithOrNull(
            compareBy<Int> { if (held[it].videoId == upNext) 0 else 1 }.thenBy { held[it].startedSeq },
        )
    }
}

/**
 * [permits] handed out by [HearingPriority]: the playing row's request is never queued behind a
 * row that is no longer playing (11-08 round 150a: PEIvcxSO9zQ played 2.5 minutes uncorrected
 * while the row before still uploaded its whole song). Event-driven: a slot is handed out when
 * one is asked for or given back, never on a timer.
 *
 * Every holder in production comes through here; [Semaphore.release] called on [permits] from
 * outside wakes nobody in this queue.
 */
internal class HearingSlots(
    private val permits: Semaphore,
    private val playing: () -> String?,
    private val upNext: () -> String?,
) {
    private class Holder(val videoId: String, val seq: Long, val mayYield: Boolean) {
        var job: Job? = null
        var yielded = false
    }

    private class Waiter(val videoId: String, val seq: Long, val mayYield: Boolean) {
        val turn = CompletableDeferred<Holder>()
        var handed: Holder? = null
    }

    private val lock = Any()
    private val waiters = ArrayList<Waiter>()
    private val holders = ArrayList<Holder>()
    private var seq = 0L

    /**
     * [work] holding one slot for [videoId]. When [mayYield] and the playing row needs the slot,
     * [work] is cancelled, [onYield] is told, and it queues again behind the playing row: what it
     * hears is kept per recording ([SongHearing.save]) for the next playback, so it still runs,
     * only later. A hedge's second copy passes false; it goes with the request it doubles.
     */
    suspend fun <T> withSlot(
        videoId: String,
        mayYield: Boolean = true,
        onYield: () -> Unit = {},
        work: suspend () -> T,
    ): T {
        while (true) {
            val holder = acquire(videoId, mayYield)
            try {
                return coroutineScope {
                    val run = async { work() }
                    val cancelNow = synchronized(lock) {
                        holder.job = run
                        holder.yielded
                    }
                    if (cancelNow) run.cancel()
                    run.await()
                }
            } catch (e: CancellationException) {
                val gaveWay = synchronized(lock) { holder.yielded }
                if (!gaveWay || !currentCoroutineContext().isActive) throw e
                onYield()
            } finally {
                release(holder)
            }
        }
    }

    /** Slots held right now, for tests. */
    internal fun held(): Int = synchronized(lock) { holders.size }

    private suspend fun acquire(videoId: String, mayYield: Boolean): Holder {
        val waiter = synchronized(lock) { Waiter(videoId, seq++, mayYield).also { waiters += it } }
        grant()
        try {
            return waiter.turn.await()
        } catch (e: CancellationException) {
            val stillWaiting = synchronized(lock) { waiters.remove(waiter) }
            if (!waiter.turn.completeExceptionally(e)) {
                // Handed a slot as it was cancelled: give it straight back.
                synchronized(lock) { waiter.handed }?.let(::release)
            } else if (stillWaiting) {
                // Gone from the queue: it may have been holding a lower one back.
                grant()
            }
            throw e
        }
    }

    private fun release(holder: Holder) {
        val was = synchronized(lock) { holders.remove(holder) }
        if (was) permits.release()
        grant()
    }

    private fun grant() {
        val handed = ArrayList<Pair<Waiter, Holder>>()
        var toCancel: Job? = null
        synchronized(lock) {
            val now = playing()
            val next = upNext()
            while (true) {
                val pick = HearingPriority.nextIndex(
                    waiters.map { it.videoId },
                    holders.map { it.videoId },
                    now,
                    next,
                ) ?: break
                if (!permits.tryAcquire()) {
                    // One holder per waiting playing-row request: one already giving way counts.
                    val waitingPlaying = waiters.count { it.videoId == now }
                    if (waiters[pick].videoId == now && waitingPlaying > holders.count { it.yielded }) {
                        val live = holders.filter { !it.yielded }
                        HearingPriority.yieldIndex(
                            live.map { HearingPriority.Held(it.videoId, it.seq, it.mayYield) },
                            now,
                            next,
                        )?.let { index ->
                            val victim = live[index]
                            victim.yielded = true
                            toCancel = victim.job
                        }
                    }
                    break
                }
                val waiter = waiters.removeAt(pick)
                val holder = Holder(waiter.videoId, waiter.seq, waiter.mayYield)
                waiter.handed = holder
                holders += holder
                handed += waiter to holder
            }
        }
        handed.forEach { (waiter, holder) ->
            // Its caller was cancelled meanwhile: the slot goes back at once.
            if (!waiter.turn.complete(holder)) release(holder)
        }
        toCancel?.cancel()
    }
}
