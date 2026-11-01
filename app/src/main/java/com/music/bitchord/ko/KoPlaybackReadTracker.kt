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

    fun retainReadAhead(videoId: String) = retain(readAheadWrites, videoId)

    fun releaseReadAhead(videoId: String) = release(readAheadWrites, videoId)

    fun playerHoldsKey(cacheKey: String): Boolean =
        (playbackKeysInUse[cacheKey] ?: 0) > 0

    fun playerHoldsVideo(videoId: String): Boolean =
        (playbackVideoIdsInUse[videoId] ?: 0) > 0

    /** Another writer — the player or read-ahead — already owns [videoId]'s entry. */
    fun anotherWriterHas(videoId: String): Boolean =
        playerHoldsVideo(videoId) || (readAheadWrites[videoId] ?: 0) > 0

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
