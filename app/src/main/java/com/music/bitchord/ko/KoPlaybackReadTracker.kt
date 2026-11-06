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
                    keyIdleSignals.getOrPut(key) { CompletableDeferred() }
                } else {
                    null
                }
            } ?: return
            signal.await()
        }
    }

    fun readerReleased(key: String) {
        if (!playerHoldsKey(key)) keyIdleSignals.remove(key)?.complete(Unit)
    }

    private fun retain(counts: ConcurrentHashMap<String, Int>, key: String) {
        counts.merge(key, 1) { open, added -> open + added }
    }

    private fun release(counts: ConcurrentHashMap<String, Int>, key: String) {
        counts.compute(key) { _, open -> if (open == null || open <= 1) null else open - 1 }
    }
}
