package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.ChromaAlignment
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.HqMatchPolicy
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqReferenceCache
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.ProxyAwareDataSourceFactory
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Proves that a high-quality candidate is the same recording as the YouTube
 * song version before anything is allowed to play it.
 *
 * The comparison is the one the app already owns: ninety seconds of both
 * recordings become chroma profiles ([ChromaAlignment]), the profiles are
 * aligned, and the answer gives a match percentage, a start offset and a
 * speed ratio. The alignment maths is untouched; [HqMatchPolicy] decides
 * whether the answer is good enough.
 *
 * The reference is always the YouTube song version ??the sound the lyrics
 * were timed against. A candidate that is a cover, a live take, a remix or a
 * different arrangement is refused, and the refusal is remembered per
 * (YouTube id, source track id) so the same search does not pay for it twice.
 *
 * Runs once, at next-song preparation. Nothing here polls or schedules
 * itself; callers are the read-ahead, the search prefetch and the upgrade
 * path, each already doing that work once for the track in hand.
 */
@OptIn(UnstableApi::class)
object HqAudioVerify {

    private const val TAG = "BitChord"

    /** How much of each recording is compared. */
    const val COMPARE_SECONDS = 90.0

    /** How long to wait for the reference's opening to reach the cache. */
    private const val REFERENCE_HEAD_TIMEOUT_MS = 20_000L

    /** One verification per pair at a time, whichever caller asks first. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * References whose opening could not be had, and when. Several candidates
     * are tried in one race; without this every one of them would wait the
     * full head timeout for the same missing bytes.
     */
    private val noReference = ConcurrentHashMap<String, Long>()
    private const val NO_REFERENCE_TTL_MS = 10 * 60_000L

    /**
     * The reference profiles already measured in this session, by YouTube id.
     *
     * The reference is the same song version for every candidate of a track, so
     * a second candidate (a different source in the same race, or the same
     * track reached again from the upgrade path) does not have to decode the
     * same ninety seconds over. Only a complete-enough profile is stored; see
     * [HqReferenceCache].
     */
    private val referenceCache = HqReferenceCache()

    /**
     * The verified answer for [stream] against [youtubeId], or null when the
     * candidate must not be used.
     *
     * A previously accepted pair returns its stored answer without decoding
     * anything. A previously rejected one returns null immediately.
     */
    suspend fun verify(
        context: Context,
        youtubeId: String,
        target: TrackMatcher.Target,
        stream: SourceStream,
    ): HqVerificationStore.Verified? = withContext(Dispatchers.IO) {
        if (youtubeId.isBlank() || stream.url.isBlank()) return@withContext null
        // A source-backed row has no YouTube song version to compare with;
        // its own source is the reference, so there is nothing to verify here.
        if (SourceTrackKeys.parse(youtubeId) != null) {
            return@withContext HqVerificationStore.Verified(0L, 1.0, 100, HqTier.NORMAL)
        }
        val sourceId = stream.sourceConfigId
        // YouTube's own copy is the reference, not a candidate to verify.
        if (sourceId == null || SourceRegistry.config(sourceId)?.kind == SourceKind.YOUTUBE) {
            return@withContext HqVerificationStore.Verified(0L, 1.0, 100, HqTier.NORMAL)
        }
        val trackId = stream.trackId
        HqVerificationStore.get(context, youtubeId, sourceId, trackId)?.let { return@withContext it }
        if (HqVerificationStore.isRejected(context, youtubeId, sourceId, trackId)) {
            return@withContext null
        }
        val sourceName = SourceRegistry.config(sourceId)?.displayName ?: "source"
        // A manifest is a container the decoder here cannot read; it cannot be
        // proved and must not be swapped in blind.
        if (StreamContainer.isManifest(stream.url)) {
            logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
            return@withContext null
        }

        val key = "$youtubeId\u0001$sourceId\u0001${trackId.orEmpty()}"
        val lock = locks.getOrPut(key) { Mutex() }
        lock.withLock {
            HqVerificationStore.get(context, youtubeId, sourceId, trackId)?.let { return@withLock it }
            if (HqVerificationStore.isRejected(context, youtubeId, sourceId, trackId)) {
                return@withLock null
            }
            val reference = referenceFeatures(context, youtubeId)
            if (reference == null) {
                logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
                TrackLog.d(TAG, "hq-verify: '${target.title}' no reference audio; keeping YouTube", youtubeId)
                return@withLock null
            }
            val candidate = candidateFeatures(stream)
            if (candidate == null) {
                logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
                return@withLock null
            }
            val result = runCatching {
                ChromaAlignment.align(reference, candidate, videoComplete = false)
            }.getOrNull()
            val match = result?.matchPercent ?: 0
            val offset = result?.map?.map(0L) ?: 0L
            val rate = result?.rate ?: 1.0
            val verdict = HqMatchPolicy.verdict(match, offset, rate)
            logVerdict(target.title, sourceName, match, offset, verdict.accepted, about = youtubeId)
            if (!verdict.accepted) {
                reject(context, youtubeId, sourceId, trackId)
                return@withLock null
            }
            val tier = HqQuality.tierOf(stream.format)
            val verified = HqVerificationStore.Verified(offset, rate, match, tier)
            HqVerificationStore.put(context, youtubeId, sourceId, trackId, verified)
            verified
        }
    }

