package com.music.bitchord.ko

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [KO] analysis-waits
 *
 * Event-driven waits for an analysis copy: the whole-track wait and the
 * span-added wait for a row that is not playing. No timer polls the cache;
 * the prefix wait wakes only on the cache's own span event.
 */
class KoAnalysisWaits(
    private val cache: () -> Cache?,
    private val renditionsOf: (Uri) -> List<AudioCache.Rendition>,
    private val awaitAnalysisHead: suspend (Uri, Long) -> Boolean,
    private val analysisHeadsInFlight: Set<String>,
    private val alignmentPrefixBytes: (String) -> Long,
    private val renditionKeysFor: (String) -> List<String>,
    private val refreshRenditionKeys: (String) -> Unit,
    private val altSuffix: String,
) {
    suspend fun awaitAnalysisTrack(uri: Uri, timeoutMs: Long = 60_000L): Boolean {
        if (cache() == null) return false
        val videoId = uri.getQueryParameter("v") ?: return false
        val watch = Uri.parse("bitchord://watch?v=$videoId")
        if (renditionsOf(watch).any { it.isComplete && !it.key.endsWith(altSuffix) }) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        if (!awaitAnalysisHead(uri, timeoutMs)) return false
        while (videoId in analysisHeadsInFlight && SystemClock.elapsedRealtime() < deadline) {
            delay(100)
        }
        return true
    }

    suspend fun awaitRenditionPrefix(videoId: String, minBytes: Long, timeoutMs: Long): Boolean {
        val cache = cache() ?: return false
        if (alignmentPrefixBytes(videoId) >= minBytes) {
            refreshRenditionKeys(videoId)
            return true
        }
        val keys = LinkedHashSet<String>().apply {
            add(videoId)
            add("$videoId#alt")
            add("$videoId#alt-noatmos")
            addAll(renditionKeysFor(videoId))
        }
        val reached = CompletableDeferred<Unit>()
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                if (alignmentPrefixBytes(videoId) >= minBytes) reached.complete(Unit)
            }

            override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        }
        keys.forEach { key -> runCatching { cache.addListener(key, listener) } }
        return try {
            val ready = if (alignmentPrefixBytes(videoId) >= minBytes) {
                true
            } else {
                withTimeoutOrNull(timeoutMs) { reached.await() } != null
            }
            // The wait can see the bytes through the direct cache-key reads
            // while renditionKeysFor's five-second memo still lacks the key
            // they landed under. The caller re-checks through that memo, so
            // without dropping it here the loop spins until the memo expires.
            if (ready) refreshRenditionKeys(videoId)
            ready
        } finally {
            keys.forEach { key -> runCatching { cache.removeListener(key, listener) } }
        }
    }
}
