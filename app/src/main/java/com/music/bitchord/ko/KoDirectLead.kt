package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.BatteryPolicy
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Direct lead: the next track's addon/high-quality lookup, proof included, run
 * [LEAD_MS] before the transition instead of at [DirectSourceGate.WINDOW_MS].
 *
 * 11-18 16:xx log (vc213, addon proving in 5-25 s): the window opens 20.5-21.4 s
 * before the transition and the standby opens the next item about 5.5 s later,
 * waiting at most [DirectSourceGate.LOOKUP_TIMEOUT_MS] from the lookup's start.
 * A cold addon search alone timed out at 4 s (APT DiTd771WumE), and search plus
 * proof took 7.9-17.6 s, so a proof begun at the window rarely landed before the
 * standby gave up and the song started on YouTube, to be swapped mid-song.
 *
 * The answer is handed over ([reused]): the window's lookup takes the lead's
 * proved copy, or joins the lead still proving, instead of searching again.
 * vc213 night log: the window's own second search (search, `/stream`, probe)
 * was what timed out at 4 s and sent proved rows to YouTube. A lead that ended
 * with nothing leaves the window's ordinary lookup exactly as before; it then
 * finds the pair's stored verdict, the search memo and the addon's warm cache.
 * A proof that lands too late still leaves the mid-song swap as before.
 *
 * Driven by the playing sampler's five-second tick in
 * PlaybackService.driveDirectWindow, past its pause and seek-settle returns: no
 * timer of its own, nothing while paused, and a lead for a track the queue moved
 * away from is stopped by [keepOnly]. Never while saving power or on a metered
 * connection ([BatteryPolicy.cachePrefetchBytesAllowed], the rule read-ahead
 * bytes and hq-ahead follow), and never without a proof.
 */
internal object KoDirectLead {

    /**
     * How far ahead of the transition the lead starts. Search plus proof took
     * up to 17.6 s on the log and a cold search can spend its 4 s timeout
     * first (the addon keeps answering into its own cache, so the window's
     * search is then a hit), and the proof should be stored before the window
     * opens at T-25: 25 + 17.6 + 4 = 46.6 s, plus the 5 s tick = 52 s. The tick
     * fires 55-60 s ahead at 60 s. More only adds bytes spent on a skip.
     */
    const val LEAD_MS = 60_000L

    /**
     * Each addon's search-and-open budget for the lead only. The window keeps
     * [SOURCE_LOOKUP_TIMEOUT_MS] (its standby has no room for more); 11-18 log:
     * 7 of 34 Private Hi-Res searches timed out at exactly 4 s and answered the
     * re-look 15-19 s after the search began. Started at T-55..60, 15 s plus a
     * 17.6 s proof still ends near the window's T-25, and a timeout counts once
     * toward the rest exactly as a 4 s one does.
     */
    const val LEAD_SOURCE_TIMEOUT_MS = 15_000L

    /**
     * The pair already led. Once only: a timed-out search is a failure toward
     * the addon's 30-minute rest (two in a row), and an empty or refused answer
     * is in the search memo and the verdict store, so asking again buys nothing.
     */
    private var pair: Pair<String, String>? = null
    private var job: Deferred<SourceStream?>? = null
    private var ledAtNs = 0L

    /** How old a lead the window may still take: the lead's span plus room for a short pause. */
    private const val REUSE_MAX_NS = 5L * 60_000 * 1_000_000

    /** What runs one lead; a test swaps it. */
    internal var work: suspend (String, TrackMatcher.Target) -> SourceStream? =
        { id, target -> DirectSourceGate.leadLookup(id, target) }

    /** Whether [remainingMs] to the transition is the lead's span: inside [LEAD_MS], before the window. */
    internal fun inLead(remainingMs: Long): Boolean =
        remainingMs > DirectSourceGate.WINDOW_MS && remainingMs <= LEAD_MS

    /** Whether this tick starts the lead for [nextId] after [currentId]: once per pair. */
    @Synchronized
    internal fun due(currentId: String, nextId: String, remainingMs: Long): Boolean {
        if (!inLead(remainingMs)) return false
        val now = currentId to nextId
        if (pair == now) return false
        job?.cancel()
        job = null
        pair = now
        return true
    }

    /** The row streams its own YouTube id and nothing already settles it. */
    internal fun eligible(nextId: String, streamedId: String?): Boolean = skipReason(nextId, streamedId) == null

    /** Why [eligible] is false, for the phone log; null when the lead may run. */
    internal fun skipReason(nextId: String, streamedId: String?): String? {
        val metered = AppSettings.meteredConnection.value == true
        val lowPower = AppSettings.lowPower.value
        return when {
            streamedId != nextId -> "streams ${streamedId ?: "no YouTube id"}"
            !BatteryPolicy.cachePrefetchBytesAllowed(metered = metered, lowPower = lowPower) ->
                if (lowPower) "saving power" else "metered connection"
            nextId in Downloads.saved.value -> "saved"
            OriginalVersion.isPinned(nextId) -> "kept on its original upload"
            StreamChoice.isSubstitute(nextId) -> "already on a substitute"
            StreamChoice.substitutesRefused(nextId) -> "substitutes refused"
            nextId in QualityUpgrade.refused -> "upgrades refused"
            SourceResolver.directAddons().none { !SourceResolver.SourceCooldown.isResting(it.configId) } ->
                "no addon awake"
            else -> null
        }
    }

