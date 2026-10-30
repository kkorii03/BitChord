package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.net.HqRetryPolicy
import com.music.bitchord.data.net.ProxyPlayback
import com.music.bitchord.download.HqLibraryUpgrade
import com.music.bitchord.data.lyrics.ChromaAlignment
import com.music.bitchord.data.lyrics.WavAudio
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.DownloadHealthPolicy
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
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /** One verification per pair at a time while that verification is running. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Serialises candidate downloads per host; see [downloadCandidate]. */
    private val candidateHostLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Songs whose candidate download was refused with a 429 and whose host is
     * resting. The next playback event, or the next verification request for
     * the same song, checks whether that rest has passed and asks once more.
     * Nothing here polls: an entry is read only when one of those events
     * happens.
     */
    private class PendingRateLimit(
        val context: Context,
        val youtubeId: String,
        val target: TrackMatcher.Target,
        val stream: SourceStream,
        val host: String?,
        val manual: Boolean,
    )

    private val pendingRateLimits = ConcurrentHashMap<String, PendingRateLimit>()

    /** One queue's worth is enough; an old refusal is not worth unbounded memory. */
    private const val MAX_PENDING_RATE_LIMITS = 64

    /** The retry itself is started by an event, never by a timer. */
    private val pendingRetryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRetryRunning = AtomicBoolean(false)

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

    /**
     * The play a verification belongs to.
     *
     * [onPlaybackStarted] replaces this object and completes [changed], which
     * wakes a rate-limit wait at once instead of letting it outlive the track
     * it was started for. Identity is the generation; no counter is needed.
     */
    private class PlaybackEpoch {
        val changed = CompletableDeferred<Unit>()
    }

    private val playbackEpoch = AtomicReference(PlaybackEpoch())

    /** Why the YouTube reference could not be compared this time. */
    enum class NoReferenceReason(val wire: String) {
        HEAD_TIMEOUT("head-timeout"),
        NO_SOURCE("no-source"),
        DECODE_FAILED("decode-failed"),
        CANCELLED("cancelled");

        /**
         * Whether this is a failure of the moment rather than of the recording.
         * A screen-off head timeout, a busy decoder or a cancelled play says
         * nothing about the song, so a pending 429 retry stays for the next
         * event. A missing source is the one reason that cannot fix itself.
         */
        val transient: Boolean
            get() = this != NO_SOURCE
    }

    /** The full answer of a verification, including why it could not be measured. */
    sealed interface Outcome {
        data class Verified(val value: HqVerificationStore.Verified) : Outcome

        /** A real measurement that did not match the YouTube recording. */
        data object Mismatch : Outcome

        /** The play changed before this attempt could measure anything. */
        data object Abandoned : Outcome

        /**
         * The host is still resting from an earlier 429, so this request was
         * not attempted at all. Unlike a measured failure it must not consume
         * the one retry a pending entry grants.
         */
        data object Deferred : Outcome

        /** The YouTube reference itself could not be opened, decoded or reached. */
        data class NoReference(val reason: NoReferenceReason) : Outcome

        /**
         * The candidate could not be measured, or the request was not
         * verifiable. [transient] marks a network/decode/alignment failure that
         * says nothing about the candidate and may succeed on the next event;
         * only a permanent refusal settles a pending 429 retry.
         */
        data class Unavailable(val transient: Boolean = false) : Outcome
    }

    private sealed interface Reference {
        data class Ready(val features: ChromaAlignment.Features) : Reference
        data class Missing(val reason: NoReferenceReason) : Reference
    }

    /** The candidate's opening as a profile, and how many bytes were fetched for it. */
    internal data class Candidate(
        val features: ChromaAlignment.Features,
        val bytes: Long,
    )

    /** Temporary candidate heads live here and are deleted after every attempt. */
    private const val CANDIDATE_DIR = "hq_verify"

    /** One read at a time while copying the response into the cache file. */
    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

    /** Response headers worth keeping on a rate-limit line; nothing URL- or key-bearing. */
    private val RATE_LIMIT_LOG_HEADERS = listOf(
        "Retry-After",
        "Date",
        "Server",
        "X-RateLimit-Limit",
        "X-RateLimit-Remaining",
        "X-RateLimit-Reset",
        "X-RateLimit-Retry-After",
    )

    /** A response that a single later attempt could still get past. */
    private class RateLimitedDownload(
        val code: Int,
        val retryAfter: String?,
        val headerFields: Map<String, List<String>>,
    ) : IOException("HTTP $code")

    /** The candidate head's download answer, keeping the 429 apart from other failures. */
    private sealed interface DownloadFetch {
        data class Bytes(val value: Long) : DownloadFetch
        data class RateLimited(val host: String?, val restMs: Long) : DownloadFetch
        data object Abandoned : DownloadFetch
    }

    /** The candidate's answer, keeping a rate-limited download from looking like a decode failure. */
    private sealed interface CandidateFetch {
        data class Ready(val value: Candidate) : CandidateFetch
        data class RateLimited(val host: String?, val restMs: Long) : CandidateFetch
        data object Abandoned : CandidateFetch
        data object Failed : CandidateFetch
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
    ): HqVerificationStore.Verified? = verifyOutcome(context, youtubeId, target, stream).verifiedOrNull()

    /**
     * As [verify], with the reason when the candidate cannot be used.
     *
     * [manual] is a listener waiting on the answer: it ignores a missing
     * reference remembered earlier in this play and asks the song check for
     * the opening again. It never turns a missing reference into a rejection.
     */
    suspend fun verifyOutcome(
        context: Context,
        youtubeId: String,
        target: TrackMatcher.Target,
        stream: SourceStream,
        manual: Boolean = false,
    ): Outcome {
        // A verification request is another event that may find a host's rest
        // over. The song being asked about here is handled below, so leave it
        // out to avoid two attempts for one pair.
        retryPendingRateLimits(exceptYoutubeId = youtubeId)
        return verifyOutcome(context, youtubeId, target, stream, manual, recordRateLimit = true)
    }

    private suspend fun verifyOutcome(
        context: Context,
        youtubeId: String,
        target: TrackMatcher.Target,
        stream: SourceStream,
        manual: Boolean,
        recordRateLimit: Boolean,
    ): Outcome = withContext(Dispatchers.IO) {
        val epoch = playbackEpoch.get()
        if (youtubeId.isBlank() || stream.url.isBlank()) return@withContext Outcome.Unavailable()
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
        val sourceName = SourceRegistry.config(sourceId)?.displayName ?: "source"
        // A source row that names an arrangement the song itself does not is
        // refused before the expensive decode. The same vocabulary decides the
        // download health check, so a piano cover cannot enter through one
        // path after being kept out of the other.
        if (DownloadHealthPolicy.isArrangement(
                sourceTitle = stream.trackTitle,
                sourceArtist = stream.trackArtist,
                songTitle = target.title,
                songArtist = target.artist,
            )
        ) {
            reject(context, youtubeId, sourceId, trackId)
            logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId)
            return@withContext Outcome.Mismatch
        }
        HqVerificationStore.get(context, youtubeId, sourceId, trackId)?.let {
            pendingRateLimits.remove(youtubeId)
            return@withContext Outcome.Verified(it)
        }
        // Only a measured mismatch is stored here; a missing reference never is.
        if (HqVerificationStore.isRejected(context, youtubeId, sourceId, trackId)) {
            return@withContext Outcome.Mismatch
        }
        // A manifest is a container the decoder here cannot read; it cannot be
        // proved and must not be swapped in blind.
        if (StreamContainer.isManifest(stream.url)) {
            logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId, measured = false)
            return@withContext Outcome.Unavailable()
        }

        // A song whose last attempt was refused is not asked again while its
        // host is still resting. A different, healthy candidate is allowed
        // through at once: that is the point of trying the other addon first.
        val incomingHost = HqHostTracker.hostOf(stream.url)
        pendingRateLimits[youtubeId]?.let { pending ->
            val restMs = HqHostTracker.restRemainingMs(pending.host)
            if (!manual && restMs > 0L &&
                (incomingHost == null || incomingHost == pending.host ||
                    HqHostTracker.isResting(incomingHost))
            ) {
                TrackLog.d(
                    TAG,
                    "decode: hq-verify '${target.title}' deferred ${restMs}ms; " +
                        "${pending.host ?: "host"} is resting",
                    youtubeId,
                )
                return@withContext Outcome.Deferred
            }
        }

        val key = "$youtubeId\u0001$sourceId\u0001${trackId.orEmpty()}"
        val lock = locks.getOrPut(key) { Mutex() }
        try {
            return@withContext lock.withLock {
                coroutineScope {
                    HqVerificationStore.get(context, youtubeId, sourceId, trackId)
                        ?.let { return@coroutineScope Outcome.Verified(it) }
                    if (HqVerificationStore.isRejected(context, youtubeId, sourceId, trackId)) {
                        return@coroutineScope Outcome.Mismatch
                    }
                    // The two ninety-second reads do not depend on each other:
                    // the reference comes from the WAV the song check already
                    // decodes and the candidate from a cache file holding its
                    // own opening. Started together, a cold verification costs
                    // the slower of the two rather than their sum — the
                    // verification in front of the reported 2.2–8.4 s prove
                    // times had the same sequential shape. A missing reference
                    // cancels the candidate read before anything waits on it.
                    val candidateDeferred = async { candidateFetch(context, stream, epoch) }
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
                    val candidate = when (val fetched = candidateDeferred.await()) {
                        is CandidateFetch.Ready -> fetched.value
                        is CandidateFetch.RateLimited -> {
                            if (recordRateLimit) {
                                notePendingRateLimit(
                                    context,
                                    youtubeId,
                                    target,
                                    stream,
                                    fetched,
                                    manual,
                                )
                            }
                            logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId, measured = false)
                            return@coroutineScope Outcome.Unavailable()
                        }
                        CandidateFetch.Abandoned -> {
                            return@coroutineScope Outcome.Abandoned
                        }
                        CandidateFetch.Failed -> {
                            logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId, measured = false)
                            return@coroutineScope Outcome.Unavailable(transient = true)
                        }
                    }
                    // A head that stops short of the window while the reference
                    // has the full one is not a shorter answer: comparing it
                    // would read as a poor match and could be stored as a
                    // rejection. It is merely unmeasurable. When both are short
                    // (a genuinely short track), they are still comparable.
                    val referenceFeatures = (reference as Reference.Ready).features
                    val referenceComplete =
                        HqReferenceCache.completeEnough(referenceFeatures, COMPARE_SECONDS)
                    val candidateComplete =
                        HqReferenceCache.completeEnough(candidate.features, COMPARE_SECONDS)
                    if (referenceComplete && !candidateComplete) {
                        logVerdict(
                            target.title,
                            sourceName,
                            0,
                            0L,
                            accepted = false,
                            about = youtubeId,
                            reference = "wav",
                            candidate = "file",
                            bytes = candidate.bytes,
                            measured = false,
                        )
                        return@coroutineScope Outcome.Unavailable(transient = true)
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
                        logVerdict(target.title, sourceName, 0, 0L, accepted = false, about = youtubeId, measured = false)
                        return@coroutineScope Outcome.Unavailable(transient = true)
                    }
                    val match = result.matchPercent
                    val offset = result.map.map(0L)
                    val rate = result.rate
                    val verdict = HqMatchPolicy.verdict(match, offset, rate)
                    logVerdict(
                        target.title,
                        sourceName,
                        match,
                        offset,
                        verdict.accepted,
                        about = youtubeId,
                        reference = "wav",
                        candidate = "file",
                        bytes = candidate.bytes,
                    )
                    if (!verdict.accepted) {
                        if (HqVerifyPolicy.shouldPersistRejection(
                                referenceAvailable = referenceComplete && candidateComplete,
                                accepted = verdict.accepted,
                            )
                        ) {
                            reject(context, youtubeId, sourceId, trackId)
                        }
                        return@coroutineScope Outcome.Mismatch
                    }
                    val tier = HqQuality.tierOf(stream.format)
                    val verified = HqVerificationStore.Verified(
                        offsetMs = offset,
                        rate = rate,
                        matchPercent = match,
                        tier = tier,
                        sourceTitle = stream.trackTitle,
                        sourceArtist = stream.trackArtist,
                    )
                    HqVerificationStore.put(context, youtubeId, sourceId, trackId, verified)
                    pendingRateLimits.remove(youtubeId)
                    // A downloaded or pre-saved copy may now be replaceable with
                    // this newly proved rung, even if it was already high quality.
                    HqLibraryUpgrade.onVerified(context)
                    Outcome.Verified(verified)
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

    private fun Outcome.verifiedOrNull(): HqVerificationStore.Verified? =
        (this as? Outcome.Verified)?.value

    /**
     * Whether this attempt has used the one retry a pending entry grants.
     *
     * A retry is only consumed by an answer that actually settles the question:
     * a measured verdict, a second 429, or a refusal that cannot fix itself.
     * The play changing ([Outcome.Abandoned]), a host still resting
     * ([Outcome.Deferred]) and a transient reference/decode/alignment failure
     * all leave the entry for the next event — otherwise a screen-off head
     * timeout or a busy decoder would silently cancel the one retry without the
     * candidate ever being measured.
     */
    internal fun Outcome?.settlesPendingRetry(): Boolean = when (this) {
        null -> true
        is Outcome.Abandoned, is Outcome.Deferred -> false
        is Outcome.NoReference -> !reason.transient
        is Outcome.Unavailable -> !transient
        else -> true
    }

    /** Remembers a 429-unmeasured song so a later event can ask once more. */
    private fun notePendingRateLimit(
        context: Context,
        youtubeId: String,
        target: TrackMatcher.Target,
        stream: SourceStream,
        limited: CandidateFetch.RateLimited,
        manual: Boolean,
    ) {
        if (pendingRateLimits.size >= MAX_PENDING_RATE_LIMITS) {
            pendingRateLimits.keys.firstOrNull()?.let { pendingRateLimits.remove(it) }
        }
        pendingRateLimits[youtubeId] = PendingRateLimit(
            context = context.applicationContext ?: context,
            youtubeId = youtubeId,
            target = target,
            stream = stream,
            host = limited.host,
            manual = manual,
        )
        TrackLog.d(
            TAG,
            "decode: hq-verify '${target.title}' will be retried after " +
                "${limited.restMs / 1_000}s rest",
            youtubeId,
        )
    }

    /**
     * Re-asks every pending song whose host has finished resting.
     *
     * Called only from playback events and verification requests, never from a
     * timer. The entry is kept when the attempt was abandoned because the play
     * changed, when the host is still resting, or when the reference/candidate
     * failed for a transient reason; a verified answer, a measured mismatch, a
     * permanent refusal or a second 429 all consume the one retry. The host's
     * rest is re-checked before every attempt because the previous song in this
     * loop may have just been refused and rested it.
     */
    private fun retryPendingRateLimits(exceptYoutubeId: String? = null) {
        val due = pendingRateLimits.values.filter {
            it.youtubeId != exceptYoutubeId &&
                HqHostTracker.restRemainingMs(it.host) <= 0L
        }
        if (due.isEmpty()) return
        if (!pendingRetryRunning.compareAndSet(false, true)) return
        pendingRetryScope.launch {
            try {
                for (pending in due) {
                    // The snapshot above is one moment in time. A retry earlier
                    // in this loop can have just got 429 and extended the host's
                    // rest, so check again before asking the same server.
                    if (HqHostTracker.restRemainingMs(pending.host) > 0L) continue
                    // A concurrent success may already have answered this song.
                    if (pendingRateLimits[pending.youtubeId] !== pending) continue
                    TrackLog.d(
                        TAG,
                        "decode: hq-verify retrying '${pending.target.title}' after host rest",
                        pending.youtubeId,
                    )
                    val outcome = try {
                        verifyOutcome(
                            pending.context,
                            pending.youtubeId,
                            pending.target,
                            pending.stream,
                            pending.manual,
                            recordRateLimit = false,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failed: Exception) {
                        TrackLog.d(
                            TAG,
                            "decode: hq-verify retry failed for ${pending.youtubeId}: ${failed.message}",
                            pending.youtubeId,
                        )
                        null
                    }
                    // An abandoned play, a still-resting host and a transient
                    // reference/decode failure keep the entry: none of them
                    // actually got the one retry. A second 429, a measured
                    // answer or a permanent refusal settles it and must not be
                    // downloaded and decoded again on every later event.
                    if (outcome.settlesPendingRetry()) {
                        pendingRateLimits.remove(pending.youtubeId, pending)
                    }
                }
            } finally {
                pendingRetryRunning.set(false)
            }
        }
    }

    /**
     * The next playback event makes every remembered missing reference stale.
     *
     * A failure belongs to the attempt that met it — a slow head, a decoder
     * that was busy — not to the recording or to the next time the track is
     * played. Clearing here is what makes the next play try again without a
     * ten-minute timer. It is also the event that wakes a song left unmeasured
     * by a 429 once its host has rested.
     */
    fun onPlaybackStarted() {
        noReference.clear()
        playbackEpoch.getAndSet(PlaybackEpoch()).changed.complete(Unit)
        retryPendingRateLimits()
    }

    /** What the sound comparison says about a file already on the device. */
    sealed interface DownloadedSound {
        /**
         * [cached] is true when this is the stored verdict for the source/track
         * pair rather than a fresh decode of the file in hand. The health check
         * still runs its runtime and arrangement checks for a cached answer:
         * the pair may be right while the bytes on disk are not.
         */
        data class Accepted(
            val verified: HqVerificationStore.Verified,
            val cached: Boolean = false,
        ) : DownloadedSound
        data object Rejected : DownloadedSound
        data object Unavailable : DownloadedSound
    }

    /**
     * Compares a downloaded file with the YouTube song version.
     *
     * The reference is the same opening [verify] uses, so a downloaded file is
     * held to the same standard as a stream candidate: a cover, a live take or
     * another arrangement is refused. A verdict already stored for the pair is
     * returned without decoding, and a rejection is remembered for the same
     * reason. [Unavailable] means the question could not be answered — no
     * reference, screen off, unreadable file — and must never be treated as a
     * rejection: deleting a file because the network was down would be worse
     * than leaving it alone.
     *
     * The file is decoded as a stream, ninety seconds at a time, never loaded
     * whole. Nothing here polls or schedules itself; the playback health check
     * calls it once for the file it is about to play.
     */
    suspend fun verifyDownloaded(
        context: Context,
        youtubeId: String,
        uri: String,
        sourceConfigId: String?,
        trackId: String?,
        tier: HqTier?,
    ): DownloadedSound = withContext(Dispatchers.IO) {
        if (youtubeId.isBlank() || uri.isBlank()) return@withContext DownloadedSound.Unavailable
        if (SourceTrackKeys.parse(youtubeId) != null) {
            return@withContext DownloadedSound.Accepted(
                HqVerificationStore.Verified(0L, 1.0, 100, HqTier.NORMAL),
            )
        }
        HqVerificationStore.get(context, youtubeId, sourceConfigId, trackId)?.let {
            return@withContext DownloadedSound.Accepted(it, cached = true)
        }
        if (sourceConfigId != null && HqVerificationStore.isRejected(context, youtubeId, sourceConfigId, trackId)) {
            return@withContext DownloadedSound.Rejected
        }
        val key = "download\u0001$youtubeId\u0001${sourceConfigId.orEmpty()}\u0001${trackId.orEmpty()}"
        val lock = locks.getOrPut(key) { Mutex() }
        try {
            return@withContext lock.withLock {
                coroutineScope {
                    HqVerificationStore.get(context, youtubeId, sourceConfigId, trackId)?.let {
                        return@coroutineScope DownloadedSound.Accepted(it, cached = true)
                    }
                    if (sourceConfigId != null &&
                        HqVerificationStore.isRejected(context, youtubeId, sourceConfigId, trackId)
                    ) {
                        return@coroutineScope DownloadedSound.Rejected
                    }
                    val candidateDeferred = async { localCandidateFeatures(context, uri) }
                    val reference = (referenceFeatures(context, youtubeId, "downloaded file", manual = false)
                        as? Reference.Ready)?.features
                    if (reference == null) {
                        candidateDeferred.cancel()
                        return@coroutineScope DownloadedSound.Unavailable
                    }
                    val candidate = candidateDeferred.await()
                        ?: return@coroutineScope DownloadedSound.Unavailable
                    val result = runCatching {
                        ChromaAlignment.align(reference, candidate, videoComplete = false)
                    }.getOrNull() ?: return@coroutineScope DownloadedSound.Unavailable
                    val match = result.matchPercent ?: 0
                    val offset = result.map?.map(0L) ?: 0L
                    val rate = result.rate ?: 1.0
                    val verdict = HqMatchPolicy.verdict(match, offset, rate)
                    logVerdict(
                        title = "downloaded file",
                        sourceName = "download",
                        match = match,
                        offset = offset,
                        accepted = verdict.accepted,
                        about = youtubeId,
                    )
                    if (!verdict.accepted) {
                        if (!sourceConfigId.isNullOrBlank() && !trackId.isNullOrBlank()) {
                            HqVerificationStore.reject(context, youtubeId, sourceConfigId, trackId)
                        }
                        return@coroutineScope DownloadedSound.Rejected
                    }
                    DownloadedSound.Accepted(
                        HqVerificationStore.Verified(
                            offsetMs = offset,
                            rate = rate,
                            matchPercent = match,
                            tier = tier ?: HqTier.NORMAL,
                        ),
                    )
                }
            }
        } finally {
            locks.remove(key, lock)
        }
    }

    /** The downloaded file's opening, read through the same extractor as a stream. */
    private suspend fun localCandidateFeatures(
        context: Context,
        uri: String,
    ): ChromaAlignment.Features? {
        val song = Song(
            videoId = "",
            title = "",
            artist = "",
            thumbnailUrl = null,
            localUri = uri,
        )
        val source = VersionAudioAligner.openDataSource(context, song) ?: return null
        return try {
            val coroutine = currentCoroutineContext()
            featuresOf(source) { !coroutine.isActive }
        } finally {
            runCatching { source.close() }
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
        reference: String? = null,
        candidate: String? = null,
        bytes: Long? = null,
        measured: Boolean = true,
    ) {
        TrackLog.i(
            TAG,
            "hq-verify: $title $sourceName match=$match% offset=$offset -> " +
                (if (accepted) "accepted" else if (measured) "rejected" else "unmeasured") +
                (reference?.let { " reference=$it" } ?: "") +
                (candidate?.let { " candidate=$it" } ?: "") +
                (bytes?.let { " bytes=$it" } ?: ""),
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
        title: String,
        manual: Boolean,
    ): Reference {
        referenceCache.get(youtubeId)?.let { return Reference.Ready(it) }
        val lock = referenceLocks.getOrPut(youtubeId) { Mutex() }
        try {
            return lock.withLock {
                referenceCache.get(youtubeId)?.let { return@withLock Reference.Ready(it) }
                diskReference(context, youtubeId)?.let { cached ->
                    referenceCache.put(youtubeId, cached)
                    noReference.remove(youtubeId)
                    return@withLock Reference.Ready(cached)
                }
                referenceFeaturesUncached(context, youtubeId, title, manual)
            }
        } finally {
            referenceLocks.remove(youtubeId, lock)
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
        // The song check already knows how to open this track and decode its
        // opening to a small WAV; using the same path avoids a second decoder
        // reading a container the extractor may only have a truncated head of.
        // [VideoAudio] keeps the WAV by song, so the lyrics check and this
        // measurement share one decode rather than paying for two.
        val song = Song(
            videoId = youtubeId,
            title = title,
            artist = "",
            thumbnailUrl = null,
            isVideo = false,
        )
        val wav = try {
            VideoAudio.songWav(context, song, COMPARE_SECONDS)
        } catch (cancelled: CancellationException) {
            logNoReference(title, youtubeId, NoReferenceReason.CANCELLED)
            return Reference.Missing(NoReferenceReason.CANCELLED)
        }
        if (wav == null) {
            noteNoReference(youtubeId, NoReferenceReason.DECODE_FAILED)
            logNoReference(title, youtubeId, NoReferenceReason.DECODE_FAILED)
            return Reference.Missing(NoReferenceReason.DECODE_FAILED)
        }
        val features = featuresOfWav(wav)
        if (features == null) {
            noteNoReference(youtubeId, NoReferenceReason.DECODE_FAILED)
            logNoReference(title, youtubeId, NoReferenceReason.DECODE_FAILED)
            return Reference.Missing(NoReferenceReason.DECODE_FAILED)
        }
        // A partial opening is returned for this comparison but not kept: the
        // rest of the WAV may still arrive, and freezing a short profile would
        // make every later candidate compare against it.
        if (HqReferenceCache.completeEnough(features, COMPARE_SECONDS)) {
            referenceCache.put(youtubeId, features)
            storeReference(context, youtubeId, features)
        }
        noReference.remove(youtubeId)
        return Reference.Ready(features)
    }

    /**
     * The WAV the song check already decoded, turned into a chroma profile.
     *
     * The header is parsed rather than handed to MediaExtractor: the samples
     * are 16-bit PCM the app itself wrote, so the container question that
     * made the stream reference fail does not arise here.
     */
    private fun featuresOfWav(wav: ByteArray): ChromaAlignment.Features? {
        val pcm = WavAudio.monoFloat(wav) ?: return null
        if (pcm.samples.isEmpty() || pcm.sampleRate <= 0) return null
        val extractor = ChromaAlignment.Extractor(pcm.sampleRate)
        extractor.push(pcm.samples)
        return extractor.finish(startSeconds = 0.0).takeIf { it.frames > 0 }
    }

    /**
     * The candidate's opening, downloaded to a cache file before decoding.
     *
     * The extractor cannot read this from a network [MediaDataSource]: its size
     * is unknown and each ranged reopen starts another request, which is what
     * left a 44.1 kHz stereo candidate with no first sample. A head-only file
     * on disk is what the platform extractor handles best, and a FLAC whose
     * tail is missing still opens. The file is deleted on every exit.
     *
     * B is not implemented here: starting this only after the current track's
     * ExoPlayer loader goes idle needs a loading event from PlaybackService and
     * a gate for ordinary streams ([HqHostGate][com.music.bitchord.playback.HqHostGate]
     * tracks upgrade, whole-rendition and automatic library downloads). The
     * bounded retry below fixes the observed 429s without changing when
     * verification starts.
     */
    private suspend fun candidateFetch(
        context: Context,
        stream: SourceStream,
        epoch: PlaybackEpoch,
    ): CandidateFetch = candidateFetch(
        File(context.cacheDir, CANDIDATE_DIR),
        stream,
        ProxyPlayback.mediaClientFor(stream.url),
        epoch,
    )

    internal suspend fun candidateFeatures(
        directory: File,
        stream: SourceStream,
        client: OkHttpClient = ProxyPlayback.mediaClientFor(stream.url),
    ): Candidate? = candidateFetch(directory, stream, client, playbackEpoch.get()).readyOrNull()

    private fun CandidateFetch.readyOrNull(): Candidate? =
        (this as? CandidateFetch.Ready)?.value

    private suspend fun candidateFetch(
        directory: File,
        stream: SourceStream,
        client: OkHttpClient,
        epoch: PlaybackEpoch,
    ): CandidateFetch {
        val needed = HqVerifyPolicy.bytesToFetch(stream.format, COMPARE_SECONDS + 2.0)
        if (needed <= 0L) return CandidateFetch.Failed
        if (!ensureCandidateDirectory(directory)) {
            TrackLog.w(TAG, "decode: hq-verify failed at=cache-dir")
            return CandidateFetch.Failed
        }
        val file = try {
            File.createTempFile("candidate-", ".bin", directory)
        } catch (e: Exception) {
            TrackLog.w(TAG, "decode: hq-verify failed at=cache-file ${e.message}")
            return CandidateFetch.Failed
        }
        return try {
            val bytes = when (val download = downloadCandidate(stream, file, needed, client, epoch)) {
                is DownloadFetch.Bytes -> download.value
                is DownloadFetch.RateLimited ->
                    return CandidateFetch.RateLimited(download.host, download.restMs)
                DownloadFetch.Abandoned -> return CandidateFetch.Abandoned
            }
            if (bytes <= 0L) {
                TrackLog.w(TAG, "decode: hq-verify failed at=download bytes=0")
                return CandidateFetch.Failed
            }
            val coroutine = currentCoroutineContext()
            // abort answers "stop now?", so it is the inverse of isActive. Passing
            // isActive itself stopped every decode on its first pass, which is why
            // no candidate ever reached the comparison.
            val features = featuresOf(file) { !coroutine.isActive }
            // The decoder stops on abort without throwing; confirm here that
            // the verification itself was not abandoned while it ran, before a
            // null is reported as a decode failure.
            coroutine.ensureActive()
            if (features == null) {
                TrackLog.w(TAG, "decode: hq-verify failed at=candidate-decode bytes=$bytes")
                return CandidateFetch.Failed
            }
            CandidateFetch.Ready(Candidate(features, bytes))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // A cancelled call surfaces as an IOException; only report it
            // as a download failure when the coroutine is still wanted.
            currentCoroutineContext().ensureActive()
            TrackLog.w(TAG, "decode: hq-verify failed at=download ${e.message}")
            CandidateFetch.Failed
        } finally {
            runCatching { file.delete() }
        }
    }

    /**
     * Whether [directory] is ready for a candidate head.
     *
     * Several candidates can start together and race to create the same cache
     * folder. A failed `mkdirs` therefore does not fail the attempt when the
     * other job has already made the folder.
     */
    internal fun ensureCandidateDirectory(directory: File): Boolean {
        if (directory.isDirectory) return true
        directory.mkdirs()
        return directory.isDirectory
    }

    /**
     * Downloads the candidate head, waiting out one rate-limit refusal.
     *
     * The wait follows the server's `Retry-After` when it sends one, capped at
     * [HqVerifyPolicy.MAX_CANDIDATE_RETRY_WAIT_MS], and is woken by the next
     * playback event. A second refusal is not retried: the candidate stays
     * unmeasured instead of becoming a stored rejection.
     */
    private suspend fun downloadCandidate(
        stream: SourceStream,
        file: File,
        limit: Long,
        client: OkHttpClient,
        epoch: PlaybackEpoch,
    ): DownloadFetch {
        var attempt = 0
        // One candidate download per host at a time. With AutoPlay filling the
        // queue, the read-ahead verified the current track and the next ones
        // together against the same Cloudflare edge, and the phone run of
        // 10-26 22:23-22:30 got 429 on every second request; the pairs of
        // refusals were always within a second of each other.
        val host = HqHostTracker.hostOf(stream.url).orEmpty()
        while (true) {
            try {
                val bytes = withCandidateHost(host) { downloadPrefix(stream, file, limit, client) }
                HqHostTracker.noteSuccess(host)
                return DownloadFetch.Bytes(bytes)
            } catch (limited: RateLimitedDownload) {
                val retry = HqVerifyPolicy.retryCandidateDownload(
                    retryAfterHeader = limited.retryAfter,
                    attempt = attempt,
                    nowMs = System.currentTimeMillis(),
                )
                val restMs = HqHostTracker.noteRateLimited(host, limited.retryAfter, limited.code)
                logRateLimited(limited, retry)
                if (!retry.retry) {
                    return DownloadFetch.RateLimited(host, restMs)
                }
                if (!samePlaybackAfter(retry.waitMs, epoch)) {
                    TrackLog.d(TAG, "decode: hq-verify retry abandoned at=download track-changed")
                    return DownloadFetch.Abandoned
                }
                attempt++
            }
        }
    }

    /**
     * Runs [block] while holding the one slot for [host] that a candidate
     * download and an automatic library download share.
     *
     * The library pass used to open its own request to a catalogue whose edge
     * was already serving a verification; both are reads of the same server,
     * so they queue here rather than racing for its rate limit.
     */
    internal suspend fun <T> withCandidateHost(host: String?, block: suspend () -> T): T {
        val key = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return block()
        return candidateHostLocks.getOrPut(key) { Mutex() }.withLock { block() }
    }

    /** Waits [waitMs], returning false as soon as the play this attempt belongs to ends. */
    private suspend fun samePlaybackAfter(waitMs: Long, epoch: PlaybackEpoch): Boolean {
        if (playbackEpoch.get() !== epoch) return false
        if (waitMs > 0L) {
            val changed = withTimeoutOrNull(waitMs) { epoch.changed.await() }
            if (changed != null) return false
        }
        return playbackEpoch.get() === epoch
    }

    /**
     * One line for a 429/503: status and only the rate-limit headers that are
     * safe to keep. The URL and any credential headers are never printed.
     */
    private fun logRateLimited(
        limited: RateLimitedDownload,
        retry: HqVerifyPolicy.CandidateRetry,
    ) {
        val headers = RATE_LIMIT_LOG_HEADERS.mapNotNull { name ->
            limited.headerFields.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.let { "$name=${it.take(80)}" }
        }.joinToString(",")
        val action = if (retry.retry) "retry in ${retry.waitMs}ms" else "giving up"
        TrackLog.w(
            TAG,
            "decode: hq-verify rate limited at=download code=${limited.code} " +
                "headers=[$headers] $action",
        )
    }

    /**
     * Fetches the first [limit] bytes of [stream] into [file], using the same
     * client, headers and proxy route as HQ playback.
     *
     * `Range: bytes=0-` lets the server stream from the start; the copy stops
     * as soon as [limit] is reached, so a server that ignores Range cannot
     * make this read the whole track. The call is cancelled as soon as the
     * calling coroutine is, including while a read is blocked: cancelling the
     * call closes the connection, which is what lets the blocked read return
     * instead of waiting for the socket timeout.
     */
    private suspend fun downloadPrefix(
        stream: SourceStream,
        file: File,
        limit: Long,
        client: OkHttpClient,
    ): Long {
        val request = Request.Builder()
            .url(stream.url)
            .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
            .header("Range", "bytes=0-")
            .build()
        val call = client.newCall(request)
        val coroutine = currentCoroutineContext()
        return suspendCancellableCoroutine { continuation ->
            // Cancelling the call closes the connection, which is what lets a
            // blocked execute/read return instead of waiting for the socket
            // timeout. The copy runs on the IO dispatcher the verifier already
            // uses, so the caller's finally deletes the file only after the
            // stream is closed.
            continuation.invokeOnCancellation { call.cancel() }
            val length = try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val headerFields = response.headers.toMultimap()
                        if (HqRetryPolicy.isRateLimited(response.code)) {
                            throw RateLimitedDownload(
                                code = response.code,
                                retryAfter = HqRetryPolicy.retryAfterHeader(headerFields),
                                headerFields = headerFields,
                            )
                        }
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("no body")
                    body.byteStream().use { input ->
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            var remaining = limit
                            while (remaining > 0L) {
                                coroutine.ensureActive()
                                val want = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = input.read(buffer, 0, want)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                remaining -= read
                            }
                            output.flush()
                        }
                    }
                }
                file.length()
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            if (continuation.isActive) continuation.resume(length)
        }
    }

    /**
     * The first [COMPARE_SECONDS] of [file] as a chroma profile, using the
     * same extractor and decoder as the lyrics measurement.
     *
     * [abort] is polled by the decoder loop, so a verification the caller has
     * given up on (the queue moved, or the reference turned out to be missing)
     * stops reading instead of finishing ninety seconds of work nobody wants.
     */
    private fun featuresOf(
        file: File,
        abort: () -> Boolean = { false },
    ): ChromaAlignment.Features? = featuresFrom { onChunk ->
        AudioDecoder.decodeMonoStream(file, 0.0, COMPARE_SECONDS + 1.0, abort = abort, label = "hq-verify", onChunk = onChunk)
    }

    /** The same profile from a downloaded file opened as a data source (a `content://` file has no path). */
    private fun featuresOf(
        source: MediaDataSource,
        abort: () -> Boolean = { false },
    ): ChromaAlignment.Features? = featuresFrom { onChunk ->
        AudioDecoder.decodeMonoStream(source, 0.0, COMPARE_SECONDS + 1.0, abort = abort, label = "hq-verify", onChunk = onChunk)
    }

    private fun featuresFrom(
        decode: ((FloatArray, Int, Double) -> Unit) -> Double?,
    ): ChromaAlignment.Features? {
        var extractor: ChromaAlignment.Extractor? = null
        var sampleRate = 0
        var startSeconds = 0.0
        try {
            val decoded = decode { samples, rate, at ->
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
}

