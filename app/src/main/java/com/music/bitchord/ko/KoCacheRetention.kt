package com.music.bitchord.ko

import android.os.SystemClock
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.CachedRenditionBar
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.BatteryPolicy
import com.music.bitchord.playback.StreamChoice
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

    /**
     * Tracks whose analysis head was asked for in the last [ANALYSIS_KEEP_MS].
     *
     * Automix's head and tail reads and the alignment waits ask for a track's
     * analysis-only copy and then come back for it. The drop pass used to
     * remove that copy in between (it runs 30 s after every track change), and
     * the next ask downloaded the whole file again: on the 11-08 phone log every
     * one of 79 repeat rounds followed a drop pass, 281 MB of 927 MB read-ahead.
     * Kept apart from [timingKeep] so these asks cannot push a timing keep out.
     */
    private val analysisKeep = LinkedHashMap<String, Long>()

    fun keepForAnalysis(videoId: String, nowMs: Long = SystemClock.elapsedRealtime()) = synchronized(analysisKeep) {
        analysisKeep.remove(videoId)
        analysisKeep[videoId] = nowMs
        while (analysisKeep.size > ANALYSIS_KEEP_MAX) analysisKeep.remove(analysisKeep.keys.first())
    }

    fun keptForAnalysis(videoId: String, nowMs: Long = SystemClock.elapsedRealtime()): Boolean = synchronized(analysisKeep) {
        val at = analysisKeep[videoId] ?: return false
        nowMs - at < ANALYSIS_KEEP_MS
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

    /**
     * Refused copies that could not be removed because the player was reading
     * them (AudioCache.dropRefusedRenditions). A copy the queue never lets go of
     * by origin (a played JioSaavn or YouTube-keyed `#alt` file) is otherwise
     * never dropped and replays the other recording on every later play.
     */
    private val pendingRefusedDrops = ConcurrentHashMap.newKeySet<String>()

    /** Review 11-17 (LEFT-4): drop [key] on a later pass, once nothing holds it. */
    fun deferRefusedDrop(key: String) {
        pendingRefusedDrops.add(key)
    }

    fun dropForeignEntries(
        cache: Cache,
        keep: Set<String>,
        keepAnalysis: Boolean,
        keepsAddonCopy: (String) -> Boolean,
        scope: CoroutineScope,
        onDropped: (String) -> Unit,
        isRefusedCopy: (String) -> Boolean = { false },
        playerHoldsKey: (String) -> Boolean = { true },
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
                // P2-0: skip what must stay. dd5380b6b had this inverted, so the
                // pass removed the playing/next addon copies and kept the partials.
                if (!cacheEntryShouldDrop(
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
                        keptForAnalysis = mediaId != null && keptForAnalysis(mediaId),
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
            dropPendingRefused(cache, keep, isRefusedCopy, playerHoldsKey, onDropped)
        }
    }

    private fun dropPendingRefused(
        cache: Cache,
        keep: Set<String>,
        isRefusedCopy: (String) -> Boolean,
        playerHoldsKey: (String) -> Boolean,
        onDropped: (String) -> Unit,
    ) {
        pendingRefusedDrops.toList().forEach { key ->
            val meta = runCatching { cache.getContentMetadata(key) }.getOrNull()
            val mediaId = meta?.get(META_MEDIA_ID, null as String?) ?: key.substringBefore('#')
            val refused = isRefusedCopy(key)
            // Gone, or its row is no longer refused: nothing left to do for it.
            if (!refused) {
                pendingRefusedDrops.remove(key)
                return@forEach
            }
            if (!refusedCopyShouldDrop(
                    refused = refused,
                    keptByQueueOrTiming = mediaId in keep || keptForTiming(mediaId),
                    playerHolds = playerHoldsKey(key),
                )
            ) {
                return@forEach
            }
            runCatching { cache.removeResource(key) }
                .onSuccess {
                    pendingRefusedDrops.remove(key)
                    forget(key)
                    onDropped(mediaId)
                    TrackLog.w(tag, "dropped refused cached copy $key once the player let go of it", about = mediaId)
                }
                .onFailure { TrackLog.d(tag, "refused copy $key still in use: ${it.message}") }
        }
    }

    /** Kept upgrade renditions already logged, so each pass does not log them again. */
    private val keptUpgradeLogged = ConcurrentHashMap.newKeySet<String>()

    /**
     * Review 11-20 (AHEAD-1): whether the complete `#hifi` entry [key] outlives
     * the queue, see [koKeepsUpgradeRendition]. Reads the entry's own metadata
     * and [StreamChoice]'s in-memory record; nothing on the network.
     */
    fun keepsUpgradeRendition(cache: Cache, key: String): Boolean {
        val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: return false
        val mediaId = meta.get(META_MEDIA_ID, null as String?) ?: key.substringBefore('#')
        val sounding = StreamChoice.sounding(mediaId)
        val kept = koKeepsUpgradeRendition(
            markedLossless = meta.get(META_HQ_LOSSLESS, null as String?) == "1",
            servedSource = meta.get(META_SERVED_SOURCE, null as String?),
            soundsSubstitute = StreamChoice.soundsSubstitute(mediaId),
            soundingSourceId = sounding?.sourceConfigId,
            soundingTrackId = sounding?.trackId,
        )
        if (kept && keptUpgradeLogged.add(key)) {
            TrackLog.d(tag, "hq-cache: $key kept past the queue (complete lossless, its clock still known)", about = mediaId)
        }
        return kept
    }

    private companion object {
        private const val TIMING_KEEP_MS = 15 * 60_000L
        private const val TIMING_KEEP_MAX = 16

        /** Long enough for a track's head and tail reads; bounded so analysis copies still go. */
        private const val ANALYSIS_KEEP_MS = 10 * 60_000L
        private const val ANALYSIS_KEEP_MAX = 12

        private const val META_ORIGIN = "bc_origin"
        private const val META_MEDIA_ID = "bc_media_id"
        private const val META_PLAYBACK = "bc_playback"
        private const val META_ANALYSIS = "bc_analysis"
        private const val META_PLAYED = "bc_played"
        private const val META_SERVED_SOURCE = "bc_served_source"
        private const val META_HQ_LOSSLESS = "bc_hq_lossless"
    }
}

/**
 * v1.9's kept-addon-copy rule, minus the refusal check the cache applies on top:
 * complete, audio by its first bytes ([audio]), and not an upgrade's `#hifi`
 * rendition unless [keepsUpgradeRendition] keeps it. Lazy, in the order of cost:
 * the container probe reads the file's head.
 */
internal fun koKeepsAddonCopy(
    key: String,
    complete: (String) -> Boolean,
    audio: (String) -> Boolean,
    keepsUpgradeRendition: (String) -> Boolean,
): Boolean = (!key.contains("#hifi") || keepsUpgradeRendition(key)) && complete(key) && audio(key)

/**
 * Review 11-20 (AHEAD-1): a complete `#hifi` entry is what a later plain play
 * opens ([KoLosslessCache.completeUpgradeRendition]), and the song's lower
 * copies were already deleted for it ([KoLosslessCache.dropLowerQualityAfterLosslessSwap]).
 * Dropped once the queue moved on, every replay fetched the Opus copy, the
 * proof head and the whole FLAC again.
 *
 * Kept only when it is a lossless rendition ([markedLossless], written when the
 * fill completed) and the lyric clock a plain play would use is this copy's:
 * [StreamChoice] still names its source row ([servedSource]) as the substitute
 * sounding. That record is in memory only; after a restart the lyric clock of a
 * plain play falls back to YouTube's, so the entry goes at the first pass as
 * before rather than play a FLAC with lines on another clock.
 */
internal fun koKeepsUpgradeRendition(
    markedLossless: Boolean,
    servedSource: String?,
    soundsSubstitute: Boolean,
    soundingSourceId: String?,
    soundingTrackId: String?,
): Boolean = markedLossless && soundsSubstitute && soundingSourceId != null && soundingTrackId != null &&
    CachedRenditionBar.servedBy(servedSource, soundingSourceId, soundingTrackId)

/**
 * Review 11-17 (LEFT-4): a refused copy goes once the queue window and the
 * timing keeps have let go of its song and the player no longer reads it.
 * Deleting it under a reader would corrupt the play that is using it.
 */
internal fun refusedCopyShouldDrop(
    refused: Boolean,
    keptByQueueOrTiming: Boolean,
    playerHolds: Boolean,
): Boolean = refused && !keptByQueueOrTiming && !playerHolds

/**
 * The one purge rule, shared by the index scan and the drop pass.
 *
 * [keepsAddonCopy] is v1.9's complete-audio test: an addon copy that is whole
 * and decodable may be read back off disk, so it outlives the queue moving on.
 * [keepAnalysis] spares Automix's analysis-only copies for callers that are
 * only dropping addon entries. [keptByQueueOrTiming] covers the playhead window
 * and [KoCacheRetention.keptForTiming]'s analysis keeps. [keptForAnalysis]
 * ([KoCacheRetention.keptForAnalysis]) spares only an analysis-only copy, never
 * a partial addon file, which must still go.
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
    keptForAnalysis: Boolean = false,
): Boolean {
    if (keptByQueueOrTiming) return false
    val foreign = origin == foreignOrigin && !keepsAddonCopy
    val analysisOnly = hasAnalysis && !hasPlayback && !hasPlayed && !keepAnalysis && !keptForAnalysis
    return foreign || analysisOnly
}