    /** The last skip logged, so the five-second tick says each reason once per pair. */
    private var skipped: Triple<String, String, String>? = null

    @Synchronized
    private fun noteSkip(currentId: String, nextId: String, why: String, remainingMs: Long) {
        val now = Triple(currentId, nextId, why)
        if (skipped == now) return
        skipped = now
        TrackLog.d(TAG, "direct-lead: $nextId skipped ($why) ${remainingMs}ms before the transition", about = nextId)
    }

    /** One playing tick, gate active, before the window: [nextId] streams [streamedId]. */
    fun onTick(
        scope: CoroutineScope,
        currentId: String,
        nextId: String,
        streamedId: String?,
        remainingMs: Long,
        target: () -> TrackMatcher.Target,
    ) {
        if (!inLead(remainingMs)) return
        skipReason(nextId, streamedId)?.let { return noteSkip(currentId, nextId, it, remainingMs) }
        if (!due(currentId, nextId, remainingMs)) return
        val wanted = target()
        if (wanted.title.isBlank()) return noteSkip(currentId, nextId, "no title", remainingMs)
        if (wanted.isVideo) return noteSkip(currentId, nextId, "a video", remainingMs)
        TrackLog.d(TAG, "direct-lead: looking up $nextId ${remainingMs}ms before the transition", about = nextId)
        synchronized(this) {
            ledAtNs = System.nanoTime()
            job = scope.async(Dispatchers.IO + TrackLog.about(nextId)) {
                val startedAt = System.nanoTime()
                val answer = try {
                    work(nextId, wanted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TrackLog.d(TAG, "direct-lead: $nextId failed (${e.javaClass.simpleName})", about = nextId)
                    null
                }
                TrackLog.d(
                    TAG,
                    "direct-lead: $nextId ${answer?.let { "proved ${it.format.summary}, kept for the window" } ?: "none"} " +
                        "ms=${(System.nanoTime() - startedAt) / 1_000_000}",
                    about = nextId,
                )
                answer
            }
        }
    }

    /** The lead for [nextId] while it may still prove, or once it proved; null after none, a failure or a stop. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    internal fun reusable(nextId: String): Deferred<SourceStream?>? {
        if (pair?.second != nextId) return null
        // A lead left over from a long pause: its stream URL may have gone stale, so the window searches fresh.
        if (System.nanoTime() - ledAtNs > REUSE_MAX_NS) return null
        val lead = job ?: return null
        if (lead.isCancelled) return null
        if (lead.isCompleted && lead.getCompleted() == null) return null
        return lead
    }

    /**
     * The window's lookup for [videoId] takes the lead's answer instead of a
     * second search: proved already, or joined while it is still proving (the
     * window's own wait still caps how long playback waits). Null leaves the
     * window's ordinary lookup to run as it always did.
     */
    internal suspend fun reused(videoId: String): SourceStream? {
        val lead = reusable(videoId) ?: return null
        val joined = !lead.isCompleted
        val answer = try {
            lead.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the window's own cancellation still propagates
            null
        }
        // Refused while it waited (a broken substitute, an upgrade that killed the row): not served.
        val usable = answer?.takeIf { !StreamChoice.substitutesRefused(videoId) && videoId !in QualityUpgrade.refused }
        TrackLog.d(
            TAG,
            if (usable != null) {
                "direct-lead: $videoId proved ${usable.format.summary} reused by window" + if (joined) " (joined while proving)" else ""
            } else {
                "direct-lead: $videoId gave the window nothing; it searches as before"
            },
            about = videoId,
        )
        return usable
    }

    /**
     * DirectSourceGate's drop: a lead runs on while the song it followed is
     * still playing with no window open yet, while its track is next (the
     * window opened on it) or once it is audible (its proof serves the second
     * look). Otherwise the queue moved and it stops. The pair stays led, so
     * coming back to it does not ask the addon a second time.
     */
    @Synchronized
    fun keepOnly(playing: Set<String>, next: String?) {
        val (currentId, nextId) = pair ?: return
        if (nextId in playing || nextId == next || (next == null && currentId in playing)) return
        val running = job ?: return
        if (running.isActive) TrackLog.d(TAG, "direct-lead: dropped $nextId, no longer next", about = nextId)
        running.cancel()
        job = null
    }

    /**
     * The lookup itself: the window's addon search and, where hq-ahead would
     * ask them, the high-quality sources, behind the same proof. Null without
     * a proof: a copy that was not compared is not worth leading.
     */
    internal suspend fun lookup(
        videoId: String,
        target: TrackMatcher.Target,
        proof: (suspend (SourceStream) -> Boolean)?,
        lengthOf: suspend () -> Int?,
    ): SourceStream? {
        val verify = proof ?: return null
        return KoDirectHqAhead.lookup(
            target.title,
            null,
            addons = {
                SourceResolver.directStream(
                    target,
                    mediaId = videoId,
                    verify = verify,
                    lengthOf = lengthOf,
                    sourceTimeoutMs = LEAD_SOURCE_TIMEOUT_MS,
                )
            },
            hq = suspend { KoDirectHqAhead.stream(target, verify) }
                .takeIf { KoDirectHqAhead.wanted(videoId, target, nextNotPlaying = true, hasProof = true) },
        )
    }

    /** Test hook: forget everything. */
    @Synchronized
    internal fun reset() {
        job?.cancel()
        job = null
        pair = null
        skipped = null
    }

    private const val TAG = "BitChord"
}
