package com.music.bitchord.ko

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.CacheRenditionChoice
import com.music.bitchord.playback.CachedRendition
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.mediaIdIn
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [KO] hq-lossless
 *
 * Verified-lossless prefetch, whole-rendition caching and the lower-quality
 * cleanup that follows a swap. These are fork additions layered on the
 * upstream cache; all cache access is through providers so the upstream file
 * only calls in.
 */
class KoLosslessCache(
    private val cache: () -> SimpleCache?,
    private val keyFactory: CacheKeyFactory,
    private val scope: CoroutineScope,
    private val upstreamFactory: () -> DataSource.Factory?,
    private val readAheadCacheFactory: (DataSource.Factory) -> CacheDataSource.Factory,
    private val renditionKeysFor: (String) -> List<String>,
    private val renditionKeysClear: (String) -> Unit,
    private val keysOf: (String) -> List<String>,
    private val isComplete: (String) -> Boolean,
    private val isLosslessRendition: (String) -> Boolean,
    private val containerOf: (String) -> Pair<String, String>?,
    private val forgetPurgeCandidate: (String) -> Unit,
    private val discardRendition: (Uri) -> Unit,
    private val tracker: KoPlaybackReadTracker,
) {
    private val tag = "BitChord"

    /** Whole-rendition downloads in flight, so a queue move can stop the old one. */
    private val wholeRenditionJobs = ConcurrentHashMap<String, Job>()

    private val losslessPrefetchAttempted = ConcurrentHashMap.newKeySet<String>()

    private var losslessPrefetchJob: Job? = null

    @Volatile
    private var losslessPrefetchFor: String? = null

    private val pendingLowQualityDrops = ConcurrentHashMap<String, String>()

    private val upgradeKeyDeferred = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var currentMediaId: String? = null

    private class WholeRenditionResult(
        val complete: Boolean,
        val bytes: Long,
        val failure: String?,
    )

    /** A complete upgraded rendition of [videoId], if one is on disk. */
    fun completeUpgradeRendition(videoId: String): String? {
        if (cache() == null) return null
        // A copy prefetched during this play must not take over its key until
        // the queue moves on: a seek reopens the plain URI, and handing it the
        // FLAC entry at the Opus entry's byte offset breaks the extractor.
        if (videoId in upgradeKeyDeferred) return null
        val candidates = renditionKeysFor(videoId).mapNotNull { key ->
            if (key.endsWith("#watch")) return@mapNotNull null
            val suffix = key.removePrefix("$videoId#")
            if (suffix == key) return@mapNotNull null
            val order = QualityUpgrade.upgradeGeneration(suffix)
            if (order <= 0) return@mapNotNull null
            CachedRendition(
                key = key,
                complete = isComplete(key),
                lossless = isLosslessRendition(key),
                upgradeOrder = order,
            )
        }
        if (candidates.isEmpty()) return null
        return CacheRenditionChoice.choose(videoId, candidates).takeIf { it != videoId }
    }

    fun hasCompleteLosslessCopy(mediaId: String): Boolean {
        val cache = cache() ?: return false
        if (mediaId.isBlank()) return false
        return keysOf(mediaId)
            .filterNot { it.endsWith("#watch") }
            .any { key ->
                val lossless = isLosslessRendition(key) ||
                    containerOf(key)?.first?.let { it in LOSSLESS_CONTAINERS } == true
                if (!lossless) return@any false
                runCatching {
                    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                    length > 0 && cache.isCached(key, 0, length)
                }.getOrDefault(false)
            }
    }

    suspend fun cacheWholeRendition(uri: Uri, title: String?) {
        cacheWholeRenditionDetailed(uri, title)
    }

    private suspend fun cacheWholeRenditionDetailed(uri: Uri, title: String?): WholeRenditionResult {
        val cache = cache() ?: return WholeRenditionResult(false, 0L, "no cache")
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val mediaId = mediaIdIn(uri)
        val job = coroutineContext.job
        mediaId?.let { wholeRenditionJobs[it] = job }
        // The cache index and its metadata are not main-thread data: callers
        // reach this from playback on Main, and `getCachedSpans`, `isComplete`
        // and the metadata write all take the cache's lock. The whole fetch
        // and check therefore run on IO, whatever dispatcher asked.
        return withContext(Dispatchers.IO) {
            // [uri] is the virtual `bitchord://watch?…&q=hifi` item, so its own
            // authority is not the server. The parked stream names the real host.
            val stream = QualityUpgrade.forcedStream(uri)
            val host = stream?.url?.let(HqHostTracker::hostOf)
                ?: HqHostTracker.hostOf(uri.toString())
            try {
                var failure: String? = null
                for (attempt in 0 until 2) {
                    tracker.awaitRelease(key)
                    val upstream = upstreamFactory()
                        ?: return@withContext WholeRenditionResult(false, 0L, "no upstream")
                    val source = readAheadCacheFactory(upstream).createDataSource()
                    val spec = DataSpec.Builder().setUri(uri).build()
                    val writer = CacheWriter(source, spec, /* temporaryBuffer = */ null, /* listener = */ null)
                    failure = runCatching {
                        val handle = coroutineContext.job.invokeOnCompletion { writer.cancel() }
                        try {
                            writer.cache()
                        } finally {
                            handle.dispose()
                        }
                    }.exceptionOrNull()?.let { error ->
                        if (error is CancellationException) throw error
                        error.message
                    }
                    if (failure == null) break
                }
                val bytes = cache.getCachedSpans(key).filter { it.isCached }.sumOf { it.length }
                val complete = failure == null && isComplete(key)
                if (complete && stream != null && HqQuality.isLosslessTier(stream.format)) {
                    runCatching {
                        cache.applyContentMetadataMutations(
                            key,
                            ContentMetadataMutations().set(META_HQ_LOSSLESS, "1"),
                        )
                    }
                }
                val label = title?.takeIf { it.isNotBlank() } ?: mediaId ?: key
                if (complete) {
                    mediaId?.let(renditionKeysClear)
                    TrackLog.d(tag, "hq-cache: $label fully cached ${formatBytes(bytes)}", about = mediaId)
                    return@withContext WholeRenditionResult(true, bytes, null)
                }
                val reason = failure ?: "incomplete"
                TrackLog.d(
                    tag,
                    "hq-cache: $label stopped short at ${formatBytes(bytes)}: $reason",
                    about = mediaId,
                )
                WholeRenditionResult(false, bytes, reason)
            } finally {
                mediaId?.let {
                    wholeRenditionJobs.remove(it, job)
                    HqHostTracker.endDownload(it, host)
                }
            }
        }
    }

    fun prefetchVerifiedRendition(uri: Uri, title: String?, stream: SourceStream): Boolean {
        val mediaId = mediaIdIn(uri) ?: return false
        if (!HqQuality.isLosslessTier(stream.format)) return false
        if (!losslessPrefetchAttempted.add(mediaId)) return false
        upgradeKeyDeferred += mediaId
        losslessPrefetchJob?.cancel()
        // Same park as a manual swap: the resolving data source reads the
        // stream from here, so its origin and host are recorded on the entry
        // exactly as they are for the copy a swap fills.
        QualityUpgrade.force(mediaId, stream)
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val host = HqHostTracker.hostOf(stream.url)
        losslessPrefetchFor = mediaId
        losslessPrefetchJob = scope.launch(TrackLog.about(mediaId)) {
            val complete = fetchLosslessRendition(uri, title, stream, mediaId, host)
            if (!complete) return@launch
            pendingLowQualityDrops[mediaId] = key
            if (currentMediaId != null && currentMediaId != mediaId &&
                pendingLowQualityDrops.remove(mediaId, key)
            ) {
                dropLowerQualityCopies(mediaId, keep = key)
            }
        }
        return true
    }

    fun cancelLosslessPrefetchFor(mediaId: String?) {
        if (mediaId == null || losslessPrefetchFor != mediaId) return
        // The row this fetch belongs to is now playing. A timed-out next-row
        // prefetch is deliberately left running; once that row becomes current
        // it must not be aborted by the following row's schedule, or the
        // playing track loses its in-progress lossless copy.
        if (mediaId == currentMediaId) return
        val active = losslessPrefetchJob?.isActive == true
        if (active) losslessPrefetchJob?.cancel()
        losslessPrefetchJob = null
        losslessPrefetchFor = null
        if (active) losslessPrefetchAttempted.remove(mediaId)
    }

    suspend fun downloadVerifiedRendition(uri: Uri, title: String?, stream: SourceStream): Boolean {
        val cache = cache() ?: return false
        val mediaId = mediaIdIn(uri) ?: return false
        if (!HqQuality.isLosslessTier(stream.format)) return false
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        losslessPrefetchJob?.takeIf { it.isActive }?.join()
        // Whether the `#hifi` entry already holds *this* stream's bytes, asked
        // before [force] overwrites the record of what filled it. The key names
        // a slot, not a file: a partial left by another candidate must be
        // thrown away rather than resumed, or the two files are spliced into
        // one entry. The same guard [PlaybackService.swapIn] uses.
        val alreadyFilled = QualityUpgrade.forcedStream(uri)?.url == stream.url
        // Park the stream even when the bytes are already complete: the player
        // is about to open the marked URI and the resolving data source must
        // hand it this exact URL rather than searching for another one.
        upgradeKeyDeferred += mediaId
        QualityUpgrade.force(mediaId, stream)
        return withContext(Dispatchers.IO) {
            if (!alreadyFilled) discardRendition(uri)
            if (isComplete(key)) {
                losslessPrefetchAttempted.add(mediaId)
                true
            } else {
                losslessPrefetchAttempted.add(mediaId)
                val host = HqHostTracker.hostOf(stream.url)
                fetchLosslessRendition(uri, title, stream, mediaId, host)
            }
        }
    }

    private suspend fun fetchLosslessRendition(
        uri: Uri,
        title: String?,
        stream: SourceStream,
        mediaId: String,
        host: String?,
    ): Boolean {
        HqHostTracker.beginDownload(mediaId, host)
        var complete = false
        try {
            TrackLog.d(tag, "hq-prefetch: $mediaId start ${stream.format.summary}", about = mediaId)
            val result = try {
                cacheWholeRenditionDetailed(uri, title)
            } catch (cancelled: CancellationException) {
                TrackLog.d(tag, "hq-prefetch: $mediaId failed cancelled", about = mediaId)
                throw cancelled
            } catch (failed: Exception) {
                WholeRenditionResult(false, 0L, failed.message ?: failed.javaClass.simpleName)
            }
            if (!result.complete) {
                TrackLog.d(
                    tag,
                    "hq-prefetch: $mediaId failed ${result.failure ?: "incomplete"}",
                    about = mediaId,
                )
                return false
            }
            complete = true
            TrackLog.d(tag, "hq-prefetch: $mediaId done ${formatBytes(result.bytes)}", about = mediaId)
            renditionKeysClear(mediaId)
            return true
        } finally {
            // A fetch cut short — most often by the queue moving on before
            // it finished — leaves the next play of the track free to try
            // again; only a complete copy closes the question.
            if (!complete) losslessPrefetchAttempted.remove(mediaId)
            HqHostTracker.endDownload(mediaId, host)
        }
    }

    fun dropLowerQualityAfterLosslessSwap(mediaId: String, keepKey: String) {
        pendingLowQualityDrops.remove(mediaId)
        scope.launch(TrackLog.about(mediaId)) {
            dropLowerQualityCopies(mediaId, keepKey)
        }
    }

    fun onTrackBecameCurrent(mediaId: String?) {
        currentMediaId = mediaId
        upgradeKeyDeferred.removeAll { it != mediaId }
        val ready = pendingLowQualityDrops.entries
            .filter { it.key != mediaId }
            .map { it.key to it.value }
        ready.forEach { (id, keep) ->
            if (pendingLowQualityDrops.remove(id, keep)) {
                scope.launch(TrackLog.about(id)) { dropLowerQualityCopies(id, keep) }
            }
        }
    }

    private suspend fun dropLowerQualityCopies(mediaId: String, keep: String) {
        val cache = cache() ?: return
        renditionKeysClear(mediaId)
        val keys = renditionKeysFor(mediaId).filter { key ->
            if (key == keep || key.endsWith("#watch")) return@filter false
            val suffix = key.removePrefix("$mediaId#")
            suffix == key || suffix == "alt" || suffix == "alt-noatmos"
        }
        for (key in keys) {
            if (!isComplete(key)) continue
            tracker.awaitRelease(key)
            runCatching { cache.removeResource(key) }
                .onSuccess {
                    forgetPurgeCandidate(key)
                    TrackLog.d(
                        tag,
                        "hq-prefetch: $mediaId removed lower-quality copy $key",
                        about = mediaId,
                    )
                }
                .onFailure {
                    TrackLog.d(
                        tag,
                        "hq-prefetch: $mediaId kept lower-quality copy $key: ${it.message}",
                        about = mediaId,
                    )
                }
        }
        renditionKeysClear(mediaId)
    }

    fun cancelWholeRenditionsExcept(mediaId: String?) {
        wholeRenditionJobs.entries
            .filter { it.key != mediaId }
            .forEach { entry ->
                entry.value.cancel()
                wholeRenditionJobs.remove(entry.key, entry.value)
            }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(java.util.Locale.ROOT, mb / 1024) else {
            "%.1f MB".format(java.util.Locale.ROOT, mb)
        }
    }

    private companion object {
        private const val META_HQ_LOSSLESS = "bc_hq_lossless"

        /** Containers that are lossless whatever their metadata says. */
        private val LOSSLESS_CONTAINERS = setOf("flac", "wav")
    }
}
