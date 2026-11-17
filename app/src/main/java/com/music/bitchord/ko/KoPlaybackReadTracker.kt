package com.music.bitchord.ko

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * [KO] reader-lock
 *
 * Tracks which cache entries the player, its read-ahead and the analysis head
 * currently hold. Media3 grants a cache entry to one writer, so the fork needs
 * to know about live readers before starting another fetch; upstream only asks
 * the cache whether bytes exist.
 */
class KoPlaybackReadTracker {
    private val playbackKeysInUse = ConcurrentHashMap<String, Int>()
    private val playbackVideoIdsInUse = ConcurrentHashMap<String, Int>()
    private val keyIdleSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val readAheadWrites = ConcurrentHashMap<String, Int>()

    /**
     * Review 11-17 (RG1-1): while one player data source reopens a plain URI
     * it already opened (a seek or a retry inside the same play), the video
     * and the entry its earlier open went to. The key is resolved on the
     * thread doing the open, so the pin is that thread's alone: another
     * play's first open of the same song (a repeat, a replay) never sees it.
     */
    private val reopenPin = ThreadLocal<Pair<String, String>?>()

    fun pinReopen(videoId: String?, key: String?) {
        if (videoId == null || key == null) reopenPin.remove() else reopenPin.set(videoId to key)
    }

    /** The entry this thread's reopen of [videoId] read before, or null on a first open. */
    fun reopenPinFor(videoId: String): String? = reopenPin.get()?.takeIf { it.first == videoId }?.second

    fun retainKey(key: String) = retain(playbackKeysInUse, key)

    fun releaseKey(key: String) = release(playbackKeysInUse, key)

    fun retainVideo(videoId: String) = retain(playbackVideoIdsInUse, videoId)

    fun releaseVideo(videoId: String) = release(playbackVideoIdsInUse, videoId)

    /** [cacheKey] is the entry read-ahead actually writes, not the video id. */
    fun retainReadAhead(cacheKey: String) = retain(readAheadWrites, cacheKey)

    fun releaseReadAhead(cacheKey: String) = release(readAheadWrites, cacheKey)

    fun playerHoldsKey(cacheKey: String): Boolean =
        (playbackKeysInUse[cacheKey] ?: 0) > 0

    fun playerHoldsVideo(videoId: String): Boolean =
        (playbackVideoIdsInUse[videoId] ?: 0) > 0

    /**
     * Another writer — the player or read-ahead — already owns [videoId]'s
     * analysis entry.
     *
     * Both are asked by key, not by video: a `#alt`, `#hifi` or `#watch`
     * rendition is a different entry, so a track playing or being read ahead
     * from one of those must not hold the plain analysis key hostage for the
     * whole song. The analysis head pins its write to the plain video id
     * (`fetch(..., pinKey = true)`), not to `keyFactory`'s answer for the
     * `automix_opus` URI, so that is the key asked about here. See
     * [KoAnalysisHeadEngine]'s one-round claim.
     */
    fun anotherWriterHas(videoId: String): Boolean =
        playerHoldsKey(videoId) || (readAheadWrites[videoId] ?: 0) > 0

    /** Completes when the player lets go of [key]; runs no timer and never polls. */
    suspend fun awaitRelease(key: String) {
        while (true) {
            val signal = synchronized(keyIdleSignals) {
                if (playerHoldsKey(key)) {
                    beforeInstall()
                    keyIdleSignals.getOrPut(key) { CompletableDeferred() }
                } else {
                    null
                }
            } ?: return
            signal.await()
        }
    }

    /**
     * Under the same lock [awaitRelease] checks and installs its signal with:
     * unlocked, a release landing between that check and the install found no
     * signal to complete, and the waiter then installed one nobody ever would.
     */
    fun readerReleased(key: String) {
        synchronized(keyIdleSignals) {
            if (!playerHoldsKey(key)) keyIdleSignals.remove(key)?.complete(Unit)
        }
    }

    /** Test seam: runs inside [awaitRelease]'s lock, between its check and its install. */
    @Volatile
    internal var beforeInstall: () -> Unit = {}

    private fun retain(counts: ConcurrentHashMap<String, Int>, key: String) {
        counts.merge(key, 1) { open, added -> open + added }
    }

    private fun release(counts: ConcurrentHashMap<String, Int>, key: String) {
        counts.compute(key) { _, open -> if (open == null || open <= 1) null else open - 1 }
    }
}
