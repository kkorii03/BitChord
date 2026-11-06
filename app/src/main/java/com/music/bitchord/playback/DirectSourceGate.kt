package com.music.bitchord.playback

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.smart.HqAudioVerify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct Source Stream: a YouTube-queued track plays from an addon ranked
 * above YouTube from its first note, and nothing is fetched for a track until
 * it is about to play.
 *
 * Without it, a track starts on whichever of YouTube and the addons answers
 * first. That is nearly always YouTube, because read-ahead warmed its URL, and
 * then [QualityUpgrade] swaps the addon's copy in mid-song. That is two
 * resolves, two streams and a seam for every track. Read-ahead adds load on
 * top: it runs on every play, skip and seek, for tracks that may never be
 * reached.
 *
 * With it on, three things change:
 *
 *  1. **One resolve, one stream, when an addon answers.** [lookup] asks the
 *     addons and the caller waits for the answer, which then plays to the end.
 *     YouTube is only the fallback when no addon answered in time, and then
 *     the track is left to [QualityUpgrade] exactly as it is with this off —
 *     see [startedOnYouTube].
 *  2. **Nothing is fetched early.** A track that is neither playing nor next
 *     within [WINDOW_MS] of its transition is held at the resolver — see
 *     [awaitOpen]. That covers ExoPlayer's own gapless preparation of the next
 *     item as well as read-ahead, because both open through the same resolver.
 *     A skip interrupts the loader thread, which cancels the wait.
 *  3. **The window opens on playback alone.** [PlaybackService] opens it from
 *     a tick, only while playing and only once the playhead has run on its own
 *     past the last seek. Skipping or scrubbing never triggers a fetch for the
 *     track after.
 *
 * Automix analysis is outside all of this: its `automix_opus` reads resolve
 * to YouTube before this gate is consulted, so it keeps measuring the Opus
 * copy it always has.
 */
object DirectSourceGate {

    private const val TAG = "BitChord"

    /** How far ahead of a transition the next track may be resolved and read ahead. */
    const val WINDOW_MS = 25_000L

    /**
     * How long playback waits for the addons before falling back to YouTube.
     *
     * Shorter than [WINDOW_MS] on purpose: a lookup started when the window
     * opens is answered or given up on with ten seconds still to go, which is
     * time for the YouTube fallback to resolve and buffer before the blend.
     *
     * A limit on the wait, not on the lookup, which runs on until the addon
     * client answers or gives up: a cold FLAC can take an addon longer than
     * this to produce.
     */
    const val LOOKUP_TIMEOUT_MS = 15_000L

    /**
     * Whether Direct Source Stream is on *and* has an addon to stream from.
     *
     * Never during a Listen Together party: a party seeks every member to the
     * host's position and expects the track to be there, and a fetch held
     * until the window opens would leave this phone behind the others.
     */
    fun isActive(): Boolean =
        AppSettings.directSourceStream.value && !ListenTogether.state.value.inParty &&
            SourceResolver.directAddons().isNotEmpty()

    private data class Open(val playing: Set<String>, val next: String?)

    private val open = MutableStateFlow(Open(emptySet(), null))

    /**
     * What is audible right now: the session's track and, mid-crossfade, the
     * other player's. Main thread. A change closes the window on whatever was
     * next, so a skip never inherits the previous track's read-ahead.
     *
     * @return whether anything changed.
     */
    fun onPlaying(mediaIds: Set<String>): Boolean {
        val now = open.value
        if (now.playing == mediaIds) return false
        open.value = Open(mediaIds, now.next?.takeIf { it in mediaIds })
        return true
    }

    /** Opens the window on [mediaId]. Returns false when it was already open. */
    fun openNext(mediaId: String): Boolean {
        val now = open.value
        if (now.next == mediaId || mediaId in now.playing) return false
        open.value = now.copy(next = mediaId)
        return true
    }

    private fun Open.admits(mediaId: String) = mediaId in playing || mediaId == next

    /** Whether [mediaId] is audible now, rather than a track only warmed for later. */
    fun isPlaying(mediaId: String): Boolean = mediaId in open.value.playing

