package com.music.bitchord.data.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * Work that is started once per key and shared by everyone who asks for it.
 *
 * A queue build and a track becoming current can ask about the same track
 * within a second of each other. Without sharing, the second asker repeats
 * the whole lookup from the start — a `next` plus a search — after the first
 * asker has already given up on its 1.5 s budget; that is what turned one
 * track into a 4.5 s wait for the first sound.
 *
 * The producing coroutine runs in [scope], never in the caller's: a caller
 * that stops waiting (its own timeout, a skip) must not cancel a lookup
 * another caller is waiting on, nor the answer that fills the cache. The
 * lookup's own network calls keep their own timeouts.
 */
class SharedLookups<T>(private val scope: CoroutineScope) {

    /** What a caller gets back: the shared result, and whether it started it. */
    class Lookup<T>(
        val result: Deferred<T>,
        /** True when the work was already in flight, so this caller only waits. */
        val joined: Boolean,
    )

    private val running = ConcurrentHashMap<String, Deferred<T>>()

    /** Starts [produce] for [key] in [scope], or joins the one already running. */
    @Synchronized
    fun lookup(key: String, produce: suspend () -> T): Lookup<T> {
        running[key]?.let { existing ->
            if (existing.isActive) return Lookup(existing, joined = true)
            running.remove(key)
        }
        val job = scope.async(start = CoroutineStart.LAZY) { produce() }
        running[key] = job
        job.invokeOnCompletion {
            synchronized(this) { if (running[key] === job) running.remove(key) }
        }
        job.start()
        return Lookup(job, joined = false)
    }

    /** True when [key] has work in flight, i.e. a second asker would share it. */
    fun isRunning(key: String): Boolean = running[key]?.isActive == true

    internal fun runningCount(): Int = running.size
}
