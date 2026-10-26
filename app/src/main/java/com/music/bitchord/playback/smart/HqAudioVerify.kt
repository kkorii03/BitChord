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
import com.music.bitchord.data.sources.HqVerifyPolicy
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.ProxyAwareDataSourceFactory
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
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

    /**
     * How long an automatic attempt waits for the reference's opening.
     *
     * Short because a missing reference is no longer remembered across plays:
     * the next playback event asks again, so giving up early costs nothing but
     * a retry later.
     */
    private const val REFERENCE_HEAD_TIMEOUT_MS = 10_000L

    /**
     * A manual request is someone waiting on the answer, so it gets the longer
     * wait that makes a slow head arrive instead of being reported missing.
     */
    private const val MANUAL_REFERENCE_HEAD_TIMEOUT_MS = 20_000L

    /** One verification per pair at a time while that verification is running. */
    private val locks = RefCountedKeyedMutex<String>()

    /**
     * References whose opening could not be had during this playback, and why.
     *
     * Several candidates are tried in one race; without this every one of them
     * would wait the full head timeout for the same missing bytes. It is
     * cleared on the next playback event and ignored by manual requests: a
     * failure now is not a verdict about the next attempt.
     */
    private val noReference = ConcurrentHashMap<String, NoReferenceReason>()

    /** A session can still reach many tracks; the map never needs to outgrow one queue. */
    private const val MAX_NO_REFERENCE = 256

    /** Why the YouTube reference could not be compared this time. */
    enum class NoReferenceReason(val wire: String) {
        HEAD_TIMEOUT("head-timeout"),
        NO_SOURCE("no-source"),
        DECODE_FAILED("decode-failed"),
        CANCELLED("cancelled"),
    }

    /** The full answer of a verification, including why it could not be measured. */
    sealed interface Outcome {
        data class Verified(val value: HqVerificationStore.Verified) : Outcome

        /** A real measurement that did not match the YouTube recording. */
        data object Mismatch : Outcome

        /** The YouTube reference itself could not be opened, decoded or reached. */
        data class NoReference(val reason: NoReferenceReason) : Outcome

        /** The candidate could not be measured, or the request was not verifiable. */
        data object Unavailable : Outcome
    }

    /**
     * A decoded profile plus whether its read reached the end of the track.
     * A short song that ended is complete; a read that stopped before the
     * requested window is not.
     */
    private data class Profile(
        val features: ChromaAlignment.Features,
        val reachedEnd: Boolean,
        val declaredDurationSeconds: Double? = null,
    ) {
        fun complete(seconds: Double): Boolean {
            // A container that states its length lets a clean early close be
            // told apart from a genuinely short recording: if the bytes ended
            // well before the declared length, the read was cut short.
            val declared = declaredDurationSeconds
            if (reachedEnd && declared != null &&
                features.durationSeconds + 1.0 < minOf(declared, seconds)
            ) {
                return false
            }
            return HqReferenceCache.completeEnough(features, seconds, reachedEnd)
        }
    }

    private sealed interface Reference {
        data class Ready(val profile: Profile) : Reference
        data class Missing(val reason: NoReferenceReason) : Reference
    }

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
    private val referenceLocks = RefCountedKeyedMutex<String>()

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
    ): HqVerificationStore.Verified? = verifyOutcome(context, youtubeId, target, stream).verifiedOrNull()

    /**
     * As [verify], with the reason when the candidate cannot be used.
     *
     * [manual] is a listener waiting on the answer: it ignores a missing
     * reference remembered earlier in this play and waits longer for the
     * YouTube opening. It never turns a missing reference into a rejection.
     */
    suspend fun verifyOutcome(
        context: Context,
        youtubeId: String,
        target: TrackMatcher.Target,
        stream: SourceStream,
        manual: Boolean = false,
    ): Outcome = withContext(Dispatchers.IO) {
        if (youtubeId.isBlank() || stream.url.isBlank()) return@withContext Outcome.Unavailable
        // A source-backed row has no YouTube song version to compare with;
        // its own source is the reference, so there is nothing to verify here.
        if (SourceTrackKeys.parse(youtubeId) != null) {
            return@withContext Outcome.Verified(
                HqVerificationStore.Verified(0L, 1.0, 100, HqTier.NORMAL),
            )
        }
        val sourceId = stream.sourceConfigId
        // YouTube's own copy is the reference, not a candidate to verify.
        if (sourceId == null || SourceRegistry.config(sourceId)?.kind == SourceKind.YOUTUBE) {
            return@withContext Outcome.Verified(
                HqVerificationStore.Verified(0L, 1.0, 100, HqTier.NORMAL),
            )
        }
        val trackId = stream.trackId
        HqVerificationStore.get(context, youtubeId, sourceId, trackId)?.let {
            return@withContext Outcome.Verified(it)
        }
        // Only a measured mismatch is stored here; a missing reference never is.
        if (HqVerificationStore.isRejected(context, youtubeId, sourceId, trackId)) {
            return@withContext Outcome.Mismatch
        }
        val sourceName = SourceRegistry.config(sourceId)?.displayName ?: "source"
        // A manifest is a container the decoder here cannot read; it cannot be
        // proved and must not be swapped in blind.
        if (StreamContainer.isManifest(stream.url)) {
            logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
            return@withContext Outcome.Unavailable
        }

        val key = "$youtubeId\u0001$sourceId\u0001${trackId.orEmpty()}"
        val lock = locks.acquire(key)
        try {
            return@withContext lock.mutex.withLock {
                coroutineScope {
                    HqVerificationStore.get(context, youtubeId, sourceId, trackId)
                        ?.let { return@coroutineScope Outcome.Verified(it) }
                    if (HqVerificationStore.isRejected(context, youtubeId, sourceId, trackId)) {
                        return@coroutineScope Outcome.Mismatch
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
                    val reference = referenceFeatures(
                        context,
                        youtubeId,
                        target.title,
                        manual,
                    )
                    if (reference is Reference.Missing) {
                        candidateDeferred.cancel()
                        return@coroutineScope Outcome.NoReference(reference.reason)
                    }
                    val referenceProfile = (reference as Reference.Ready).profile
                    val referenceFeatures = referenceProfile.features
                    // A truncated profile is not a shorter recording. Comparing
                    // it would only produce a poor match, and a poor match is
                    // stored as a permanent rejection — so a partial reference
                    // means "try again later", not "this candidate is wrong".
                    if (!referenceProfile.complete(COMPARE_SECONDS)) {
                        candidateDeferred.cancel()
                        logNoReference(target.title, youtubeId, NoReferenceReason.DECODE_FAILED)
                        return@coroutineScope Outcome.NoReference(NoReferenceReason.DECODE_FAILED)
                    }
                    val candidate = candidateDeferred.await()
                    if (candidate == null) {
                        logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
                        return@coroutineScope Outcome.Unavailable
                    }
                    // The same rule on the candidate side: a read cut short by
                    // a slow or closing stream says nothing about the
                    // recording and must not become a stored rejection.
                    if (!referenceProfile.complete(COMPARE_SECONDS) ||
                        !candidate.complete(COMPARE_SECONDS)
                    ) {
                        logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
                        return@coroutineScope Outcome.Unavailable
                    }
                    val result = runCatching {
                        ChromaAlignment.align(
                            referenceFeatures,
                            candidate.features,
                            videoComplete = false,
                        )
                    }.getOrNull()
                    if (result == null) {
                        // The aligner failed rather than finding a poor match;
                        // that says nothing about the candidate, so it must not
                        // become a stored rejection.
                        logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
                        return@coroutineScope Outcome.Unavailable
                    }
                    val match = result.matchPercent
                    val offset = result.map.map(0L)
                    val rate = result.rate
                    val verdict = HqMatchPolicy.verdict(match, offset, rate)
                    logVerdict(target.title, sourceName, match, offset, verdict.accepted, about = youtubeId)
                    if (!verdict.accepted) {
                        if (HqVerifyPolicy.shouldPersistRejection(
                                referenceAvailable = true,
                                accepted = verdict.accepted,
                            )
                        ) {
                            reject(context, youtubeId, sourceId, trackId)
                        }
                        return@coroutineScope Outcome.Mismatch
                    }
                    val tier = HqQuality.tierOf(stream.format)
                    val verified = HqVerificationStore.Verified(offset, rate, match, tier)
                    HqVerificationStore.put(context, youtubeId, sourceId, trackId, verified)
                    // A downloaded or pre-saved copy may now be replaceable with
                    // this newly proved rung, even if it was already high quality.
                    HqLibraryUpgrade.onVerified(context)
                    Outcome.Verified(verified)
                }
            }
        } finally {
            // Kept until the last caller waiting on it has left. Removing it
            // the moment the holder finished let a newcomer make a second
            // mutex while an earlier waiter still held the first, decoding the
            // same pair twice.
            locks.release(key, lock)
        }
    }

    private fun Outcome.verifiedOrNull(): HqVerificationStore.Verified? =
        (this as? Outcome.Verified)?.value

    /**
     * The next playback event makes every remembered missing reference stale.
     *
     * A failure belongs to the attempt that met it — a slow head, a decoder
     * that was busy — not to the recording or to the next time the track is
     * played. Clearing here is what makes the next play try again without a
     * ten-minute timer.
     */
    fun onPlaybackStarted() {
        noReference.clear()
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

    /** Remembers a missing reference for the rest of this playback, with its cause. */
    private fun noteNoReference(youtubeId: String, reason: NoReferenceReason) {
        if (noReference.size >= MAX_NO_REFERENCE) noReference.clear()
        noReference[youtubeId] = reason
    }

    private fun logNoReference(title: String, youtubeId: String, reason: NoReferenceReason) {
        TrackLog.d(
            TAG,
            "hq-verify: '$title' no reference reason=${reason.wire}",
            youtubeId,
        )
    }

    /**
     * A profile that came from the memory or disk cache. Only complete reads
     * are ever stored there, so it counts as having reached the end.
     */
    private fun cachedProfile(features: ChromaAlignment.Features): Profile =
        Profile(features = features, reachedEnd = true)

    /** A stored reference profile for [youtubeId], or null when there is none usable. */
    private fun diskReference(context: Context, youtubeId: String): ChromaAlignment.Features? {
        val file = referenceFile(context, youtubeId)
        if (!file.exists()) return null
        val features = runCatching {
            ChromaAlignment.Features.fromBytes(file.readBytes())
        }.getOrNull()
        if (features == null) {
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
        title: String,
        manual: Boolean,
    ): Reference {
        referenceCache.get(youtubeId)?.let { return Reference.Ready(cachedProfile(it)) }
        val lock = referenceLocks.acquire(youtubeId)
        try {
            return lock.mutex.withLock {
                referenceCache.get(youtubeId)?.let { return@withLock Reference.Ready(cachedProfile(it)) }
                diskReference(context, youtubeId)?.let { cached ->
                    referenceCache.put(youtubeId, cached)
                    noReference.remove(youtubeId)
                    return@withLock Reference.Ready(cachedProfile(cached))
                }
                referenceFeaturesUncached(context, youtubeId, title, manual)
            }
        } finally {
            referenceLocks.release(youtubeId, lock)
        }
    }

    private suspend fun referenceFeaturesUncached(
        context: Context,
        youtubeId: String,
        title: String,
        manual: Boolean,
    ): Reference {
        if (manual) {
            noReference.remove(youtubeId)
        } else {
            noReference[youtubeId]?.let { remembered ->
                logNoReference(title, youtubeId, remembered)
                return Reference.Missing(remembered)
            }
        }
        AudioCache.keepForTiming(youtubeId)
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(youtubeId))
        AudioCache.requestAnalysisHead(opus)
        val timeout = if (manual) MANUAL_REFERENCE_HEAD_TIMEOUT_MS else REFERENCE_HEAD_TIMEOUT_MS
        if (!AudioCache.awaitAnalysisHead(opus, timeout)) {
            noteNoReference(youtubeId, NoReferenceReason.HEAD_TIMEOUT)
            logNoReference(title, youtubeId, NoReferenceReason.HEAD_TIMEOUT)
            return Reference.Missing(NoReferenceReason.HEAD_TIMEOUT)
        }
        val song = Song(
            videoId = youtubeId,
            title = "",
            artist = "",
            thumbnailUrl = null,
            isVideo = false,
        )
        val source = VersionAudioAligner.openDataSource(context, song) ?: run {
            noteNoReference(youtubeId, NoReferenceReason.NO_SOURCE)
            logNoReference(title, youtubeId, NoReferenceReason.NO_SOURCE)
            return Reference.Missing(NoReferenceReason.NO_SOURCE)
        }
        return try {
            val coroutine = currentCoroutineContext()
            val profile = featuresOf(source) { coroutine.isActive }
            if (profile == null) {
                // A cancelled decode is not a missing reference: the next
                // playback must be free to try again.
                val reason = if (coroutine.isActive) {
                    NoReferenceReason.DECODE_FAILED
                } else {
                    NoReferenceReason.CANCELLED
                }
                if (reason != NoReferenceReason.CANCELLED) {
                    noteNoReference(youtubeId, reason)
                }
                logNoReference(title, youtubeId, reason)
                Reference.Missing(reason)
            } else {
                // A partial head is returned for this comparison but not kept:
                // the rest of the bytes may still arrive, and freezing a short
                // profile would make every later candidate compare against it.
                // A short recording that reached its end is complete, so it is
                // kept like any other.
                if (profile.complete(COMPARE_SECONDS)) {
                    referenceCache.put(youtubeId, profile.features)
                    storeReference(context, youtubeId, profile.features)
                }
                noReference.remove(youtubeId)
                Reference.Ready(profile)
            }
        } finally {
            runCatching { source.close() }
        }
    }

    /** The candidate's opening, read straight from its stream URL. */
    private suspend fun candidateFeatures(stream: SourceStream): Profile? {
        val dataSource = ProxyAwareDataSourceFactory().createDataSource()
        val source = UrlMediaDataSource(dataSource, Uri.parse(stream.url), stream.headers)
        return try {
            val coroutine = currentCoroutineContext()
            // Cancelling the other half of the race must interrupt the decode,
            // not leave it holding the global decoder gate for another ninety
            // seconds while the caller has already moved on.
            runInterruptible {
                featuresOf(source) { coroutine.isActive }
            }
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
    ): Profile? {
        // Checked before the extractor opens the source and before the decoder
        // gate is taken: an abandoned verification must not queue behind the
        // work it was cancelled to avoid.
        if (abort()) return null
        var extractor: ChromaAlignment.Extractor? = null
        var sampleRate = 0
        var startSeconds = 0.0
        var decoded: AudioDecoder.Decoded? = null
        try {
            decoded = AudioDecoder.decodeMonoStreamDecoded(
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
        val result = decoded ?: return null
        val features = extractor?.finish(startSeconds = startSeconds)?.takeIf { it.frames > 0 }
            ?: return null
        return Profile(
            features = features,
            reachedEnd = result.reachedEnd,
            declaredDurationSeconds = result.declaredDurationSeconds,
        )
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

