package com.music.bitchord.ko

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.AudioCache.Upcoming
import com.music.bitchord.playback.BatteryPolicy
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.smart.HqAudioVerify
import com.music.bitchord.playback.smart.HqVerificationStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [KO] prefetch-queue
 *
 * The fork's read-ahead scheduling: verify the next source candidate, honour
 * metered/low-power choices, and wait for the next row's high-quality answer.
 * AudioCache keeps only the one-line call into this object.
 */
class KoPrefetchQueue(
    private val scope: CoroutineScope,
    private val appContext: () -> Context?,
    private val pendingQueue: () -> List<String>,
    private val setPendingQueue: (List<String>) -> Unit,
    private val job: () -> Job?,
    private val setJob: (Job?) -> Unit,
    private val fetchRange: suspend (String, Long, Long) -> Unit,
    private val fetchWhole: suspend (String) -> Unit,
    private val logHqTier: (String, String, SourceStream, HqTier) -> Unit,
    private val tracker: KoPlaybackReadTracker,
    private val retryAnalysisHead: (String) -> Unit,
) {
    private val tag = "BitChord"

    private val hqHeadReady = ConcurrentHashMap<String, CompletableDeferred<SourceStream?>>()

    suspend fun awaitHqHead(videoId: String, timeoutMs: Long): SourceStream? {
        StreamChoice.of(videoId)?.let { stream ->
            val sourceId = stream.sourceConfigId
            if (sourceId != null && SourceRegistry.config(sourceId)?.kind != SourceKind.YOUTUBE) {
                return stream
            }
        }
        val flight = hqHeadReady[videoId] ?: return null
        return withTimeoutOrNull(timeoutMs) { flight.await() }
    }

    fun prefetchQueue(upcoming: List<Upcoming>) {
        val context = appContext() ?: return
        val mediaIds = upcoming.map { it.mediaId }
        if (mediaIds == pendingQueue()) return
        android.util.Log.d("BCFetchDebug", "prefetchQueue: head ${pendingQueue().firstOrNull()} -> ${mediaIds.firstOrNull()}")
        setPendingQueue(mediaIds)
        val headId = mediaIds.firstOrNull()
        val headFlight = if (headId != null) CompletableDeferred<SourceStream?>() else null
        if (headId != null && headFlight != null) {
            hqHeadReady[headId] = headFlight
        }
        job()?.cancel()
        // Both halves of the read-ahead below go through [StreamResolver],
        // which speaks YouTube ids and nothing else. A source-backed track
        // handed to it resolves to a failure, so filtering here saves a dead
        // round trip per queued track rather than changing any outcome —
        // read-ahead for those is a separate job, and their servers are
        // typically a good deal closer than googlevideo anyway.
        //
        Downloads.ensureOpen()
        val videoIds = mediaIds.filter { SourceRegistry.parseTrackKey(it) == null }
            // A track already on disk needs no reading ahead, and read-ahead
            // speaks only to googlevideo: warming one would spend mobile data
            // fetching a second copy of a file the listener deliberately saved,
            // then cache it under a key playback is never going to ask for —
            // it plays the download instead. See [Song.toMediaItem].
            .filter { it !in Downloads.saved.value }
            // The player may already be buffering this item (it buffers the
            // next playlist entry ahead of reach); there is no entry left for
            // read-ahead to own. Re-checked when the fetch actually runs.
            .filterNot(tracker::playerHoldsVideo)
        // The player buffering the next item is a reason not to write its
        // bytes, never a reason to skip its high-quality lookup. Dropping it
        // here sent the lookup to the track *after* next (10-31: 'upgrade
        // ahead: Belly Dancer' while Breathe was next) and released the
        // next-song bundle with no answer (next-prep hq=none(no-answer)). The
        // pinned stream reaches the buffered item through the standby's
        // lossless re-prepare; only its bytes are skipped below.
        val heldHead = headId?.takeIf {
            it !in videoIds &&
                SourceRegistry.parseTrackKey(it) == null &&
                it !in Downloads.saved.value
        }
        // A head that read-ahead will not resolve (a download, a source-backed
        // row) has no high-quality answer coming; release anyone waiting on it.
        if (headId != null && headId !in videoIds && headId != heldHead) {
            headFlight?.complete(null)
        }
        // With substitution possible, only the *bytes* half drops out. Read-
        // ahead builds its own spec below from an id alone and carries none of
        // the title and artist a substitution is matched on — so it resolves
        // to YouTube and would write Opus bytes into the very entry playback
        // is about to fill from a higher-ranked source, under the same key, at
        // whatever offset each of them happened to reach. Reading ahead for a
        // track and then corrupting it is worse than not reading ahead at all.
        //
        // The URL half is a different matter and was thrown out with it, at
        // real cost. Warming [StreamResolver]'s own cache writes nothing to
        // disk and cannot corrupt anything, and it is the difference between
        // the fallback starting instantly and starting with a full client walk
        // — measured at 7.9s. Since the fallback now races the module lookup
        // rather than waiting behind it, that walk is what a track waits on
        // whenever the modules are slow, and warming it here is what makes the
        // race worth running at all.
        val substitutable = SourceResolver.canSubstituteForYouTube()
        setJob((heldHead ?: videoIds.firstOrNull())?.let { next ->
            val target = upcoming.firstOrNull { it.mediaId == next }?.target
            // A track the listener reverted plays from its own `#original`
            // rendition — see [OriginalVersion] — while read-ahead builds its
            // spec from an id alone and so writes under the plain key. Every
            // byte of that would land in an entry playback is never going to
            // read, and pinning a substitute for it would pin a source it is
            // never going to use. The URL half below is still worth having: a
            // reverted track resolves through YouTube, which is what it warms.
            val pinnedToYouTube = OriginalVersion.isPinned(next)
            val nextFlight = if (next == headId) headFlight else null
            scope.launch {
                // With substitution on, the *bytes* half above used to be
                // switched off outright, and the paragraph explaining why is
                // still correct as far as it goes: read-ahead resolving to
                // YouTube on its own would write Opus into the entry a
                // higher-ranked source is about to fill.
                //
                // What it treated as impossible was knowing the answer in
                // advance. A quick source can be asked *here* — see
                // [SourceResolver.prefetchSubstitute] — and once its stream is
                // recorded in [StreamChoice], the question stops being open:
                // every later resolve for this track, read-ahead's own included,
                // is held to that one stream. Both writers then agree on the
                // file and on the `#alt` key it lands under, which is exactly
                // the condition the byte half was missing.
                //
                // A source that is disabled, doesn't have the track, or fails
                // leaves nothing pinned, and this falls through to the same
                // URL-only warm-up it did before — YouTube resolves the track at
                // playback time as usual.
                val warmed = if (substitutable && target != null && !pinnedToYouTube) {
                    // The sound comparison is the gate: a candidate that is a
                    // cover, a live take or a different edit is refused here,
                    // before it can be pinned for the next play. Because it is
                    // applied inside the race, a refused fast answer does not
                    // stop a slower source's real recording from winning. The
                    // first sound then stays YouTube if nothing passes.
                    val currentTier = StreamChoice.of(next)?.format?.let { HqQuality.tierOf(it) }
                        ?: HqVerificationStore.bestTier(context, next)
                        ?: HqTier.NORMAL
                    val found = try {
                        SourceResolver.prefetchSubstitute(target, remember = false) { _, candidate ->
                            HqAudioVerify.verify(context, next, target, candidate) != null
                        }
                    } catch (cancelled: CancellationException) {
                        // A cancelled read-ahead is not a failed lookup: let
                        // the job unwind instead of carrying on to the URL
                        // warm-up after the queue has already moved on.
                        throw cancelled
                    } catch (failed: Exception) {
                        TrackLog.d(tag, "warm-up substitute failed for $next: ${failed.message}", about = next)
                        null
                    }
                    found?.also { candidate ->
                        SourceResolver.rememberOriginal(target, candidate)
                        StreamChoice.remember(next, candidate, substituted = true)
                        logHqTier(next, target.title, candidate, currentTier)
                    }
                } else {
                    null
                }
                // The next-song bundle may be suspended on this; a null is a
                // real answer too (nothing on a higher source passed).
                nextFlight?.complete(warmed)
                // Safe to fill for the same reason in both cases: either nothing
                // outranks YouTube and read-ahead is the only writer, or a
                // source has been pinned and every writer now resolves to it.
                //
                // Bytes are on-disk data the listener may never reach, so on a
                // metered connection only the URL half runs: resolving the next
                // item's stream costs a few small round trips and is what keeps
                // the track change instant; prefetching its audio would spend
                // mobile data on a track a skip may skip over. Low power is the
                // same answer for the bytes half — the URL is still warmed so a
                // transition does not wait on a client walk.
                val metered = AppSettings.meteredConnection.value == true
                val cacheBytes = BatteryPolicy.cachePrefetchBytesAllowed(
                    metered = metered,
                    lowPower = AppSettings.lowPower.value,
                ) && (!substitutable || warmed != null) && !pinnedToYouTube &&
                    // The player already owns this entry; see heldHead.
                    next != heldHead
                if (cacheBytes) {
                    launch(TrackLog.about(next)) {
                        delay(PREFETCH_DELAY_MS)
                        // Low power can begin during the grace delay. Re-read
                        // it here, where the bytes would actually be spent.
                        if (!BatteryPolicy.cachePrefetchBytesAllowed(
                                metered = AppSettings.meteredConnection.value == true,
                                lowPower = AppSettings.lowPower.value,
                            )
                        ) {
                            return@launch
                        }
                        // Visible to [requestAnalysisHead] while these bytes are
                        // being written: an analysis round for the same track
                        // would meet this writer's lock and come up empty. Not
                        // set during the grace delay above, when no write exists
                        // to collide with yet.
                        tracker.retainReadAhead(next)
                        try {
                            val nextHost =
                                (warmed?.url ?: StreamChoice.of(next)?.url)?.let(HqHostTracker::hostOf)
                            if (nextHost != null && HqHostTracker.isResting(nextHost)) {
                                TrackLog.d(
                                    tag,
                                    "read-ahead $next: $nextHost is resting; not fetching it",
                                    about = next,
                                )
                                return@launch
                            }
                            // The current track may still be downloading from
                            // this same host. Its bytes win: wait for that
                            // download to finish rather than opening a second
                            // request and tripping the server's rate limit.
                            // Another host is unaffected and proceeds at once.
                            HqHostTracker.awaitIdle(nextHost, exceptTrack = next)
                            fetchRange(next, 0, PRELOAD_BYTES)
                            fetchWhole(next)
                        } finally {
                            tracker.releaseReadAhead(next)
                            retryAnalysisHead(next)
                        }
                    }
                }
                // Metered or saving power, only the immediate next track's URL
                // is worth minting: it is the one a skip most likely lands on.
                // Every resolve past it is either a request the listener pays
                // for or work done while the phone is trying to save battery.
                launch {
                    delay(PREFETCH_DELAY_MS)
                    // Low power (or metering) can begin during the grace delay.
                    // The byte launch above re-reads it there and skips its
                    // fetch; this half must re-read too, or it would drop the
                    // immediate next track as already fetched and keep walking
                    // the old lookahead while the phone is saving power.
                    val targets = BatteryPolicy.cacheUrlWarmupTargets(
                        candidates = videoIds,
                        metered = AppSettings.meteredConnection.value == true,
                        lowPower = AppSettings.lowPower.value,
                        queueLookahead = QUEUE_LOOKAHEAD,
                        bytesPlanned = cacheBytes,
                    )
                    for (id in targets) {
                        // A track already pinned to another source has no use
                        // for a YouTube URL: nothing will ask for one, and
                        // minting it spends a client walk to fill a cache entry
                        // that is never read.
                        if (id == next && warmed != null) continue
                        runCatching { StreamResolver.resolve(id) }
                            .onFailure { TrackLog.d(tag, "queue warm-up skipped $id: ${it.message}", about = id) }
                        delay(QUEUE_RESOLVE_STAGGER_MS)
                    }
                }
            }.also { warm ->
                // Cancellation before the answer lands still releases waiters.
                warm.invokeOnCompletion { nextFlight?.complete(null) }
            }
        })
    }

    fun cancel() {
        setPendingQueue(emptyList())
        job()?.cancel()
        setJob(null)
    }

    private companion object {
        private const val PRELOAD_BYTES = 1L * 1024 * 1024
        private const val PREFETCH_DELAY_MS = 10_000L
        private const val QUEUE_LOOKAHEAD = 1
        private const val QUEUE_RESOLVE_STAGGER_MS = 500L
    }
}
