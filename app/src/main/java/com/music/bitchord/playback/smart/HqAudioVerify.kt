package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.music.bitchord.data.TrackLog
import com.music.bitchord.download.HqLibraryUpgrade
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
import com.music.bitchord.playback.PowerState
import com.music.bitchord.playback.ProxyAwareDataSourceFactory
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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

    /** One verification per pair at a time while that verification is running. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * References whose opening could not be had, and when. Several candidates
     * are tried in one race; without this every one of them would wait the
     * full head timeout for the same missing bytes.
     */
    private val noReference = ConcurrentHashMap<String, Long>()
    private const val NO_REFERENCE_TTL_MS = 10 * 60_000L

    /**
     * A failure is only useful until the bytes have had a chance to arrive;
     * this bounds the map even in a session that never sees a reference again.
     */
    private const val MAX_NO_REFERENCE = 256

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
     * The same profiles across app restarts, in a few dozen kilobytes each.
     *
     * The memory cache only helps within one run. A candidate first seen in a
     * later session — a source added since, or a search that found a different
     * row — would otherwise decode the same ninety seconds of the YouTube
     * reference again. The answer is a property of that recording and cannot
     * change, so it is worth the small file. Bounded and oldest-first; a
     * partial profile is never written, for the same reason it is not kept in
     * memory (see [HqReferenceCache]).
     */
    private const val REFERENCE_DIR = "hq_reference"
    private const val MAX_REFERENCE_FILES = 64

    /**
     * One reference decode per YouTube id at a time.
     *
     * The read-ahead asks every source at once, and every candidate needs the
     * same reference. Without this, candidates that started before the first
     * profile landed each decoded the same ninety seconds; the cache only
     * helped the ones that arrived after it. The second candidate now waits for
     * the first's profile instead of paying for it again. Pair locks are always
     * taken before this one, so the two cannot deadlock.
     */
    private val referenceLocks = ConcurrentHashMap<String, Mutex>()

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
        // Verification is a ninety-second decode on each side. A verdict
        // already on disk is returned above; with the screen off there is
        // nobody waiting on a fresh one — the next-song read-ahead can ask
        // again once it is on — so this is exactly the work to leave undone
        // rather than to spend battery on.
        if (!PowerState.screenOn) return@withContext null
        val sourceName = SourceRegistry.config(sourceId)?.displayName ?: "source"
        // A manifest is a container the decoder here cannot read; it cannot be
        // proved and must not be swapped in blind.
        if (StreamContainer.isManifest(stream.url)) {
            logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
            return@withContext null
        }

        val key = "$youtubeId\u0001$sourceId\u0001${trackId.orEmpty()}"
        val lock = locks.getOrPut(key) { Mutex() }
        try {
            return@withContext lock.withLock {
                coroutineScope {
                    HqVerificationStore.get(context, youtubeId, sourceId, trackId)
                        ?.let { return@coroutineScope it }
                    if (HqVerificationStore.isRejected(context, youtubeId, sourceId, trackId)) {
                        return@coroutineScope null
                    }
                    // The two ninety-second reads do not depend on each other:
                    // the reference comes from the cached YouTube opening and
                    // the candidate from its own URL. Started together, a cold
                    // verification costs the slower of the two rather than
                    // their sum — the verification in front of the reported
                    // 2.2–8.4 s prove times had the same sequential shape. A
                    // missing reference cancels the candidate read before
                    // anything waits on it.
                    val candidateDeferred = async { candidateFeatures(stream) }
                    val reference = referenceFeatures(context, youtubeId)
                    if (reference == null) {
                        candidateDeferred.cancel()
                        logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
                        TrackLog.d(TAG, "hq-verify: '${target.title}' no reference audio; keeping YouTube", youtubeId)
                        return@coroutineScope null
                    }
                    val candidate = candidateDeferred.await()
                    if (candidate == null) {
                        logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
                        return@coroutineScope null
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
                        return@coroutineScope null
                    }
                    val tier = HqQuality.tierOf(stream.format)
                    val verified = HqVerificationStore.Verified(offset, rate, match, tier)
                    HqVerificationStore.put(context, youtubeId, sourceId, trackId, verified)
                    // A downloaded or pre-saved copy may now be replaceable with
                    // this newly proved rung, even if it was already high quality.
                    HqLibraryUpgrade.onVerified(context)
                    verified
                }
            }
        } finally {
            // The entry only has to live as long as the work it serialises.
            // Keeping every pair for the session was a slow leak on a long
            // queue; a caller already waiting keeps its reference, so removing
            // it cannot let a second decode of this pair start alongside that
            // one. At worst a third caller arriving after the wait starts a
            // fresh lock, and the verdict cache still answers it.
            locks.remove(key, lock)
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

    /** Remembers a missing reference, pruning expired failures first. */
    private fun noteNoReference(youtubeId: String) {
        val now = System.currentTimeMillis()
        if (noReference.size >= MAX_NO_REFERENCE) {
            noReference.entries.removeIf { now - it.value >= NO_REFERENCE_TTL_MS }
            if (noReference.size >= MAX_NO_REFERENCE) noReference.clear()
        }
        noReference[youtubeId] = now
    }

    /** A stored reference profile for [youtubeId], or null when there is none usable. */
    private fun diskReference(context: Context, youtubeId: String): ChromaAlignment.Features? {
        val file = referenceFile(context, youtubeId)
        if (!file.exists()) return null
        val features = runCatching {
            ChromaAlignment.Features.fromBytes(file.readBytes())
        }.getOrNull()
        if (features == null || !HqReferenceCache.completeEnough(features, COMPARE_SECONDS)) {
            runCatching { file.delete() }
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return features
    }

    /** Keeps [features] for the next session; a failed write is not worth failing the verification over. */
    private fun storeReference(context: Context, youtubeId: String, features: ChromaAlignment.Features) {
        runCatching {
            val dir = File(context.filesDir, REFERENCE_DIR)
            dir.mkdirs()
            val file = referenceFile(context, youtubeId)
            val temporary = File(dir, file.name + ".tmp")
            temporary.writeBytes(features.toBytes())
            try {
                if (!temporary.renameTo(file)) {
                    file.delete()
                    temporary.renameTo(file)
                }
            } finally {
                temporary.delete()
            }
            val files = dir.listFiles { candidate -> candidate.name.endsWith(".bin") }.orEmpty()
            if (files.size > MAX_REFERENCE_FILES) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - MAX_REFERENCE_FILES)
                    .forEach { it.delete() }
            }
        }.onFailure {
            TrackLog.d(TAG, "hq-verify: could not keep reference for $youtubeId: ${it.message}", youtubeId)
        }
    }

    private fun referenceFile(context: Context, youtubeId: String): File {
        val safe = youtubeId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { "id" }
        return File(
            File(context.filesDir, REFERENCE_DIR),
            "$safe-${youtubeId.hashCode().toUInt()}.bin",
        )
    }

    /**
     * The YouTube song version's opening, from memory, disk or a local file.
     *
     * The memory cache answers a repeat within this run, the disk cache one
     * from an earlier run, and [referenceLocks] makes the first decode answer
     * for every candidate racing beside it. Only the wrapper touches the
     * caches; [referenceFeaturesUncached] is the one decode.
     */
    private suspend fun referenceFeatures(
        context: Context,
        youtubeId: String,
    ): ChromaAlignment.Features? {
        referenceCache.get(youtubeId)?.let { return it }
        val lock = referenceLocks.getOrPut(youtubeId) { Mutex() }
        try {
            return lock.withLock {
                referenceCache.get(youtubeId)?.let { return@withLock it }
                diskReference(context, youtubeId)?.let { cached ->
                    referenceCache.put(youtubeId, cached)
                    noReference.remove(youtubeId)
                    return@withLock cached
                }
                referenceFeaturesUncached(context, youtubeId)
            }
        } finally {
            referenceLocks.remove(youtubeId, lock)
        }
    }

    private suspend fun referenceFeaturesUncached(
        context: Context,
        youtubeId: String,
    ): ChromaAlignment.Features? {
        if (!PowerState.screenOn) return null
        val now = System.currentTimeMillis()
        val failedAt = noReference[youtubeId]
        if (failedAt != null && now - failedAt < NO_REFERENCE_TTL_MS) return null
        AudioCache.keepForTiming(youtubeId)
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(youtubeId))
        AudioCache.requestAnalysisHead(opus)
        if (!AudioCache.awaitAnalysisHead(opus, REFERENCE_HEAD_TIMEOUT_MS)) {
            noteNoReference(youtubeId)
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
            noteNoReference(youtubeId)
            return null
        }
        return try {
            val coroutine = currentCoroutineContext()
            val features = featuresOf(source) { coroutine.isActive && PowerState.screenOn }
            if (features == null) {
                // A cancelled decode is not a missing reference: the next
                // playback must be free to try again. The screen going off is
                // the same kind of interruption, not a verdict about the audio.
                if (coroutine.isActive && PowerState.screenOn) noteNoReference(youtubeId)
            } else {
                // A partial head is returned for this comparison but not kept:
                // the rest of the bytes may still arrive, and freezing a short
                // profile would make every later candidate compare against it.
                if (HqReferenceCache.completeEnough(features, COMPARE_SECONDS)) {
                    referenceCache.put(youtubeId, features)
                    storeReference(context, youtubeId, features)
                }
                noReference.remove(youtubeId)
            }
            features
        } finally {
            runCatching { source.close() }
        }
    }

    /** The candidate's opening, read straight from its stream URL. */
    private suspend fun candidateFeatures(stream: SourceStream): ChromaAlignment.Features? {
        val dataSource = ProxyAwareDataSourceFactory().createDataSource()
        val source = UrlMediaDataSource(dataSource, Uri.parse(stream.url), stream.headers)
        return try {
            val coroutine = currentCoroutineContext()
            featuresOf(source) { coroutine.isActive && PowerState.screenOn }
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * The first [COMPARE_SECONDS] of [source] as a chroma profile, using the
     * same extractor and decoder as the lyrics measurement.
     *
     * [abort] is polled by the decoder loop, so a verification the caller has
     * given up on (the queue moved, or the reference turned out to be missing)
     * stops reading instead of finishing ninety seconds of work nobody wants.
     */
    private fun featuresOf(
        source: MediaDataSource,
        abort: () -> Boolean = { false },
    ): ChromaAlignment.Features? {
        var extractor: ChromaAlignment.Extractor? = null
        var sampleRate = 0
        var startSeconds = 0.0
        try {
            val decoded = AudioDecoder.decodeMonoStream(
                source,
                0.0,
                COMPARE_SECONDS + 1.0,
                abort = abort,
                label = "hq-verify",
            ) { samples, rate, at ->
                val current = extractor
                if (current == null) {
                    sampleRate = rate
                    startSeconds = at
                    extractor = ChromaAlignment.Extractor(rate).also { it.push(samples) }
                } else if (rate == sampleRate) {
                    current.push(samples)
                }
            }
            // Null means the decode was aborted or produced nothing. A partial
            // profile is not a shorter answer: it would be compared as if the
            // recording were that short.
            if (decoded == null) return null
        } catch (e: Exception) {
            TrackLog.w(TAG, "hq-verify: could not read candidate audio: ${e.message}")
            return null
        }
        // A container that never states its rate would make the chroma clock
        // infinite; it cannot be compared, so it is refused here rather than
        // carried into the aligner as a bogus profile.
        if (sampleRate <= 0) return null
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

