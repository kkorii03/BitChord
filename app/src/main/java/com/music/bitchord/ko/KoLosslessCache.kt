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
    private val isKeptOrigin: (String) -> Boolean,
    private val isRefused: (String) -> Boolean,
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

    /** The host [losslessPrefetchJob] downloads from; see [koSwapWaitsForPrefetch]. */
    @Volatile
    private var losslessPrefetchHost: String? = null

    private val pendingLowQualityDrops = ConcurrentHashMap<String, String>()

    private val upgradeKeyDeferred = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var currentMediaId: String? = null

    private val containerMemo = KoContainerMemo(
        probe = containerOf,
        contentLengthOf = ::contentLengthOf,
    )

    private class WholeRenditionResult(
        val complete: Boolean,
        val bytes: Long,
        val failure: String?,
    )

    /**
     * The complete rendition of [videoId] a plain play should open: a finished
     * upgrade, or a finished lossless copy that has no upgrade generation.
     *
     * The losslessness is read from the entry's own persisted metadata, so it
     * survives a restart with the bytes it describes: after one, the metadata
     * is all that is left of the in-memory stream choice.
     */
    fun completeUpgradeRendition(videoId: String): String? {
        if (cache() == null) return null
        // A copy prefetched during this play must not take over its key until
        // the queue moves on: a seek reopens the plain URI, and handing it the
        // FLAC entry at the Opus entry's byte offset breaks the extractor.
        if (videoId in upgradeKeyDeferred) return null
        val candidates = renditionCandidates(videoId)
        val chosen = if (candidates.isEmpty()) {
            null
        } else {
            CacheRenditionChoice.choose(videoId, candidates).takeIf { it != videoId }
        }
        // Review 11-17 (RG1-1): a copy refused while it plays is not taken away
        // from this play's seeks, the same reason as the deferral above.
        return koKeepRefusedPlayingCopy(chosen, tracker.reopenPinFor(videoId), isComplete, isRefused)
    }

    private fun renditionCandidates(videoId: String): List<CachedRendition> =
        losslessRenditionCandidates(
            videoId = videoId,
            keys = renditionKeysFor(videoId),
            isComplete = isComplete,
            markedLossless = isLosslessRendition,
            keptOrigin = isKeptOrigin,
            containerOf = ::containerForCompleteCopy,
            refused = isRefused,
        )

    /** Whether [key] is lossless by its marker or by the container it holds. */
    private fun isLosslessCopy(key: String): Boolean =
        isLosslessRendition(key) ||
            containerForCompleteCopy(key)?.first?.let { it in KO_LOSSLESS_CONTAINERS } == true

    fun hasCompleteLosslessCopy(mediaId: String): Boolean {
        val cache = cache() ?: return false
        if (mediaId.isBlank()) return false
        return keysOf(mediaId)
            .filterNot { it.endsWith("#watch") }
            .any { key ->
                val complete = runCatching {
                    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                    length > 0 && cache.isCached(key, 0, length)
                }.getOrDefault(false)
                if (!complete) return@any false
                isLosslessCopy(key)
            }
    }

    private fun contentLengthOf(key: String): Long {
        val cache = cache() ?: return -1L
        return runCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(key))
        }.getOrDefault(-1L)
    }

    /**
     * The container of a copy that [isComplete] already made sure is whole.
     * The memo keeps a playback key resolution from reopening the file.
     */
    private fun containerForCompleteCopy(key: String): Pair<String, String>? =
        containerMemo.containerOf(key)

    /** Drops both the memoized key list and the container remembered for [mediaId]. */
    private fun clearRendition(mediaId: String) {
        renditionKeysClear(mediaId)
        containerMemo.forget(mediaId)
    }

    /**
     * @param releasesHost whether the end of this read also ends the download the
     *   caller began on the host. A read in the middle of a swap passes false: the
     *   audition and the player are still about to read from that host, and ending
     *   it here let other work open a second request to it, the 429 the gate exists for.
     */
    suspend fun cacheWholeRendition(uri: Uri, title: String?, releasesHost: Boolean = true) {
        cacheWholeRenditionDetailed(uri, title, releasesHost)
    }

    private suspend fun cacheWholeRenditionDetailed(
        uri: Uri,
        title: String?,
        releasesHost: Boolean = true,
        /** [KO] UPG-7: the length the verified head's file stated; a fill of another length is not complete. */
        expectedLength: Long = -1L,
    ): WholeRenditionResult {
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
                            // Only the playing track's fills: a next row's fill is the only claim its FLAC, later read from cache, carries.
                            KoCacheFill.run(marks = mediaId != null && mediaId == currentMediaId) { writer.cache() }
                        } finally {
                            handle.dispose()
                        }
                    }.exceptionOrNull()?.let { error ->
                        if (error is CancellationException) throw error
                        error.message
                    }
                    if (failure == null) break
                }
                // [KO] UPG-7: a head spliced onto another file states another length.
                // dupdl: a head a player's open laid in promised its length too.
                val expected = if (expectedLength > 0L) expectedLength else KoVerifiedHead.promisedLength(key, stream?.url)
                if (failure == null && !KoVerifiedHead.lengthAgrees(expected, contentLengthOf(key))) {
                    failure = "length ${contentLengthOf(key)} is not the verified file's $expected"
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
                    mediaId?.let(::clearRendition)
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
                    if (releasesHost) HqHostTracker.endDownload(it, host)
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
        losslessPrefetchHost = host
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
        losslessPrefetchHost = null
        if (active) losslessPrefetchAttempted.remove(mediaId)
    }

    /** @param takesLossy the automatic swap's lossy fallback; see [KoQualityUpgrade.automaticSwapTakes]. */
    suspend fun downloadVerifiedRendition(
        uri: Uri,
        title: String?,
        stream: SourceStream,
        takesLossy: Boolean = false,
    ): Boolean {
        val cache = cache() ?: return false
        val mediaId = mediaIdIn(uri) ?: return false
        val wanted = if (takesLossy) {
            KoQualityUpgrade.automaticSwapTakes(stream.format)
        } else {
            HqQuality.isLosslessTier(stream.format)
        }
        if (!wanted) return false
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        losslessPrefetchJob?.takeIf { it.isActive }?.let { running ->
            val prefetchFor = losslessPrefetchFor
            val streamHost = HqHostTracker.hostOf(stream.url)
            if (koSwapWaitsForPrefetch(prefetchFor, losslessPrefetchHost, mediaId, streamHost)) {
                val started = android.os.SystemClock.elapsedRealtime()
                running.join()
                TrackLog.d(
                    tag,
                    "hq-swap: $mediaId waited ${android.os.SystemClock.elapsedRealtime() - started}ms " +
                        "for prefetch of $prefetchFor",
                    about = mediaId,
                )
            }
        }
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
                // [KO] UPG-7: start from the head the sound check accepted, then fetch the rest.
                val seeded = withContext(Dispatchers.IO) { seedFromVerifiedHead(uri, stream, mediaId) }
                if (seeded == null) {
                    cacheWholeRenditionDetailed(uri, title)
                } else {
                    // The host stays held across the whole-file fallback below.
                    val fromHead = cacheWholeRenditionDetailed(uri, title, releasesHost = false, expectedLength = seeded)
                    if (fromHead.complete) {
                        fromHead
                    } else {
                        // Never leave a mixed entry behind: throw it away and fetch the file whole, as before.
                        TrackLog.d(
                            tag,
                            "hq-cache: $mediaId verify head dropped (${fromHead.failure ?: "incomplete"}); fetching whole",
                            about = mediaId,
                        )
                        withContext(Dispatchers.IO) { discardRendition(uri) }
                        cacheWholeRenditionDetailed(uri, title)
                    }
                }
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
            clearRendition(mediaId)
            return true
        } finally {
            // A fetch cut short — most often by the queue moving on before
            // it finished — leaves the next play of the track free to try
            // again; only a complete copy closes the question.
            if (!complete) losslessPrefetchAttempted.remove(mediaId)
            HqHostTracker.endDownload(mediaId, host)
        }
    }

    /**
     * [KO] UPG-7: lays the accepted verify head of exactly [stream] into [uri]'s
     * empty entry as one span, so the fill that follows reads only the rest, with
     * a Range. Runs on IO. Any doubt leaves the entry as it was (the old whole
     * download); the held file is deleted whatever happens.
     *
     * @return the whole file's length the head response stated, for the fill to
     *   check; null when nothing was laid in.
     */
    private fun seedFromVerifiedHead(uri: Uri, stream: SourceStream, mediaId: String): Long? {
        val cache = cache() ?: return null
        val held = KoVerifiedHead.take(stream.url, stream.sourceConfigId, stream.trackId)
        if (held == null) {
            if (KoVerifiedHead.heldCount() > 0) {
                TrackLog.d(tag, "hq-cache: $mediaId verify head not used: held for another stream", about = mediaId)
            }
            return null
        }
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        var target: java.io.File? = null
        try {
            val cachedBytes = runCatching {
                cache.getCachedSpans(key).filter { it.isCached }.sumOf { it.length }
            }.getOrDefault(-1L)
            val plan = KoVerifiedHead.plan(held.bytes, held.file.length(), held.totalBytes, cachedBytes, contentLengthOf(key))
            if (plan.length <= 0L) {
                TrackLog.d(tag, "hq-cache: $mediaId verify head not used: ${plan.why}", about = mediaId)
                return null
            }
            // Non-blocking: a writer already on this entry wins, and the head is dropped.
            val hole = cache.startReadWriteNonBlocking(key, 0L, plan.length)
            if (hole == null || hole.isCached) {
                TrackLog.d(tag, "hq-cache: $mediaId verify head not used: entry busy", about = mediaId)
                return null
            }
            try {
                if (hole.length < plan.length) {
                    TrackLog.d(tag, "hq-cache: $mediaId verify head not used: entry not empty", about = mediaId)
                    return null
                }
                val file = cache.startFile(key, 0L, plan.length)
                target = file
                if (!KoVerifiedHead.moveInto(held.file, file, plan.length)) {
                    TrackLog.d(tag, "hq-cache: $mediaId verify head not used: move failed", about = mediaId)
                    return null
                }
                cache.commitFile(file, plan.length)
                target = null
            } finally {
                cache.releaseHoleSpan(hole)
            }
            TrackLog.d(
                tag,
                "hq-cache: $mediaId adopted from verify ${formatBytes(plan.length)} of " +
                    "${formatBytes(held.totalBytes)} (${plan.why}); fetching the rest",
                about = mediaId,
            )
            return held.totalBytes
        } catch (failed: Exception) {
            TrackLog.d(tag, "hq-cache: $mediaId verify head not used: ${failed.message}", about = mediaId)
            return null
        } finally {
            target?.delete()
            held.file.delete()
        }
    }

    /**
     * [KO] dupdl: a player opening an upgraded [uri] (the next track's standby
     * re-prepared as FLAC, the window's proof, a lead) starts from the head the
     * sound check accepted for exactly that stream, instead of reading the whole
     * file from byte 0 again. 11-19 night log: 12 heads of 19-31 MB were kept
     * and then fetched again in full by the next player.
     *
     * Runs on the player's loader thread, before its cache open. Only into an
     * empty entry, asked before the head is taken, so an entry that already
     * holds bytes leaves the head to the swap that can discard and use it.
     * The length the check saw is promised for [checkPlayerSeed].
     */
    fun seedForPlayer(uri: Uri) {
        if (KoVerifiedHead.heldCount() == 0) return
        val stream = QualityUpgrade.forcedStream(uri) ?: return
        if (!KoVerifiedHead.holdsAccepted(stream.url, stream.sourceConfigId, stream.trackId)) return
        val cache = cache() ?: return
        val mediaId = mediaIdIn(uri) ?: return
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val cachedBytes = runCatching {
            cache.getCachedSpans(key).filter { it.isCached }.sumOf { it.length }
        }.getOrDefault(-1L)
        if (cachedBytes != 0L || contentLengthOf(key) > 0L) return
        val total = seedFromVerifiedHead(uri, stream, mediaId) ?: return
        KoVerifiedHead.promise(key, stream.url, total)
        TrackLog.d(tag, "hq-cache: $mediaId player opens on the verify head; the rest by Range", about = mediaId)
    }

    /**
     * [KO] dupdl: a player's reader of [uri] closed. Once its fill states the
     * file's length, one other than the length the verify head's file stated is
     * another file spliced onto that head (the UPG-7 rule): the entry is dropped
     * so nothing keeps or replays it. Runs on the closing reader's thread.
     */
    fun checkPlayerSeed(uri: Uri) {
        if (!KoVerifiedHead.hasPromises()) return
        val stream = QualityUpgrade.forcedStream(uri) ?: return
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val stated = contentLengthOf(key)
        when (KoVerifiedHead.settle(key, stream.url, stated)) {
            KoVerifiedHead.Splice.AGREES ->
                TrackLog.d(tag, "hq-cache: ${mediaIdIn(uri)} verify head and the rest agree on ${formatBytes(stated)}", about = mediaIdIn(uri))
            KoVerifiedHead.Splice.DIFFERS -> {
                TrackLog.w(tag, "hq-cache: ${mediaIdIn(uri)} rest states ${formatBytes(stated)}, not the verified file's; dropping the entry", about = mediaIdIn(uri))
                discardRendition(uri)
                // Still stated: a live reader kept it, and the next close asks again.
                if (contentLengthOf(key) <= 0L) KoVerifiedHead.forgetPromise(key)
            }
            KoVerifiedHead.Splice.NONE -> Unit
        }
    }

    fun dropLowerQualityAfterLosslessSwap(mediaId: String, keepKey: String) {
        pendingLowQualityDrops.remove(mediaId)
        scope.launch(TrackLog.about(mediaId)) {
            dropLowerQualityCopies(mediaId, keepKey)
        }
    }

    /** Review SW7A-2: [completeUpgradeRendition] answers null for [videoId] until the queue moves on. */
    fun deferUpgradeKey(videoId: String) {
        upgradeKeyDeferred += videoId
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
        clearRendition(mediaId)
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
        clearRendition(mediaId)
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
    }
}

/**
 * Review OPT1 (hq-path-join-foreign-prefetch): whether a swap download for
 * [mediaId] waits for the running prefetch. The same row keeps one writer on
 * its entry, and the same host (or one not known) keeps the old queueing in
 * front of a 429. Only another row's fetch from another host no longer holds
 * the playing song's swap for a whole next-song FLAC.
 */
internal fun koSwapWaitsForPrefetch(
    prefetchFor: String?,
    prefetchHost: String?,
    mediaId: String,
    streamHost: String?,
): Boolean =
    prefetchFor == null || prefetchFor == mediaId ||
        prefetchHost == null || streamHost == null || prefetchHost == streamHost