    /** The one line a verification leaves: the figure and the verdict, never lyrics text. */
    private fun logVerdict(
        title: String,
        sourceName: String,
        match: Int,
        offset: Long,
        accepted: Boolean,
        about: String? = null,
    ) {
        TrackLog.i(
            TAG,
            "hq-verify: $title $sourceName match=$match% offset=$offset -> " +
                if (accepted) "accepted" else "rejected",
            about,
        )
    }

    private fun reject(
        context: Context,
        youtubeId: String,
        sourceId: String,
        trackId: String?,
    ) {
        if (trackId.isNullOrBlank()) return
        HqVerificationStore.reject(context, youtubeId, sourceId, trackId)
    }

    /** The YouTube song version's opening, from the cache or a local file. */
    private suspend fun referenceFeatures(
        context: Context,
        youtubeId: String,
    ): ChromaAlignment.Features? {
        referenceCache.get(youtubeId)?.let { return it }
        val now = System.currentTimeMillis()
        val failedAt = noReference[youtubeId]
        if (failedAt != null && now - failedAt < NO_REFERENCE_TTL_MS) return null
        AudioCache.keepForTiming(youtubeId)
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(youtubeId))
        AudioCache.requestAnalysisHead(opus)
        if (!AudioCache.awaitAnalysisHead(opus, REFERENCE_HEAD_TIMEOUT_MS)) {
            noReference[youtubeId] = System.currentTimeMillis()
            TrackLog.d(TAG, "hq-verify: reference opening for $youtubeId did not arrive", youtubeId)
            return null
        }
        val song = Song(
            videoId = youtubeId,
            title = "",
            artist = "",
            thumbnailUrl = null,
            isVideo = false,
        )
        val source = VersionAudioAligner.openDataSource(context, song) ?: run {
            noReference[youtubeId] = System.currentTimeMillis()
            return null
        }
        return try {
            val features = featuresOf(source)
            if (features == null) {
                noReference[youtubeId] = System.currentTimeMillis()
            } else {
                // A partial head is returned for this comparison but not kept:
                // the rest of the bytes may still arrive, and freezing a short
                // profile would make every later candidate compare against it.
                if (HqReferenceCache.completeEnough(features, COMPARE_SECONDS)) {
                    referenceCache.put(youtubeId, features)
                }
                noReference.remove(youtubeId)
            }
            features
        } finally {
            runCatching { source.close() }
        }
    }

    /** The candidate's opening, read straight from its stream URL. */
    private fun candidateFeatures(stream: SourceStream): ChromaAlignment.Features? {
        val dataSource = ProxyAwareDataSourceFactory().createDataSource()
        val source = UrlMediaDataSource(dataSource, Uri.parse(stream.url), stream.headers)
        return try {
            featuresOf(source)
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * The first [COMPARE_SECONDS] of [source] as a chroma profile, using the
     * same extractor and decoder as the lyrics measurement.
     */
    private fun featuresOf(source: MediaDataSource): ChromaAlignment.Features? {
        var extractor: ChromaAlignment.Extractor? = null
        var sampleRate = 0
        var startSeconds = 0.0
        try {
            AudioDecoder.decodeMonoStream(source, 0.0, COMPARE_SECONDS + 1.0) { samples, rate, at ->
                val current = extractor
                if (current == null) {
                    sampleRate = rate
                    startSeconds = at
                    extractor = ChromaAlignment.Extractor(rate).also { it.push(samples) }
                } else if (rate == sampleRate) {
                    current.push(samples)
                }
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "hq-verify: could not read candidate audio: ${e.message}")
            return null
        }
        return extractor?.finish(startSeconds = startSeconds)?.takeIf { it.frames > 0 }
    }

    /**
     * Adapts a Media3 [DataSource] to the [MediaDataSource] interface the
     * extractor wants, keeping the source open across the sequential reads it
     * makes and reopening only at a new position.
     */
    private class UrlMediaDataSource(
        private val dataSource: DataSource,
        private val uri: Uri,
        private val headers: Map<String, String>,
    ) : MediaDataSource() {

        private var isOpen = false
        private var openPosition = -1L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (!isOpen || position != openPosition) {
                closeUpstream()
                val spec = DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(position)
                    .setHttpRequestHeaders(headers)
                    .build()
                try {
                    dataSource.open(spec)
                } catch (e: IOException) {
                    return -1
                }
                isOpen = true
                openPosition = position
            }
            val read = try {
                dataSource.read(buffer, offset, size)
            } catch (e: IOException) {
                closeUpstream()
                return -1
            }
            if (read < 0) {
                closeUpstream()
                return -1
            }
            openPosition += read
            return read
        }

        override fun getSize(): Long = -1L

        override fun close() {
            closeUpstream()
        }

        private fun closeUpstream() {
            if (isOpen) {
                runCatching { dataSource.close() }
                isOpen = false
                openPosition = -1L
            }
        }
    }
}