    /**
     * Blocks a resolve for [mediaId] until it is playing or next inside the
     * window. Returns at once when the gate is off, the track is already
     * admitted, or the calling thread is inside [ungated].
     *
     * Runs on a loader thread under `runBlocking`. A released media period
     * interrupts that thread, which cancels the wait, so a skipped track never
     * leaves a loader parked here.
     */
    suspend fun awaitOpen(mediaId: String) {
        if (bypass.get() == true || !isActive() || open.value.admits(mediaId)) return
        TrackLog.d(TAG, "holding $mediaId until it is within ${WINDOW_MS / 1000}s of playing", about = mediaId)
        combine(open, AppSettings.directSourceStream, ListenTogether.state) { state, on, party ->
            !on || party.inParty || state.admits(mediaId)
        }
            .first { it }
        TrackLog.d(TAG, "released $mediaId", about = mediaId)
    }

    /** How much of the next track has to be on disk before it counts as ready to blend into. */
    private const val READY_BYTES = 512L * 1024

    /** How often [isStreamReady] may look at the cache for a track that isn't ready yet. */
    private const val READY_RECHECK_MS = 1_000L

    /** The last track asked about, whether it was ready, and when the cache was last asked. */
    private var readyCheck: Triple<String, Boolean, Long>? = null

    /**
     * Whether [item]'s audio is on disk far enough to start playing it, for
     * the Automix marker on the seek bar. Main thread.
     *
     * A file on the device is always ready. A streamed track needs its opening
     * [READY_BYTES] cached — written by [AudioCache.prefetchDirect] or by the
     * player's own preparation, or left over from an earlier play.
     *
     * Asked every crossfade tick, so the cache is read at most once a
     * [READY_RECHECK_MS] per track, and a yes is kept: bytes on disk do not
     * stop being there mid-song.
     */
    fun isStreamReady(item: androidx.media3.common.MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        if (uri.scheme != "bitchord") return true
        val now = android.os.SystemClock.elapsedRealtime()
        readyCheck?.let { (id, ready, at) ->
            if (id == item.mediaId && (ready || now - at < READY_RECHECK_MS)) return ready
        }
        // Or the whole file, for a track shorter than the threshold.
        val ready = AudioCache.cachedPrefixBytes(uri) >= READY_BYTES || AudioCache.isFullyCached(uri)
        readyCheck = Triple(item.mediaId, ready, now)
        return ready
    }

    private val bypass = ThreadLocal<Boolean>()

