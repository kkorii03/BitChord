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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 * Nothing is handed over: the answer is thrown away. What it leaves is the
 * pair's stored verdict ([com.music.bitchord.playback.smart.HqAudioVerify]),
 * the search memo and the addon's own warm cache. The window's ordinary lookup
 * then runs exactly as before, the same probe, the same StreamChoice pin and the
 * same settle, and its proof answers from the store (or waits on the pair's
 * lock for a proof still running here) instead of downloading again. A proof
 * that lands too late still leaves the mid-song swap as before.
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
     * The pair already led. Once only: a timed-out search is a failure toward
     * the addon's 30-minute rest (two in a row), and an empty or refused answer
     * is in the search memo and the verdict store, so asking again buys nothing.
     */
    private var pair: Pair<String, String>? = null
    private var job: Job? = null

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
    internal fun eligible(nextId: String, streamedId: String?): Boolean =
        streamedId == nextId &&
            BatteryPolicy.cachePrefetchBytesAllowed(
                metered = AppSettings.meteredConnection.value == true,
                lowPower = AppSettings.lowPower.value,
            ) &&
            nextId !in Downloads.saved.value &&
            !OriginalVersion.isPinned(nextId) &&
            !StreamChoice.isSubstitute(nextId) &&
            !StreamChoice.substitutesRefused(nextId) &&
            nextId !in QualityUpgrade.refused &&
            SourceResolver.directAddons().any { !SourceResolver.SourceCooldown.isResting(it.configId) }

    /** One playing tick, gate active, before the window: [nextId] streams [streamedId]. */
    fun onTick(
        scope: CoroutineScope,
        currentId: String,
        nextId: String,
        streamedId: String?,
        remainingMs: Long,
        target: () -> TrackMatcher.Target,
    ) {
        if (!inLead(remainingMs) || !eligible(nextId, streamedId)) return
        if (!due(currentId, nextId, remainingMs)) return
        val wanted = target()
        if (wanted.title.isBlank() || wanted.isVideo) return
        TrackLog.d(TAG, "direct-lead: looking up $nextId ${remainingMs}ms before the transition", about = nextId)
        synchronized(this) {
            job = scope.launch(Dispatchers.IO + TrackLog.about(nextId)) {
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
                    "direct-lead: $nextId ${answer?.let { "proved ${it.format.summary}" } ?: "none"} " +
                        "ms=${(System.nanoTime() - startedAt) / 1_000_000}",
                    about = nextId,
                )
            }
        }
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
            addons = { SourceResolver.directStream(target, mediaId = videoId, verify = verify, lengthOf = lengthOf) },
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
    }

    private const val TAG = "BitChord"
}
