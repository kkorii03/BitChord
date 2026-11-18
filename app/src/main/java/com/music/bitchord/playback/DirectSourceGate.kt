package com.music.bitchord.playback

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.ko.KoDirectHqAhead // [KO] hq-ahead
import com.music.bitchord.playback.smart.HqAudioVerify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job // [KO] hq-ahead
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
        dropAheadNotAdmitted() // [KO] hq-ahead
        return true
    }

    /** Opens the window on [mediaId]. Returns false when it was already open. */
    fun openNext(mediaId: String): Boolean {
        val now = open.value
        if (now.next == mediaId || mediaId in now.playing) return false
        open.value = now.copy(next = mediaId)
        dropAheadNotAdmitted() // [KO] hq-ahead
        return true
    }

    /**
     * [KO] hq-ahead: the high-quality half of a lookup is for the song before
     * it starts. Once it is audible (its waiters must not hold its first note)
     * or no longer next (the queue moved), it stops, and that song goes the
     * ordinary way: YouTube first, the second look after the first sound. See
     * [com.music.bitchord.ko.KoDirectHqAhead]. The addon half is left as it
     * always was.
     */
    private fun dropAheadNotAdmitted() {
        val now = open.value
        lookups.forEach { (id, lookup) ->
            if (now.next != id || id in now.playing) lookup.ahead?.cancel()
        }
        KoDirectHqAhead.stopKeptProofsExcept(now.playing) // OPT1: a proof kept for a song no longer audible stops
        com.music.bitchord.ko.KoNextUrlAhead.keepOnly(now.playing + listOfNotNull(now.next)) // [KO] UPG-speed 2
        com.music.bitchord.ko.KoDirectLead.keepOnly(now.playing, now.next) // [KO] direct-lead
        com.music.bitchord.ko.KoLateDirectProof.keepOnly(now.next?.takeIf { it !in now.playing }) // [KO] nextprep fix 3
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

    /**
     * [ahead]: cancels the lookup's high-quality half ([KO] hq-ahead); null when it has none.
     * [answer]: what [lookup]'s waiters take, when [work] runs on past it ([KO] late-window).
     */
    private class Lookup(
        val work: Deferred<SourceStream?>,
        val startedAtMs: Long,
        val ahead: Job? = null,
        val answer: Deferred<SourceStream?> = work,
    )

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
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS - waited) { lookup.answer.await() }
    }

    /**
     * Starts the addon lookup without waiting, so a track that is already
     * playing can race it against YouTube instead of sitting out the whole
     * window — see [firstAnswer].
     */
    internal fun startLookup(videoId: String, target: TrackMatcher.Target): Deferred<SourceStream?>? =
        start(videoId, target)?.work

    /** [KO] direct-lead: this lookup's search and proof, run before the window and outside [lookups]; see KoDirectLead. */
    internal suspend fun leadLookup(videoId: String, target: TrackMatcher.Target): SourceStream? =
        com.music.bitchord.ko.KoDirectLead.lookup(videoId, target, KoDirectHqAhead.proofForTest ?: proofOf(videoId, target)) { youTubeLength(videoId) }

    private fun start(videoId: String, target: TrackMatcher.Target): Lookup? {
        // [KO] direct-lead: a plain-id read (the mix analysis's head, held at the
        // gate since the song started) has no title to search with. Phone 11-19
        // 01:54: it was the window's first lookup, `no addon served ''`, and as
        // a table entry it could have handed its empty answer to the titled
        // lookup starting beside it. It only joins: the titled lookup, or the lead.
        if (target.title.isBlank()) {
            lookups[videoId]?.let { return it }
            if (com.music.bitchord.ko.KoDirectLead.reusable(videoId) == null) return null
            return Lookup(
                work = scope.async(TrackLog.about(videoId)) { com.music.bitchord.ko.KoDirectLead.reused(videoId) },
                startedAtMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
        val proof = KoDirectHqAhead.proofForTest ?: proofOf(videoId, target) // [KO] hq-ahead
        // [KO] hq-ahead: the next track, not yet audible, also asks Telegram/JioSaavn.
        val nextNotPlaying = open.value.let { it.next == videoId && videoId !in it.playing }
        val ahead = KoDirectHqAhead.wanted(videoId, target, nextNotPlaying = nextNotPlaying, hasProof = proof != null)
        // [KO] late-window: the next track's addons, before the search, so a timeout in it can be told apart.
        val marks = if (nextNotPlaying && proof != null) com.music.bitchord.ko.KoLateWindowSearch.marks() else null
        // [KO] first-audio: when every addon is resting, the search can only
        // time out. Skipping the wait lets YouTube start at once instead of
        // after the whole direct-source window.
        if (!ahead && !directLookupWorthWaiting(
                SourceResolver.directAddons().map { SourceResolver.SourceCooldown.isResting(it.configId) },
            )
        ) {
            TrackLog.d(TAG, "all direct addons are resting; not waiting for $videoId", about = videoId)
            return null
        }
        val aheadOf = if (ahead) Job() else null // [KO] hq-ahead
        val answer = scope.async(TrackLog.about(videoId), start = CoroutineStart.LAZY) {
                // [KO] direct-lead: the lead's proved copy, or the lead still proving, instead of a second search.
                com.music.bitchord.ko.KoDirectLead.reused(videoId)?.let { return@async it }
                KoDirectHqAhead.lookup( // [KO] hq-ahead
                    target.title,
                    aheadOf,
                    addons = {
                        SourceResolver.directStream(
                            target,
                            mediaId = videoId,
                            verify = proof,
                            lengthOf = { youTubeLength(videoId) },
                        )
                    },
                    hq = proof?.takeIf { ahead }?.let { verify ->
                        // OPT1: a proof in flight when the song starts runs on for the second look.
                        val kept = KoDirectHqAhead.keptOnStart(videoId, scope, { isPlaying(videoId) }, verify)
                        suspend { KoDirectHqAhead.stream(target, kept) }
                    },
                )
            }
        // [KO] late-window: the waiters keep [answer] and its 4 s per-addon cap; an addon
        // that timed out in it is searched on with the lead's budget, for the late proof.
        val work = if (marks == null) answer else scope.async(TrackLog.about(videoId), start = CoroutineStart.LAZY) {
            answer.await() ?: com.music.bitchord.ko.KoLateWindowSearch.after(videoId, marks, { open.value.admits(videoId) }) {
                SourceResolver.directStream(
                    target,
                    mediaId = videoId,
                    verify = proof,
                    lengthOf = { youTubeLength(videoId) },
                    sourceTimeoutMs = com.music.bitchord.ko.KoDirectLead.LEAD_SOURCE_TIMEOUT_MS,
                )
            }
        }.also { late -> late.invokeOnCompletion { if (it != null) answer.cancel() } }
        val fresh = Lookup(
            work = work,
            startedAtMs = android.os.SystemClock.elapsedRealtime(),
            ahead = aheadOf,
            answer = answer,
        )
        val running = lookups.putIfAbsent(videoId, fresh)
        if (running != null) {
            fresh.work.cancel()
            answer.cancel()
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
        // [KO] manifest-verify: a manifest is now measurable, but a direct open is progressive; it stays for the upgrade swap.
        // [KO] nextprep B4: and, under Automix for a song not yet audible, on YouTube's clock (the pin opens at the YouTube cue).
        return { stream ->
            !StreamContainer.isManifest(stream.url) &&
                HqAudioVerify.verify(context, videoId, target, stream)
                    ?.let { com.music.bitchord.ko.koDirectOnClock(videoId, stream, it, audible = isPlaying(videoId)) } == true
        }
    }

    internal suspend fun firstAnswer(
        direct: Deferred<SourceStream?>,
        fallback: Deferred<Result<String>>,
        videoId: String? = null,
    ): FirstAnswer? = select {
        direct.onAwait { it?.let(FirstAnswer::Addon) }
        fallback.onAwait { result ->
            if (result.isSuccess) FirstAnswer.YouTube(result.getOrThrow())
            else lastResort(videoId, direct)?.let(FirstAnswer::Addon)
        }
    }

    /**
     * How long, from the lookup's start, a track whose YouTube walk failed may
     * still wait for an addon before its start gives up. The addon is all that
     * is left then, so the wait is longer than [LOOKUP_TIMEOUT_MS]; but it was
     * unbounded, and the resolve runs on the player's loader thread, which has
     * no timeout of its own: an addon search (or its proof download) that
     * never answered left the track loading in silence with nothing to recover
     * it. Twice the window: an addon that has not answered by then is not
     * going to before the listener gives up on the track.
     */
    const val LAST_RESORT_TIMEOUT_MS = 2 * LOOKUP_TIMEOUT_MS

    /**
     * [work]'s answer, waited for at most until [LAST_RESORT_TIMEOUT_MS] after
     * [videoId]'s lookup started (from now when it is not in the table). Null
     * past it: the caller fails the start as it does when no addon has it.
     * The lookup itself runs on for the second look, as always.
     */
    internal suspend fun lastResort(videoId: String?, work: Deferred<SourceStream?>): SourceStream? {
        if (work.isCompleted) return work.await()
        val now = android.os.SystemClock.elapsedRealtime()
        val started = videoId?.let { lookups[it]?.startedAtMs } ?: now
        val left = (LAST_RESORT_TIMEOUT_MS - (now - started)).coerceAtLeast(1L)
        return withTimeoutOrNull(left) { work.await() }
    }

    /**
     * Whether waiting for the addons could still produce an answer. Each flag
     * is one addon resting after repeated failures; when all are, the search
     * would spend the window only to return null, so playback goes straight to
     * YouTube.
     */
    internal fun directLookupWorthWaiting(addonResting: List<Boolean>): Boolean =
        addonResting.any { !it }

    /**
     * YouTube's runtimes for tracks queued without one, looked up for the
     * addon match and kept so playback's decoded-length check measures against
     * the same figure. Bounded: a long session queues a lot of tracks.
     */
    private val lengths = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > LENGTHS_KEPT
    }

    /** YouTube's runtime for [videoId], when [youTubeLength] has looked it up. */
    fun knownLength(videoId: String): Int? = synchronized(lengths) { lengths[videoId] }

    /**
     * YouTube's runtime for [videoId], from its watch-queue entry: metadata
     * only, one `next` call, no audio. Asked only for a track whose queue row
     * carried no duration — nearly every row in the app shows one, and that
     * figure rides in the playback URI already — and only alongside the addon
     * search, so it costs no time of its own. See [SourceResolver.directStream].
     */
    private suspend fun youTubeLength(videoId: String): Int? {
        knownLength(videoId)?.let { return it }
        val seconds = withTimeoutOrNull(LENGTH_TIMEOUT_MS) {
            YtMusicRepository.trackLinks(videoId).getOrNull()
                ?.durationText
                ?.let(TrackMatcher::secondsOf)
        }
        if (seconds == null) {
            TrackLog.d(TAG, "no YouTube runtime for $videoId; matching addons on title and artist", about = videoId)
            return null
        }
        synchronized(lengths) { lengths[videoId] = seconds }
        TrackLog.d(TAG, "YouTube runtime for $videoId is ${seconds}s; matching addons against it", about = videoId)
        return seconds
    }

    private const val LENGTHS_KEPT = 256

    /** Well inside an addon search's time, which is what it runs beside. */
    private const val LENGTH_TIMEOUT_MS = 5_000L

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
        val running = stillLooking(videoId)
        val pending = QualityUpgrade.settledForLess(
            mediaId = videoId,
            target = target,
            inFlight = running,
            playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
        )
        if (!pending) NerdStats.onLosslessRaceEnd(videoId)
        // [KO] nextprep fix 3: a proof landing before the row sounds goes to the standby, not only the mid-song swap.
        if (pending) com.music.bitchord.ko.KoLateDirectProof.watch(videoId, running) { isPlaying(videoId) }
    }
}
