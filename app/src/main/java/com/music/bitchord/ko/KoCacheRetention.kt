package com.music.bitchord.ko

import android.os.SystemClock
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.BatteryPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [KO] cache-retention
 *
 * Timing holds and the incremental purge index. Both are additions to the
 * upstream AudioCache bookkeeping, kept here so the cache file only calls in.
 */
class KoCacheRetention {
    private val tag = "BitChord"

    private val timingKeep = LinkedHashMap<String, Long>()

    fun keepForTiming(videoId: String) = synchronized(timingKeep) {
        timingKeep.remove(videoId)
        timingKeep[videoId] = SystemClock.elapsedRealtime()
        while (timingKeep.size > TIMING_KEEP_MAX) timingKeep.remove(timingKeep.keys.first())
    }

    fun keptForTiming(videoId: String): Boolean = synchronized(timingKeep) {
        val at = timingKeep[videoId] ?: return false
        SystemClock.elapsedRealtime() - at < TIMING_KEEP_MS
    }

    private val purgeCandidates = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var purgeIndexBuilt = false

    fun purgeCandidate(meta: ContentMetadata): Boolean = BatteryPolicy.cacheEntryNeedsPurge(
        origin = meta.get(META_ORIGIN, null as String?),
        foreignOrigin = AudioCache.Origin.OTHER.name,
        hasAnalysis = meta.get(META_ANALYSIS, null as String?) != null,
        hasPlayback = meta.get(META_PLAYBACK, null as String?) != null,
        hasPlayed = meta.get(META_PLAYED, null as String?) != null,
    )

    fun update(key: String, candidate: Boolean) {
        if (candidate) purgeCandidates.add(key) else purgeCandidates.remove(key)
    }

    fun forget(key: String) {
        purgeCandidates.remove(key)
    }

    fun resetIndex() {
        purgeCandidates.clear()
        purgeIndexBuilt = true
    }

    private fun rebuild(cache: Cache) {
        // Only adds: a concurrent recordServed may have put a newer key in
        // the set while this scan was running, and it must not be lost.
        cache.keys.forEach { key ->
            val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: return@forEach
            if (purgeCandidate(meta)) purgeCandidates.add(key)
        }
        purgeIndexBuilt = true
    }

    fun dropForeignEntries(
        cache: Cache,
        keep: Set<String>,
        keepAnalysis: Boolean,
        keepsAddonCopy: (String) -> Boolean,
        scope: CoroutineScope,
        onDropped: (String) -> Unit,
    ) {
        scope.launch {
            if (!purgeIndexBuilt) rebuild(cache)
            var dropped = 0
            purgeCandidates.toList().forEach { key ->
                val meta = runCatching { cache.getContentMetadata(key) }.getOrNull()
                if (meta == null || !purgeCandidate(meta)) {
                    forget(key)
                    return@forEach
                }
                val mediaId = meta.get(META_MEDIA_ID, null as String?)
                val origin = meta.get(META_ORIGIN, null as String?)
                val keptByQueueOrTiming = mediaId != null &&
                    (mediaId in keep || keptForTiming(mediaId))
                if (cacheEntryShouldDrop(
                        origin = origin,
                        foreignOrigin = AudioCache.Origin.OTHER.name,
                        hasAnalysis = meta.get(META_ANALYSIS, null as String?) != null,
                        hasPlayback = meta.get(META_PLAYBACK, null as String?) != null,
                        hasPlayed = meta.get(META_PLAYED, null as String?) != null,
                        keepAnalysis = keepAnalysis,
                        // Only an addon entry can be a kept copy; the complete
                        // and container probes are file I/O, not worth running
                        // for an analysis-only YouTube entry.
                        keepsAddonCopy = origin == AudioCache.Origin.OTHER.name && keepsAddonCopy(key),
                        keptByQueueOrTiming = keptByQueueOrTiming,
                    )
                ) {
                    return@forEach
                }
                runCatching { cache.removeResource(key) }
                    .onSuccess {
                        dropped++
                        forget(key)
                        mediaId?.let(onDropped)
                    }
                    .onFailure { TrackLog.d(tag, "unkept cache entry $key still in use: ${it.message}") }
            }
            if (dropped > 0) TrackLog.d(tag, "dropped $dropped addon / analysis-only cache entries")
        }
    }

    private companion object {
        private const val TIMING_KEEP_MS = 15 * 60_000L
        private const val TIMING_KEEP_MAX = 16

        private const val META_ORIGIN = "bc_origin"
        private const val META_MEDIA_ID = "bc_media_id"
        private const val META_PLAYBACK = "bc_playback"
        private const val META_ANALYSIS = "bc_analysis"
        private const val META_PLAYED = "bc_played"
    }
}

/**
 * The one purge rule, shared by the index scan and the drop pass.
 *
 * [keepsAddonCopy] is v1.9's complete-audio test: an addon copy that is whole
 * and decodable may be read back off disk, so it outlives the queue moving on.
 * [keepAnalysis] spares Automix's analysis-only copies for callers that are
 * only dropping addon entries. [keptByQueueOrTiming] covers the playhead window
 * and [KoCacheRetention.keptForTiming]'s analysis keeps.
 */
internal fun cacheEntryShouldDrop(
    origin: String?,
    foreignOrigin: String,
    hasAnalysis: Boolean,
    hasPlayback: Boolean,
    hasPlayed: Boolean,
    keepAnalysis: Boolean,
    keepsAddonCopy: Boolean,
    keptByQueueOrTiming: Boolean,
): Boolean {
    if (keptByQueueOrTiming) return false
    val foreign = origin == foreignOrigin && !keepsAddonCopy
    val analysisOnly = hasAnalysis && !hasPlayback && !hasPlayed && !keepAnalysis
    return foreign || analysisOnly
}