    /**
     * Runs [block] with the gate open, for a resolve made on behalf of
     * something other than this device's player. A Cast receiver is handed
     * URLs for items the phone is not playing.
     */
    fun <T> ungated(block: () -> T): T {
        val was = bypass.get()
        bypass.set(true)
        try {
            return block()
        } finally {
            bypass.set(was)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private class Lookup(val work: Deferred<SourceStream?>, val startedAtMs: Long)

    private val lookups = ConcurrentHashMap<String, Lookup>()

    /**
     * The addon stream for [videoId], shared between whoever asks for it at
     * once. Read-ahead opening the window and the player's own resolve arrive
     * together, and should cost one search, not two.
     *
     * The wait is bounded by [LOOKUP_TIMEOUT_MS] from when the lookup started,
     * however late the caller joined it. Null means no addon had it in time,
     * and the caller falls back to YouTube and says so with [startedOnYouTube].
     */
    suspend fun lookup(videoId: String, target: TrackMatcher.Target): SourceStream? {
        val lookup = start(videoId, target) ?: return null
        val waited = android.os.SystemClock.elapsedRealtime() - lookup.startedAtMs
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS - waited) { lookup.work.await() }
    }

    /**
     * Starts the addon lookup without waiting, so a track that is already
     * playing can race it against YouTube instead of sitting out the whole
     * window — see [firstAnswer].
     */
    internal fun startLookup(videoId: String, target: TrackMatcher.Target): Deferred<SourceStream?>? =
        start(videoId, target)?.work

    private fun start(videoId: String, target: TrackMatcher.Target): Lookup? {
        // [KO] first-audio: when every addon is resting, the search can only
        // time out. Skipping the wait lets YouTube start at once instead of
        // after the whole direct-source window.
        if (!directLookupWorthWaiting(
                SourceResolver.directAddons().map { SourceResolver.SourceCooldown.isResting(it.configId) },
            )
        ) {
            TrackLog.d(TAG, "all direct addons are resting; not waiting for $videoId", about = videoId)
            return null
        }
        val fresh = Lookup(
            work = scope.async(TrackLog.about(videoId), start = CoroutineStart.LAZY) {
                SourceResolver.directStream(target, mediaId = videoId, verify = proofOf(videoId, target))
            },
            startedAtMs = android.os.SystemClock.elapsedRealtime(),
        )
        val running = lookups.putIfAbsent(videoId, fresh)
        if (running != null) {
            fresh.work.cancel()
        } else {
            fresh.work.invokeOnCompletion { lookups.remove(videoId, fresh) }
            fresh.work.start()
        }
        return running ?: fresh
    }

    /**
     * The first answer from [direct] and [fallback], for a track that is
     * already playing. Waiting [LOOKUP_TIMEOUT_MS] here buys the addon's
     * seamless first note at the cost of silence the listener did not ask
     * for; whichever can serve a note first wins instead. A losing addon is
     * still running and the caller hands it to the second look.
     */
    internal sealed interface FirstAnswer {
        data class Addon(val stream: SourceStream) : FirstAnswer
        data class YouTube(val url: String) : FirstAnswer
    }

    /**
     * The proof a direct stream has to clear before it plays: the same
     * ninety-second sound comparison, against the same YouTube reference, that
     * the upgrade path demands before it will swap a candidate in — see
     * [HqAudioVerify].
     *
     * It is run *inside* the lookup rather than after it, so it spends the
     * lookup's own budget and nothing else. [LOOKUP_TIMEOUT_MS] is not extended
     * for it, and that cap is the whole of the promise kept here: a candidate
     * that cannot be proved inside the window is not served, and the caller
     * falls back to YouTube exactly as it does when no addon answers in time —
     * [startedOnYouTube] then hands the track to [QualityUpgrade], which
     * upgrades it on its own terms, proof and all. On the path where the
     * listener is already hearing something, [firstAnswer] races YouTube's own
     * walk beside this one, so nothing about that resolve waits for the
     * comparison at all.
     *
     * The cost is named rather than hidden: a candidate that used to play two
     * seconds in now waits for its proof, and one whose proof does not land
     * before the cap leaves the track on YouTube for the second look to
     * upgrade. Sound already playing is never interrupted either way, which is
     * why this is affordable at all.
     *
     * Null when there is no application context to compare with — a unit test,
     * or the gate reached before the app has one — and the probe is then the
     * whole of the proof.
     */
    private fun proofOf(
        videoId: String,
        target: TrackMatcher.Target,
    ): (suspend (SourceStream) -> Boolean)? {
        if (!AppSettings.appContextReady()) return null
        val context = AppSettings.appContext
        return { stream -> HqAudioVerify.verify(context, videoId, target, stream) != null }
    }

    internal suspend fun firstAnswer(
        direct: Deferred<SourceStream?>,
        fallback: Deferred<Result<String>>,
    ): FirstAnswer? = select {
        direct.onAwait { it?.let(FirstAnswer::Addon) }
        fallback.onAwait { result ->
            if (result.isSuccess) FirstAnswer.YouTube(result.getOrThrow())
            else direct.await()?.let(FirstAnswer::Addon)
        }
    }

    /**
     * Whether waiting for the addons could still produce an answer. Each flag
     * is one addon resting after repeated failures; when all are, the search
     * would spend the window only to return null, so playback goes straight to
     * YouTube.
     */
    internal fun directLookupWorthWaiting(addonResting: List<Boolean>): Boolean =
        addonResting.any { !it }

    /** The lookup for [videoId] if it is still going, past the point anyone waits for it. */
    fun stillLooking(videoId: String): Deferred<SourceStream?>? = lookups[videoId]?.work

    /**
     * [videoId] is about to play from YouTube because the addons did not
     * answer in time, so it gets the second look it would get with Direct
     * Source Stream off. A lookup still running is handed over and swaps in
     * when it lands; one that came back empty leaves the second look to search
     * for itself.
     *
     * Called only once the YouTube URL is in hand, because that walk is what
     * sets the bitrate a candidate has to beat.
     */
    fun startedOnYouTube(videoId: String, target: TrackMatcher.Target) {
        val pending = QualityUpgrade.settledForLess(
            mediaId = videoId,
            target = target,
            inFlight = stillLooking(videoId),
            playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
        )
        if (!pending) NerdStats.onLosslessRaceEnd(videoId)
    }
}
