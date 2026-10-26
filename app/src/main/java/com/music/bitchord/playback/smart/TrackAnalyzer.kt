/*
 * Modeled on Orchard's own TrackAnalyzer (https://github.com/SFG5453/Orchard).
 * Phase 1 was the DSP-only pass (native/analyzer/audio_analysis.cpp); Phase 2
 * adds the Beat This! ONNX model (see [BeatTracker]) and Phase 3 the
 * open-unmix vocal mask (see [VocalTracker]), both over the head and tail of
 * the track, which is the only part a transition ever reads.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.playback.smart

import android.content.Context
import android.os.SystemClock
import android.media.MediaDataSource
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import java.util.Locale

/**
 * Produces [TrackAnalysis] for tracks that are about to be mixed, and hands it
 * to [TransitionPlanner].
 *
 * [analysisFor] is called from the crossfade watcher every tick, so it never
 * blocks or computes: it returns what is already known, and an unanalysed
 * track simply reads as no evidence, which the policy ladder answers with a
 * plain fade.
 */
@UnstableApi
class TrackAnalyzer(private val context: Context, private val cache: AudioCache) {

    private val tracker = BeatTracker(
        modelPath = { unpackedAsset(BeatTracker.MODEL_ASSET) },
        inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
    )
    private val vocals = VocalTracker(
        modelPath = { unpackedAsset(VocalTracker.MODEL_ASSET) },
        inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
    )

    /**
     * A model's weights on disk, unpacked from assets the first time they are
     * asked for.
     *
     * ONNX Runtime wants a file it can memory-map, and an asset inside the APK
     * is not one. This used to live inside each tracker; it moved out when they
     * became shared with the desktop build, which unpacks from the jar instead.
     */
    private fun unpackedAsset(name: String): String {
        val file = java.io.File(context.filesDir, name)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(name).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

    /**
     * Whether analysis is switched off entirely right now.
     *
     * A party never mixes — [com.music.bitchord.playback.CrossfadeController]
     * refuses to plan a transition while one is running, because every member
     * would start the next track at a moment their own copy of the audio
     * decided on. Measuring for a transition that cannot happen is then pure
     * cost, and not a small one: a whole-track decode, two ONNX passes, and for
     * an uncached track a megabyte fetched over the same connection the party's
     * own sync is sharing.
     *
     * Read fresh at every entry point and between the stages of a pass in
     * flight, rather than latched when the party starts, so joining one stops
     * the work already running instead of only the work not yet queued.
     */
    private val stopped: Boolean get() = ListenTogether.state.value.inParty

    /**
     * Resolved on first use, not at construction, for the reason [AnalysisStore]
     * documents about its own directory: this class is a field initializer on
     * the playback service, which runs before the service has a base context
     * attached, and asking a context for anything there returns null.
     */
    private val resolver by lazy { context.contentResolver }

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    /** The opening-window result, kept separately so it survives a later tail pass. */
    private val headResults = ConcurrentHashMap<String, TrackAnalysis>()
    /** The closing-window result, merged with [headResults] for the planner. */
    private val tailResults = ConcurrentHashMap<String, TrackAnalysis>()
    /** Windows already measured, so a cached side is never decoded twice. */
    private val measuredWindows = ConcurrentHashMap<String, MutableSet<AnalysisWindow>>()
    /** Failed attempts per window, bounding a track that cannot be decoded. */
    private val regionFailures = ConcurrentHashMap<String, Int>()
    /** Cumulative decode time per track, for the per-transition analysisMs log. */
    private val analysisMillis = ConcurrentHashMap<String, Long>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    /**
     * Tracks with a reconsideration already queued on [executor].
     *
     * The crossfade ticker asks four times a second, but what the answer
     * depends on — the cache index, the bytes on disk, a rendition's header —
     * changes on the scale of downloads rather than ticks. One queued question
     * per track is enough, and it is what keeps the cache walk off the
     * playback thread.
     */
    private val requestPending = ConcurrentHashMap.newKeySet<String>()
    private val sourceAnalysisUris = ConcurrentHashMap<String, Uri>()
    private val sourceResolutions = ConcurrentHashMap.newKeySet<String>()
    private val sourceResolutionAttempted = ConcurrentHashMap.newKeySet<String>()
    private val sourceResolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tracks whose result came from [analyzeHead] and is waiting to be superseded. */
    private val provisional = ConcurrentHashMap.newKeySet<String>()

    /**
     * Cached prefix size, in bytes, at the last head attempt on each
     * *rendition*. See [headWorthTrying].
     *
     * Keyed by rendition rather than by track because the growth guard is
     * asking "have I already decoded roughly this much of this copy", and a
     * track can move between copies: a first attempt on a half-cached lossless
     * rendition recorded six megabytes, and a much lighter Opus head arriving
     * afterwards — the one that would actually have produced a result — was then
     * refused for being smaller than a number belonging to a different file.
     */
    private val headAttempts = ConcurrentHashMap<String, Long>()

    /**
     * Track-and-rendition pairs that have already reported waiting for a head,
     * so the tick doesn't spam. Not keyed by track alone: a track that gains a
     * second copy of itself is in a genuinely new situation, and the first
     * version of this hid exactly that.
     */
    private val headSkipLogged = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many times each track's whole-track pass has been refused for
     * decoding short. Counted rather than flagged so a rendition still filling
     * in its holes gets a few more chances, while one that is genuinely
     * truncated stops being re-decoded on every tick.
     */
    private val shortDecodes = ConcurrentHashMap<String, Int>()

    /** Tracks already looked for on disk this session; see [restoreOnce]. */
    private val restoreAttempted = ConcurrentHashMap.newKeySet<String>()

    /** Results that survive the process, so a track is measured once and stays measured. */
    private val store = AnalysisStore { context.filesDir }

    /**
     * Cache keys of renditions that decoded short despite the cache calling them
     * complete. Keyed by rendition rather than by track, because the point is to
     * send the next attempt at the *same* track to a different copy of it.
     */
    private val badRenditions = ConcurrentHashMap.newKeySet<String>()

    /**
     * Cache keys already put through the whole-track pass, whatever came of it.
     *
     * What reopens a track that was written off: a copy of it nobody has read
     * yet. Without this the write-off is final for the session, and a rendition
     * that arrives seconds later — a quality upgrade, or the head fetch the
     * analyzer itself asked for — is never looked at. Measured, a track was
     * given up on at 22:09:24 and its `#hifi` copy finished downloading at
     * 22:09:41.
     */
    private val triedRenditions = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many times each rendition has been thrown off disk for being
     * undecodable, so a clean slate stays a one-off.
     *
     * The refetch that follows a discard is not guaranteed to be any better —
     * a source can serve the same broken file twice — and without a bound the
     * two halves feed each other: refuse, delete, refetch, refuse, delete, on a
     * 250ms tick, spending the listener's data in a loop. One clean-slate retry
     * is enough for the case this exists for, which is an entry spliced from two
     * different encodings and unrecoverable only because nothing would ever
     * overwrite it.
     */
    private val discarded = ConcurrentHashMap<String, Int>()

    private fun discardsOf(key: String): Int = discarded[key] ?: 0

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bitchord-smart-analysis").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * What is known about [trackId] right now: never a computation, never a
     * block. Returns an empty analysis for anything not yet finished, which
     * [assessTransitionTier] reads as no evidence rather than as a failure.
     */
    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    /**
     * Looks [trackId] up on disk, once, off the playback thread.
     *
     * Deliberately not folded into [analysisFor], which is called several times
     * per tick from the playback thread and must never touch the filesystem.
     * The result lands in [results] a tick or two later, which is immaterial
     * against the seconds a real analysis takes — and against the alternative,
     * which is not having it at all.
     */
    private fun restoreOnce(trackId: String) {
        if (results.containsKey(trackId)) return
        // The set doubles as the once-guard: add() is true only for the first
        // caller, so a track with nothing stored is looked for once per session
        // rather than on every tick.
        if (!restoreAttempted.add(trackId)) return
        executor.execute {
            val stored = store.load(trackId) ?: return@execute
            Log.d(
                TAG,
                "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence} " +
                    "key=${stored.key} keyConf=${"%.2f".format(Locale.ROOT, stored.keyConfidence)}",
            )
            if (results.putIfAbsent(trackId, stored) == null) {
                // A stored result came from the old whole-track pass, so it
                // already covers both ends of the transition.
                headResults[trackId] = stored
                tailResults[trackId] = stored
                windowsOf(trackId).addAll(listOf(AnalysisWindow.HEAD, AnalysisWindow.TAIL))
            }
        }
    }

    /** True once [trackId] has a result, including a failure. Nothing more will arrive. */
    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    /**
     * True while a decode and inference for [trackId] is actually in flight.
     *
     * Distinct from "not analysed": a track waiting on bytes and a track being
     * worked on right now are the same absence of a result, and the difference
     * is the difference between something being wrong and something simply
     * taking the several seconds it takes.
     */
    fun isAnalysing(trackId: String): Boolean = trackId in running

    /**
     * Queues [trackId] (playing at [uri]) for analysis if it is not already
     * done or in flight. Cheap to call repeatedly; callers re-request as
     * caching progresses.
     *
     * Runs in up to two passes, because waiting for a full cache is what kept
     * the *incoming* track of every transition unanalysed. A track only
     * finishes downloading once it is already playing, so the whole-track pass
     * lands in time to describe a track's own mix-out and never in time to
     * describe its entry — which is the half the listener hears at the moment
     * of the blend.
     *
     * So a track with enough of a head on disk gets [analyzeHead] first: beat
     * grid only, over the opening window, which is all the incoming side is
     * read for. That result is provisional and is replaced by the whole-track
     * [analyze] as soon as the remaining bytes arrive.
     *
     * None of that applies to a track playing off the device, which goes
     * straight to [analyze] on the first tick that reaches it — there is nothing
     * to wait for and nothing to escalate through. See [LocalAudioSource] for
     * why that needed saying at all.
     */
    fun request(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        window: AnalysisWindow = AnalysisWindow.HEAD,
    ) {
        if (trackId.isBlank()) return
        // Nothing shorter than [MIN_ANALYSIS_SECONDS] is mixed, and even one
        // region costs a decode plus two model runs.
        if (!trackLongEnoughToAnalyse(durationSeconds)) return
        if (stopped) return
        if (trackId in running) return
        // Whatever an earlier session already measured comes back first: it is
        // a disk read, not a decode.
        restoreOnce(trackId)
        if (hasWindow(trackId, window)) return
        // Everything below reads the cache index and, through it, the
        // filesystem. One queued reconsideration per track and window is
        // enough, and the single-threaded executor keeps them in order with the
        // analysis they may start.
        val pendingKey = "$trackId@${window.name}"
        if (!requestPending.add(pendingKey)) return
        executor.execute {
            try {
                requestWindowNow(trackId, uri, durationSeconds, window)
            } catch (error: Throwable) {
                Log.w(TAG, "Analysis request for $trackId ($window) failed", error)
            } finally {
                requestPending.remove(pendingKey)
            }
        }
    }

    /** True once [trackId] has a usable result for [window]. */
    fun hasWindow(trackId: String, window: AnalysisWindow): Boolean =
        measuredWindows[trackId]?.contains(window) == true

    /** Milliseconds spent decoding and measuring [trackId] so far. */
    fun analysisTimeMs(trackId: String): Long = analysisMillis[trackId] ?: 0L

    /** [request]'s body, on the analysis thread rather than the playback thread. */
    private fun requestWindowNow(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        window: AnalysisWindow,
    ) {
        if (stopped || trackId in running || hasWindow(trackId, window)) return
        val analysisUri = analysisUriFor(trackId, uri) ?: return

        // A track playing off the device is not a download in progress. Every
        // byte of it is already there, and none of those bytes are in the cache.
        val local = LocalAudioSource.isLocal(analysisUri)
        val complete = if (local) {
            emptyList()
        } else {
            cache.renditionsOf(analysisUri)
                .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(analysisUri.getQueryParameter("v"), it.key) }
                .filter { it.isComplete && it.key !in badRenditions }
        }
        val usableComplete = local || complete.isNotEmpty()

        when (window) {
            AnalysisWindow.HEAD -> {
                if (usableComplete) {
                    val copy = copyToRead(trackId, analysisUri, durationSeconds) ?: return
                    runWindow(trackId, analysisUri, durationSeconds, window, copy)
                } else {
                    // A partial container can still yield the opening. This is
                    // the path that gets the incoming side measured before its
                    // download finishes.
                    val headRendition = headWorthTrying(trackId, analysisUri, durationSeconds)
                    if (headRendition == null) {
                        cache.requestAnalysisHead(analysisUri)
                        return
                    }
                    runWindow(
                        trackId,
                        analysisUri,
                        durationSeconds,
                        window,
                        copy = null,
                        headRendition = headRendition,
                    )
                }
            }
            AnalysisWindow.TAIL -> {
                // The tail cannot be read from a partial file. Keep the
                // download moving and try again when the copy is complete.
                if (!usableComplete) {
                    cache.requestAnalysisHead(analysisUri)
                    return
                }
                val copy = copyToRead(trackId, analysisUri, durationSeconds) ?: return
                runWindow(trackId, analysisUri, durationSeconds, window, copy)
            }
        }
    }

    /**
     * Runs one window pass and publishes it. [copy] is a complete/local source;
     * [headRendition] is the partial-cache alternative used only for an opening.
     */
    private fun runWindow(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        window: AnalysisWindow,
        copy: Copy?,
        headRendition: AudioCache.Rendition? = null,
    ) {
        if (!running.add(trackId)) return
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // Efficient mode yields to decoding and playback rather than
            // competing for a core. Thread count remains the speed knob.
            Process.setThreadPriority(
                if (AppSettings.automixPerformanceMode.value == AutomixPerformanceMode.EFFICIENT) {
                    Process.THREAD_PRIORITY_BACKGROUND
                } else {
                    Process.THREAD_PRIORITY_DEFAULT
                },
            )
            if (stopped || hasWindow(trackId, window)) return
            val analysis = if (headRendition != null) {
                analyzeHead(trackId, uri, durationSeconds, headRendition)
            } else {
                val usableCopy = copy ?: return
                analyzeWindow(trackId, uri, durationSeconds, window, usableCopy)
            }
            if (analysis != null) {
                publishWindow(trackId, window, analysis, durationSeconds)
            } else if (!stopped) {
                recordRegionFailure(trackId, window, durationSeconds)
            }
        } catch (error: Throwable) {
            // Throwable, not Exception: decode leans on MediaCodec, and an OOM
            // or codec-level Error uncaught on a pool thread that is nobody's
            // parent takes the whole app down.
            Log.w(TAG, "Analysis of $trackId ($window) failed", error)
            if (!stopped) recordRegionFailure(trackId, window, durationSeconds)
        } finally {
            analysisMillis.merge(trackId, SystemClock.elapsedRealtime() - startedAt, Long::plus)
            running.remove(trackId)
            // The session holds the model's arena and a parsed ONNX graph in
            // native heap for as long as it is open, which a backgrounded music
            // player cannot justify between transitions.
            if (running.isEmpty()) {
                tracker.release()
                vocals.release()
            }
        }
    }

    private fun publishWindow(
        trackId: String,
        window: AnalysisWindow,
        analysis: TrackAnalysis,
        durationSeconds: Double,
    ) {
        when (window) {
            AnalysisWindow.HEAD -> headResults[trackId] = analysis
            AnalysisWindow.TAIL -> tailResults[trackId] = analysis
        }
        windowsOf(trackId).add(window)
        regionFailures.remove("$trackId@${window.name}")
        shortDecodes.remove(trackId)
        provisional.remove(trackId)
        val merged = mergeWindows(trackId, durationSeconds)
        results[trackId] = merged
        // Only a track measured at both ends is persisted. A head-only result
        // is missing the outgoing half, and a tail-only one the incoming half;
        // storing either would freeze a deliberately partial answer in place.
        if (hasWindow(trackId, AnalysisWindow.HEAD) && hasWindow(trackId, AnalysisWindow.TAIL)) {
            store.save(trackId, merged)
            restoreAttempted.add(trackId)
        }
        Log.d(
            TAG,
            "Analysed ${window.name.lowercase(Locale.ROOT)} of $trackId: " +
                "bpm=${analysis.bpm} conf=${analysis.beatConfidence} " +
                "key=${analysis.key} keyConf=${"%.2f".format(Locale.ROOT, analysis.keyConfidence)} " +
                "contentEnd=${analysis.contentEndTime} " +
                "mixIn=${analysis.mixInCandidates.size} mixOut=${analysis.mixOutCandidates.size}",
        )
    }

    private fun recordRegionFailure(trackId: String, window: AnalysisWindow, durationSeconds: Double) {
        val key = "$trackId@${window.name}"
        val attempts = regionFailures.merge(key, 1, Int::plus) ?: 1
        if (attempts < MAX_REGION_ATTEMPTS) return
        // Bounded, so a container that is genuinely truncated is not decoded
        // on every tick for the rest of the session.
        Log.w(TAG, "Giving up on $trackId ($window) after $MAX_REGION_ATTEMPTS attempts")
        windowsOf(trackId).add(window)
        if (results[trackId] == null) results[trackId] = empty(trackId, durationSeconds)
    }

    private fun windowsOf(trackId: String): MutableSet<AnalysisWindow> =
        measuredWindows.computeIfAbsent(trackId) { ConcurrentHashMap.newKeySet() }

    /**
     * The single result the planner reads: the opening fields from [headResults]
     * and the outgoing fields from [tailResults].
     */
    private fun mergeWindows(trackId: String, durationSeconds: Double): TrackAnalysis {
        val head = headResults[trackId]
        val tail = tailResults[trackId]
        if (head == null && tail == null) {
            return results[trackId] ?: empty(trackId, durationSeconds)
        }
        if (head == null) return tail!!
        if (tail == null) return head
        // Both ends can report a key; keep the more confident reading rather
        // than letting the tail's lower-confidence one win by default.
        val keySource = when {
            tail.key.isBlank() -> head
            head.key.isBlank() -> tail
            tail.keyConfidence >= head.keyConfidence -> tail
            else -> head
        }
        return tail.copy(
            trackId = trackId,
            duration = maxOf(head.duration, tail.duration, durationSeconds),
            bpm = tail.bpm.takeIf { it > 0 } ?: head.bpm,
            beatInterval = tail.beatInterval.takeIf { it > 0 } ?: head.beatInterval,
            beatConfidence = maxOf(tail.beatConfidence, head.beatConfidence),
            downbeats = tail.downbeats.ifEmpty { head.downbeats },
            phraseBoundaries = tail.phraseBoundaries.ifEmpty { head.phraseBoundaries },
            firstBeat = tail.firstBeat.takeIf { it != 0.0 } ?: head.firstBeat,
            key = keySource.key,
            keyConfidence = keySource.keyConfidence,
            loudnessLufs = tail.loudnessLufs.takeIf { it != 0.0 } ?: head.loudnessLufs,
            audibleStartTime = head.audibleStartTime ?: tail.audibleStartTime,
            pickupTime = head.pickupTime ?: tail.pickupTime,
            introEndTime = head.introEndTime.takeIf { it > 0 } ?: tail.introEndTime,
            mixInTime = head.mixInTime.takeIf { it > 0 } ?: tail.mixInTime,
            mixInCandidates = head.mixInCandidates.ifEmpty { tail.mixInCandidates },
            energyCurve = tail.energyCurve.ifEmpty { head.energyCurve },
            lowEnergyCurve = tail.lowEnergyCurve.ifEmpty { head.lowEnergyCurve },
            vocalActivityMask = tail.vocalActivityMask.ifEmpty { head.vocalActivityMask },
            vocalProbability = tail.vocalProbability.takeIf { it > 0.0 } ?: head.vocalProbability,
        )
    }

    /**
     * Whether enough of [uri]'s head is on disk to be worth a decode, claiming
     * the attempt if so.
     *
     * The byte threshold is derived from the rendition's own average bitrate
     * where the duration is known, because "30 seconds of audio" is a wildly
     * different number of bytes at 96 kbps and at lossless.
     * [HEAD_BYTES_MARGIN] covers the container header and the fact that a
     * track's opening is rarely at its own average bitrate.
     *
     * Where the duration isn't known — which is the common case, since callers
     * request analysis before anything has read the container — the estimate is
     * unavailable and [MIN_HEAD_BYTES] stands in. That is about 30 s of a
     * typical stream but only a few seconds of lossless, so a single attempt
     * gated on it would be spent on too little audio for exactly the tracks
     * that carry the most bytes per second.
     *
     * Hence retrying on growth rather than attempting once: an attempt is
     * allowed again only when the cached prefix has [HEAD_RETRY_GROWTH]-fold
     * grown since the last one. A track therefore gets a handful of tries
     * spread across its download instead of either one try or one per tick.
     */
    private fun headWorthTrying(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        // Across every rendition of the recording, not just the one the player
        // happens to be on. The same track can be part-downloaded under a
        // sibling cache key, and the live URI's own copy is frequently the one
        // holding nothing — a track that reported six megabytes cached twenty
        // minutes earlier reported zero here, because the question was being
        // asked of the wrong copy of it.
        val candidate = cache.renditionsOf(uri)
            .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(uri.getQueryParameter("v"), it.key) }
            .filter { it.cachedPrefix > 0L && it.key !in badRenditions }
            // The growth guard, applied as a filter rather than to the winner.
            // Applied afterwards it did not skip a copy, it ended the search: the
            // single best candidate was chosen, refused for not having grown, and
            // the second-best — frequently the one that would have worked — was
            // never reached. A track therefore got exactly one head attempt ever,
            // against whichever copy of it happened to rank highest at the time.
            .filter { rendition ->
                val previous = headAttempts[rendition.key] ?: return@filter true
                rendition.cachedPrefix >= previous * HEAD_RETRY_GROWTH
            }
            // Most *audio*, not most bytes: renditions differ in bitrate, so the
            // largest prefix is not necessarily the longest playable head.
            .maxByOrNull { headSecondsOf(it, durationSeconds) }
            ?: return null

        val prefix = candidate.cachedPrefix
        val total = candidate.contentLength
        val needed = if (durationSeconds.isFinite() && durationSeconds > MIN_HEAD_SECONDS && total > 0) {
            val bytesPerSecond = total / durationSeconds
            // Sized to [MIN_HEAD_SECONDS] — the shortest decode [analyzeHead]
            // will accept — not to the model's full window. Gating on the full
            // window meant demanding two and a half times the input the analysis
            // would actually settle for: a lossless rendition needs nine
            // megabytes on disk for thirty seconds of audio, and a track sitting
            // at six was refused outright despite holding twice what was needed
            // to produce a result. Whatever *is* cached still gets decoded — the
            // read simply runs out — so a larger prefix is used when there is
            // one, and [HEAD_RETRY_GROWTH] comes back for a better look as the
            // rest arrives.
            (MIN_HEAD_SECONDS * bytesPerSecond * HEAD_BYTES_MARGIN).toLong()
                .coerceAtLeast(MIN_HEAD_BYTES)
                .coerceAtMost(total)
        } else {
            MIN_HEAD_BYTES
        }
        if (prefix < needed) {
            // Once per track, not per tick: a head pass that never fires is
            // invisible otherwise, which is exactly how the first version of
            // this shipped doing nothing at all.
            if (headSkipLogged.add("$trackId@${candidate.key}")) {
                Log.d(
                    TAG,
                    "Head pass for $trackId waiting: ${prefix / 1024}kB cached of " +
                        "${needed / 1024}kB needed (rendition ${candidate.key})",
                )
            }
            return null
        }

        headAttempts[candidate.key] = prefix
        return candidate
    }

    /**
     * Roughly how many seconds of audio a rendition's cached prefix holds.
     *
     * The ranking this feeds used to be `cachedPrefix / contentLength`, which
     * answers zero whenever the length is unknown — and the length is unknown
     * for precisely the entry that matters most, the head
     * [AudioCache.requestAnalysisHead] just fetched, because a bounded request
     * gets a bounded answer. A megabyte of freshly downloaded opening therefore
     * scored below a sibling holding eight unusable kilobytes, and the analyzer
     * spent its one attempt on the wrong copy.
     *
     * [ASSUMED_BYTES_PER_SECOND] stands in where the length still isn't known.
     * It only has to be the right order of magnitude: this decides which copy to
     * read first, not whether the result is trusted.
     */
    private fun headSecondsOf(rendition: AudioCache.Rendition, durationSeconds: Double): Double =
        if (rendition.contentLength > 0 && durationSeconds.isFinite() && durationSeconds > 0) {
            rendition.cachedPrefix * durationSeconds / rendition.contentLength
        } else {
            rendition.cachedPrefix / ASSUMED_BYTES_PER_SECOND
        }

    /**
     * The opening window only: a beat grid, and nothing that would need the rest
     * of the file.
     *
     * Runs [TrackFeatures] over the head, but copies across only the fields
     * that describe an *entry*: where the file starts making sound, the pickup,
     * the end of the intro, and the mix-in candidates. Those are all measured
     * within the opening seconds, so a head-only pass measures them exactly as
     * a whole-track pass would.
     *
     * Everything that describes the rest of the track is dropped on the floor —
     * content end, outro, mix-out anchors, the energy curve. Over a 30 s head
     * that pass does not fail, it answers confidently about a track that is
     * mostly missing, and the planner has no way to tell the difference. Left at
     * their defaults they read as "no evidence": [contentEndTime] falls back to
     * the real duration and the mix-out list ranks as empty.
     *
     * The energy curve is dropped for the same reason even though it is
     * genuinely measured here: the policy indexes the vocal mask against it and
     * counts audible seconds from it, and a curve that stops at 30 s would have
     * this track's *outgoing* half scored against a window it does not cover.
     * A vocal mask therefore cannot come from this pass either, and waits for
     * the whole-track one.
     */
    /**
     * Measures one end of a track: the opening or the closing
     * [AutomixWorkPolicy.TRANSITION_WINDOW_SECONDS] seconds.
     *
     * The middle is never decoded. The DSP pass runs over just this window and
     * its time fields are shifted into the track's own timeline, so the planner
     * reads the same coordinates it always did. Only the fields that describe
     * this end are filled; [mergeWindows] combines the two passes.
     */
    private fun analyzeWindow(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        window: AnalysisWindow,
        copy: Copy,
    ): TrackAnalysis? {
        val openSource = copy.open
        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId ($window): ${copy.key} has no readable duration")
            return null
        }
        val span = AutomixWorkPolicy.TRANSITION_WINDOW_SECONDS
        val start = if (window == AnalysisWindow.TAIL) max(0.0, effectiveDuration - span) else 0.0
        val end = if (window == AnalysisWindow.TAIL) effectiveDuration else minOf(span, effectiveDuration)
        if (end <= start) return null

        // One stereo decode per window: [region] derives the DSP features from
        // the same audio the models read, and the offset puts every time back on
        // the track's own timeline (including the vocal mask's indexing).
        val model = region(
            openSource,
            start,
            end,
            features = null,
            deriveFeatures = true,
            featureOffsetSeconds = start,
        ) ?: return null
        if (stopped) return null
        if (model.seconds < minOf(MIN_HEAD_SECONDS, end - start)) {
            Log.d(
                TAG,
                "Window $window of $trackId decoded only " +
                    "${"%.1f".format(Locale.ROOT, model.seconds)}s; too short",
            )
            return null
        }
        val shifted = model.features ?: return null
        val grid = model.grid
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = effectiveDuration,
            bpm = grid?.bpm ?: shifted.bpm,
            beatInterval = grid?.beatInterval ?: shifted.beatInterval,
            beatConfidence = grid?.beatConfidence ?: shifted.beatConfidence,
            downbeats = grid?.downbeats ?: shifted.downbeats,
            phraseBoundaries = shifted.phraseBoundaries,
            firstBeat = grid?.firstBeat ?: shifted.firstBeat,
            key = shifted.key,
            keyConfidence = shifted.keyConfidence,
            loudnessLufs = shifted.loudnessLufs,
            audibleStartTime = if (window == AnalysisWindow.HEAD) shifted.audibleStartTime else null,
            pickupTime = if (window == AnalysisWindow.HEAD) shifted.pickupTime else null,
            introEndTime = if (window == AnalysisWindow.HEAD) shifted.introEndTime else 0.0,
            contentEndTime = if (window == AnalysisWindow.TAIL) {
                shifted.contentEndTime.takeIf { it > 0.0 } ?: effectiveDuration
            } else {
                effectiveDuration
            },
            outroStartTime = if (window == AnalysisWindow.TAIL) shifted.outroStartTime else 0.0,
            mixInTime = if (window == AnalysisWindow.HEAD) shifted.mixInTime else 0.0,
            mixOutTime = if (window == AnalysisWindow.TAIL) shifted.mixOutTime else 0.0,
            mixInCandidates = if (window == AnalysisWindow.HEAD) shifted.mixInCandidates else emptyList(),
            mixOutCandidates = if (window == AnalysisWindow.TAIL) shifted.mixOutCandidates else emptyList(),
            energyCurve = shifted.energyCurve,
            lowEnergyCurve = shifted.lowEnergyCurve,
            vocalActivityMask = model?.vocalMask?.toList().orEmpty(),
            vocalProbability = shifted.vocalProbability,
        )
    }

    /** Moves every time in [TrackFeatures.Features] by [offset] seconds. */
    private fun TrackFeatures.Features.shiftedBy(offset: Double): TrackFeatures.Features {
        if (offset == 0.0) return this
        fun List<Double>.shifted() = map { it + offset }
        fun List<EnergySample>.shifted() = map { it.copy(time = it.time + offset) }
        fun List<MixCandidate>.shifted() = map { it.copy(time = it.time + offset) }
        return copy(
            audibleStartTime = audibleStartTime + offset,
            pickupTime = pickupTime + offset,
            introEndTime = introEndTime + offset,
            outroStartTime = outroStartTime + offset,
            contentEndTime = contentEndTime + offset,
            mixInTime = mixInTime + offset,
            mixOutTime = mixOutTime + offset,
            downbeats = downbeats.shifted(),
            phraseBoundaries = phraseBoundaries.shifted(),
            energyCurve = energyCurve.shifted(),
            lowEnergyCurve = lowEnergyCurve.shifted(),
            mixInCandidates = mixInCandidates.shifted(),
            mixOutCandidates = mixOutCandidates.shifted(),
        )
    }

    private fun analyzeHead(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        rendition: AudioCache.Rendition,
    ): TrackAnalysis? {
        fun openSource(): MediaDataSource? = cache.renditionDataSource(uri, rendition)

        // Same guard the whole-track pass applies, for the same reason: a
        // sibling rendition can be a different cut, and a beat grid borrowed
        // across that would put every anchor seconds out. Skipped for the
        // player's own copy, which is the track by definition. A header that
        // will not parse yet is not held against the rendition — more bytes may
        // well fix it — but a length that genuinely disagrees is.
        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        if (expected != null && rendition.key != cache.cacheKeyOf(uri)) {
            val length = openSource()?.use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                // Logged rather than returned quietly. This is the likeliest way
                // for a head pass to do nothing — a partial container the
                // extractor will not read a duration out of — and while it was
                // silent the whole path looked like it had never run.
                Log.d(TAG, "Head rendition ${rendition.key} for $trackId has no readable duration yet")
                return null
            }
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Head rendition ${rendition.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against ${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                badRenditions.add(rendition.key)
                return null
            }
        }

        val window = minOf(AutomixWorkPolicy.TRANSITION_WINDOW_SECONDS, durationSeconds)
        val head = region(::openSource, 0.0, window, features = null, deriveFeatures = true)
            ?: run {
                // An abandoned decode comes back the same way a broken one
                // does, and only one of the two is worth a warning: see
                // [stopped]. Nothing is recorded either way — the caller
                // publishes only a non-null result — so the track is simply
                // left unmeasured, which is what it is.
                if (stopped) return null
                Log.d(TAG, "Head pass for $trackId could not decode rendition ${rendition.key}")
                return null
            }
        // What was decoded, not what was asked for: the source stops where the
        // cache does. A tempo read off a few seconds is not a weaker measurement
        // than one read off thirty, it is a different and much more credulous
        // one, and the planner cannot see the difference — so it is refused here
        // and the next attempt gets more of the file.
        if (head.seconds < MIN_HEAD_SECONDS) {
            Log.d(TAG, "Head pass for $trackId decoded only ${"%.1f".format(Locale.ROOT, head.seconds)}s; too short")
            return null
        }
        val grid = head.grid
        val entry = head.features
        if (grid == null && entry == null) {
            Log.d(TAG, "Head pass for $trackId produced nothing usable")
            return null
        }

        Log.d(
            TAG,
            "Analysed head of $trackId: bpm=${grid?.bpm ?: entry?.bpm} " +
                "conf=${grid?.beatConfidence ?: entry?.beatConfidence} " +
                "key=${entry?.key} keyConf=${"%.2f".format(Locale.ROOT, entry?.keyConfidence ?: 0.0)} " +
                "audibleStart=${entry?.audibleStartTime} pickup=${entry?.pickupTime} " +
                "introEnd=${entry?.introEndTime} mixInCandidates=${entry?.mixInCandidates?.size ?: 0} " +
                "over ${"%.1f".format(Locale.ROOT, head.seconds)}s",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = durationSeconds,
            bpm = grid?.bpm ?: entry?.bpm ?: 0.0,
            beatInterval = grid?.beatInterval ?: entry?.beatInterval ?: 0.0,
            beatConfidence = grid?.beatConfidence ?: entry?.beatConfidence ?: 0.0,
            downbeats = grid?.downbeats ?: entry?.downbeats.orEmpty(),
            firstBeat = grid?.firstBeat ?: entry?.firstBeat ?: 0.0,
            key = entry?.key.orEmpty(),
            keyConfidence = entry?.keyConfidence ?: 0.0,
            audibleStartTime = entry?.audibleStartTime,
            pickupTime = entry?.pickupTime,
            introEndTime = entry?.introEndTime ?: 0.0,
            mixInTime = entry?.mixInTime ?: 0.0,
            mixInCandidates = entry?.mixInCandidates.orEmpty(),
        )
    }

    /**
     * Picks which rendition of [uri]'s recording to analyse: the lightest one
     * that is both complete and the same cut as the track being played.
     *
     * A recording can be on disk two or three times over — the Opus stream
     * YouTube served, a substituted source's copy, a later quality upgrade — and
     * they hold the same music, so an analysis of any of them describes all of
     * them. Analysing the smallest is not merely cheaper: it is the one that
     * finished downloading first, and a lossless upgrade can take most of a
     * track's play time to arrive. Waiting for it is why analysis was landing
     * seconds *after* the transition it was meant to inform.
     *
     * The duration check is what makes the sharing safe. A `#alt` rendition
     * comes from an entirely different source and may be a different cut —
     * a radio edit, a version with a longer intro — and a beat grid borrowed
     * across that difference would put every downbeat and both mix anchors
     * seconds out. Comparing container durations catches exactly that, and
     * costs a header parse per candidate.
     */
    private fun chooseRendition(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        val complete = cache.renditionsOf(uri)
            .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(uri.getQueryParameter("v"), it.key) }
            .filter { it.isComplete && it.key !in badRenditions }
        if (complete.isEmpty()) return null

        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        // Without a length to check a sibling against, sharing would be a guess,
        // so only the rendition actually being played can be trusted.
        if (expected == null) {
            val own = cache.cacheKeyOf(uri)
            complete.firstOrNull { it.key == own }?.let { return it }
            // Nothing to cross-check against — but one copy is not ambiguous
            // either, and refusing it outright is a dead end rather than a
            // safeguard. [cacheKeyOf] answers with whichever rendition the key
            // factory resolves to *now*, which with substitution on is the `#alt`
            // entry; the copy actually on disk is routinely the plain one, so
            // this asked for a rendition that did not exist and returned null on
            // every tick, silently, for as long as the track stayed queued.
            //
            // The risk the duration check exists to catch is borrowing a beat
            // grid across two different cuts of a song. That needs two copies to
            // choose wrongly between. With exactly one there is no choice being
            // made, and the worst case degrades from "never analysed" to "a grid
            // measured off the only audio we have".
            return complete.singleOrNull()?.also {
                Log.d(
                    TAG,
                    "Analysing $trackId from its only cached rendition ${it.key}; " +
                        "no duration to check it against",
                )
            }
        }

        for (candidate in complete) {
            val length = cache.renditionDataSource(uri, candidate)
                .use(AudioDecoder::containerDurationSeconds) ?: continue
            if (length <= 0) continue
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Rendition ${candidate.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against ${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                continue
            }
            if (candidate.key != cache.cacheKeyOf(uri)) {
                Log.d(
                    TAG,
                    "Analysing $trackId from lighter rendition ${candidate.key} " +
                        "(${candidate.contentLength / 1024}kB)",
                )
            }
            return candidate
        }
        return null
    }

    /**
     * Where a whole-track pass reads its audio from, and the identity the retry
     * bookkeeping keys off.
     *
     * Two kinds of copy exist and they are not interchangeable. A **cached
     * rendition** is one of several copies of a streamed recording: chosen
     * between by [chooseRendition], cross-checked for being the same cut, and —
     * when it turns out undecodable — thrown off disk so a clean one can replace
     * it. A **local file** is the track itself: exactly one of it, complete from
     * the moment it exists, nothing to choose between, and not ours to delete.
     *
     * [rendition] is what tells the two apart. Null means the audio is a file on
     * the device, which is why the blame-and-discard half of [structure] is
     * conditional on it rather than on a flag.
     */
    private class Copy(
        val key: String,
        val rendition: AudioCache.Rendition?,
        val open: () -> MediaDataSource?,
    )

    /**
     * Which copy of [trackId]'s audio to read, or null when there isn't a usable
     * one yet.
     *
     * A local URI short-circuits the entire rendition search: none of what that
     * search decides between applies to a file the user already has, and asking
     * the cache about one only ever produced the empty answer that kept Automix
     * from analysing local tracks at all. The URI stands in as the cache key,
     * which is what [request]'s `untried` check reads so a file that will not
     * decode is not re-decoded on every tick for the rest of the session.
     */
    private fun copyToRead(trackId: String, uri: Uri, durationSeconds: Double): Copy? {
        if (LocalAudioSource.isLocal(uri)) {
            return Copy(key = uri.toString(), rendition = null) { LocalAudioSource.open(resolver, uri) }
        }
        val rendition = chooseRendition(trackId, uri, durationSeconds) ?: return null
        return Copy(key = rendition.key, rendition = rendition) { cache.renditionDataSource(uri, rendition) }
    }

    /**
     * Source-backed playback URIs (JioSaavn, module/lossless, etc.) carry the
     * same title/artist/runtime matching hints as a YouTube item. Resolve that
     * identity once, then keep playback on its original URI while analysis
     * fills and reads the separately pinned YouTube Opus entry.
     */
    private fun analysisUriFor(trackId: String, playbackUri: Uri): Uri? {
        if (playbackUri.getQueryParameter("v") != null || playbackUri.authority != "source") return playbackUri
        // Never for a row picked from the Addons search: that would put a
        // YouTube search and its Opus on the network under a track whose whole
        // point is not being YouTube. Left unmeasured, as a party leaves every
        // track — see [SourceResolver.MIX_PARAM].
        if (SourceResolver.isPickedAddonRow(playbackUri)) return null
        sourceAnalysisUris[trackId]?.let { return it }
        if (trackId in sourceResolutionAttempted) return null
        if (!sourceResolutions.add(trackId)) return null
        val target = SourceResolver.targetIn(playbackUri)
        sourceResolutionScope.launch {
            try {
                val match = findYouTubeMatch(target)
                if (match != null) {
                    sourceAnalysisUris[trackId] = Uri.parse(AutomixAnalysisSource.opusUri(match.videoId))
                    Log.d(TAG, "Automix analysis for $trackId will use YouTube Opus ${match.videoId}")
                } else {
                    Log.d(TAG, "No YouTube match for Automix analysis of $trackId")
                }
            } catch (error: Throwable) {
                Log.d(TAG, "YouTube match failed for Automix analysis of $trackId: ${error.message}")
            } finally {
                sourceResolutionAttempted.add(trackId)
                sourceResolutions.remove(trackId)
            }
        }
        return null
    }

    private suspend fun findYouTubeMatch(target: TrackMatcher.Target): com.music.bitchord.data.model.Song? {
        if (target.title.isBlank()) return null
        for (query in TrackMatcher.queries(target)) {
            val candidates = YtMusicRepository.search(query, SearchFilter.SONGS)
                .getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            TrackMatcher.best(candidates, target)?.let { return it }
        }
        return null
    }

    /**
     * What a whole-track pass came back with.
     *
     * [decodedShort] is the difference between "this copy is broken, strike it"
     * and "there was no copy to read", which the caller counts very differently:
     * three strikes writes a track off for the session. Conflating the two spent
     * all three in 922ms on a track whose only complete copy had just been
     * rejected — the following two attempts decoded nothing because there was
     * nothing left to decode, and were counted as though they had tried.
     */
    private class WholeTrack(val analysis: TrackAnalysis?, val decodedShort: Boolean = false)

    /**
     * What Pass 1 came back with.
     *
     * [decodedShort] has to survive the return rather than collapsing into a null [features]: it is
     * the same distinction [WholeTrack.decodedShort] draws, between a broken copy and no copy, and
     * only one of the two is a strike.
     */
    private class Structural(
        val features: TrackFeatures.Features?,
        val decodedShort: Boolean = false,
    )

    /**
     * Decodes the whole track and reduces it to DSP features.
     *
     * A method rather than a block in [analyze] for a reason that is about memory, not tidiness —
     * see the call site. Everything it decodes is dead by the time it returns, and returning is
     * what makes that true of the heap as well as of the program.
     */
    private fun structure(
        trackId: String,
        uri: Uri,
        copy: Copy,
        effectiveDuration: Double,
    ): Structural {
        val structRate = TrackFeatures.sampleRate
        val decoded = copy.open()?.use {
            AudioDecoder.decodeRegion(it, 0.0, effectiveDuration, abort = { stopped }, label = "automix")
        } ?: return Structural(null)
        val (pcm, _) = decoded
        if (stopped) return Structural(null)

        // A decode that stops early is indistinguishable, downstream, from a
        // track that simply goes quiet: [TrackFeatures] is handed the
        // container's full duration alongside a short buffer, reads the
        // difference as trailing silence, and puts the mix-out anchor where the
        // bytes ran out. Nothing about the result looks wrong — it is a complete
        // analysis with a plausible contentEnd — and the audible symptom is the
        // track being faded out minutes early. Refused outright rather than
        // published, because a missing analysis degrades to a plain crossfade
        // while a confidently wrong one does not degrade at all.
        val decodedSeconds = if (pcm.sampleRate > 0) pcm.samples.size / pcm.sampleRate else 0.0
        if (decodedSeconds < effectiveDuration * MIN_DECODED_FRACTION) {
            Log.w(
                TAG,
                "Analysis of $trackId refused: ${copy.key} decoded " +
                    "${"%.1f".format(Locale.ROOT, decodedSeconds)}s of a " +
                    "${"%.1f".format(Locale.ROOT, effectiveDuration)}s " +
                    // The two causes are different enough to be worth naming. A
                    // cached rendition stops at the first hole read-ahead left in
                    // it; a file on the device has no holes, so a short decode
                    // there means the container itself is truncated or damaged.
                    if (copy.rendition != null) "container — cached with holes?" else "container — truncated file?",
            )
            // Both halves of this are about *renditions*, so both are conditional
            // on there being one. A local file has no sibling copy to be routed to
            // instead and is not ours to delete either way; the three-strike count
            // in [request] is the whole bound there, and it is enough, because a
            // file on disk that decodes short will decode short again.
            copy.rendition?.let { rendition ->
                // Remembered, so the retry reaches for a *different* rendition. This
                // is the whole reason the lightest one is only a preference: a
                // rendition the cache index calls complete can still decode short if
                // it was written badly, and without this the retries would pick the
                // same broken copy three times over and give up on a track whose
                // heavier rendition would have analysed perfectly well.
                badRenditions.add(rendition.key)
                // And thrown off disk, not merely remembered. A file the cache calls
                // complete and the decoder gives up on partway is not going to
                // improve: nothing else will ever write to it, because as far as the
                // cache is concerned it is finished. Remembering it only helps for as
                // long as this process lives — a restart clears the set, the same
                // bytes are read again, and the same seconds are spent reaching the
                // same conclusion. Deleting it is what lets a clean copy be fetched.
                // Skipped for whatever the player is reading from; see
                // [AudioCache.discardBadRendition].
                if (rendition.isComplete && discardsOf(rendition.key) < MAX_RENDITION_DISCARDS &&
                    cache.discardBadRendition(uri, rendition.key)
                ) {
                    // Every memory of that copy goes with the bytes. Deleting the
                    // file and then still refusing its key is the worst of both: the
                    // clean copy [AudioCache.requestAnalysisHead] fetches in its place
                    // is filtered straight back out by [badRenditions], the track is
                    // written off for the session anyway, and the download was spent
                    // on nothing. The strike count goes too — the next attempt reads
                    // genuinely different bytes, so it starts level.
                    discarded.merge(rendition.key, 1, Int::plus)
                    badRenditions.remove(rendition.key)
                    triedRenditions.remove(rendition.key)
                    shortDecodes.remove(trackId)
                }
            }
            return Structural(null, decodedShort = true)
        }

        val samples = if (abs(pcm.sampleRate - structRate) > 1.0) {
            TrackFeatures.resample(pcm.samples, pcm.sampleRate, structRate) ?: return Structural(null)
        } else {
            pcm.samples
        }

        return Structural(TrackFeatures.analyze(samples, effectiveDuration))
    }

    /**
     * The whole-track pass. A null [WholeTrack.analysis] means "not now, try
     * again"; see the short-decode guard below, which is the one condition that
     * produces a confident-looking analysis that is wrong by minutes rather than
     * merely absent, and the only one that counts as a strike.
     */
    private fun analyze(trackId: String, uri: Uri, durationSeconds: Double): WholeTrack {
        val copy = copyToRead(trackId, uri, durationSeconds) ?: return WholeTrack(null)
        // Recorded before the outcome is known, because what this gates is
        // whether a *written-off* track is worth reopening, and the answer is
        // only ever "yes" for a copy that has not been read yet. Recording it on
        // success alone would leave a failure looking permanently reopenable and
        // re-decode the same file on every tick.
        triedRenditions.add(copy.key)
        val openSource = copy.open

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: ${copy.key} has no readable duration")
            return WholeTrack(empty(trackId, 0.0))
        }

        // Pass 1 (Phase 1, DSP-only): the analyzer needs the whole track — the energy curve,
        // phrase structure and mix-out anchor all read the tail, not just a window of it — at its
        // own low sample rate, so this is a much smaller decode than a full-rate pass would be.
        //
        // In a frame of its own, and handing back only the features, because of what it allocates
        // to get them: the whole track decoded to mono at the container's rate (35 MB for a
        // 3.5-minute song) plus the resampled copy the DSP reads (8 MB). Neither is touched again
        // after this line, but a local holding either stays reachable to the end of the method, and
        // the rest of the method is Pass 2 — the most allocation-heavy part of the analysis.
        // Measured on the API 28 emulator: those two buffers were 43 MB of an 82 MB live set, still
        // held while the models ran, in a process that was reaching a 256 MB heap limit and had
        // died on it. Returning is what releases them — a `val` cannot be nulled, and a narrower
        // scope alone does not make ART treat one as dead.
        val structural = structure(trackId, uri, copy, effectiveDuration)
        // A party started while Pass 1 was decoding. Dropped as "no result"
        // rather than as a failure — the track was never measured, so nothing
        // should be recorded against it and nothing should stop it being
        // measured properly once the party ends.
        //
        // Read *before* the two lines below, not after, because an abort comes
        // back from [structure] as a decode that produced nothing, and that is
        // exactly what those lines write a permanent "cannot be analysed" for.
        // The screen going off is the same kind of interruption.
        if (stopped) return WholeTrack(null)
        if (structural.decodedShort) return WholeTrack(null, decodedShort = true)
        val features = structural.features ?: return WholeTrack(empty(trackId, effectiveDuration))

        // Pass 2 (Phases 2 and 3, models): the Beat This! grid and the open-unmix vocal mask, over
        // the head and tail only. A transition only ever reads the tail of the outgoing track and
        // the head of the incoming one, and a track is both of those at different moments, so the
        // middle is never decoded for this. Both models read the same decoded region, so the
        // stereo buffer is paid for once.
        val window = BeatTracker.WINDOW_SECONDS
        val tailStart = max(0.0, effectiveDuration - window)
        val head = region(openSource, 0.0, minOf(window, effectiveDuration), features)
        // Between the two model passes, for the same reason as above. Not
        // folded into the `tail` condition below: a head-only result would be
        // written *and persisted* as if it were a whole-track one, freezing a
        // deliberately partial answer in place of the complete one.
        if (stopped) return WholeTrack(null)
        val tail = if (tailStart > window / 2) region(openSource, tailStart, effectiveDuration, features) else null
        // Last of the three. An abort during the tail pass leaves `tail` null,
        // which is indistinguishable from a window the models found nothing in
        // — and that one gets published, with the DSP estimate standing in. A
        // result assembled out of a half-finished analysis is worse than none.
        if (stopped) return WholeTrack(null)

        val headGrid = head?.grid
        val tailGrid = tail?.grid

        // The tail governs where the outgoing track is mixed out, so it takes precedence; the
        // head is what a track uses when it is the *incoming* side of a different transition.
        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "Analysed $trackId: bpm=${leading?.bpm ?: features.bpm} " +
                "conf=${leading?.beatConfidence ?: features.beatConfidence} " +
                "key=${features.key} contentEnd=${features.contentEndTime} " +
                "mixOutCandidates=${features.mixOutCandidates.size} " +
                "vocalMask=${if (head?.vocalMask != null || tail?.vocalMask != null) "model" else "dsp"}",
        )

        return WholeTrack(
                TrackAnalysis(
                    status = TrackAnalysis.STATUS_READY,
                    trackId = trackId,
                    duration = effectiveDuration,
                    contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
                    bpm = leading?.bpm ?: features.bpm,
                beatInterval = leading?.beatInterval ?: features.beatInterval,
                beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
                downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
                    .ifEmpty { features.downbeats }
                    .sorted(),
                firstBeat = headGrid?.firstBeat ?: features.firstBeat,
                phraseBoundaries = features.phraseBoundaries,
                key = features.key,
                keyConfidence = features.keyConfidence,
                loudnessLufs = features.loudnessLufs,
                audibleStartTime = features.audibleStartTime,
                pickupTime = features.pickupTime,
                introEndTime = features.introEndTime,
                outroStartTime = features.outroStartTime,
                mixInTime = features.mixInTime,
                mixOutTime = features.mixOutTime,
                mixInCandidates = features.mixInCandidates,
                mixOutCandidates = features.mixOutCandidates,
                energyCurve = features.energyCurve,
                lowEnergyCurve = features.lowEnergyCurve,
                // The model's mask where it ran, the DSP heuristic's where it didn't. Falling back to
                // the heuristic rather than to nothing matters because the policy reads an
                // absent mask and a neutral one identically — as "no evidence" — so a failed model
                // pass would otherwise silently discard the estimate Phase 1 already had.
                vocalActivityMask = mergeMasks(features.energyCurve.size, head?.vocalMask, tail?.vocalMask)
                    ?: features.vocalActivityMask,
                vocalProbability = features.vocalProbability,
            ),
        )
    }

    /**
     * Everything a decoded region contributes, once its audio has been let go
     * of. [seconds] is what was actually decoded, which for a partially cached
     * file is not what was asked for.
     */
    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
        val seconds: Double,
        /** Only populated when the caller asked for it; see [region]'s `deriveFeatures`. */
        val features: TrackFeatures.Features? = null,
    )

    /**
     * Decodes one stereo region and runs both models over it, returning only their results.
     *
     * The point of the function boundary is the audio: the stereo buffer, its mono mix and the
     * resampled copies are all local, so they become collectible the moment this returns rather
     * than staying live until the whole analysis finishes. A 30 s stereo region is several
     * megabytes before either model's own working set is counted.
     *
     * Null, or a null field, means "no model evidence for this window" — a codec that will not
     * configure, a region too short, a missing model — which [analyze] already falls back on.
     *
     * The extractor seeks to a sync sample at or before what was asked for, so the region's real
     * start (not [startSeconds]) is what its beat times must be stated against.
     */
    private fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features?,
        deriveFeatures: Boolean = false,
        featureOffsetSeconds: Double = 0.0,
    ): Region? {
        val decoded = openSource()?.use {
            AudioDecoder.decodeRegionStereo(it, startSeconds, endSeconds, abort = { stopped }, label = "automix")
        } ?: run {
                // The two ways this comes back empty mean opposite things and
                // were reported identically, which cost a round of guessing:
                // this one is the extractor refusing the container outright, so
                // the bytes are wrong or not enough of them are there to parse.
                Log.d(TAG, "Region [$startSeconds, $endSeconds) would not open")
                return null
            }
        val (stereo, actualStart) = decoded
        if (stereo.left.size < stereo.sampleRate) {
            // And this one is a container that parsed fine and yielded under a
            // second of audio — a decode that started and ran out, not one that
            // never started.
            Log.d(TAG, "Region [$startSeconds, $endSeconds) decoded ${stereo.left.size} frames; too few")
            return null
        }

        val seconds = stereo.left.size / stereo.sampleRate
        // In a frame of its own so the full-rate mono downmix is released before either model runs.
        // It is 23 MB for this window at 48 kHz — the same size as each of the two channels it
        // averages — and it is read exactly twice, to make the resampled model input and the DSP
        // one. As a local it would nonetheless stay reachable through `tracker.track` and
        // `vocalMask` below, which is where the analysis allocates most heavily and where the
        // process was dying. Same reasoning [derived] already had, one level further out.
        val inputs = regionInputs(stereo, seconds, deriveFeatures)
        val derived = inputs.derived?.shiftedBy(featureOffsetSeconds)

        return Region(
            grid = inputs.forModel?.let { tracker.track(it, offsetSeconds = actualStart) },
            vocalMask = (features ?: derived)?.let { vocalMask(stereo, it, actualStart) },
            seconds = seconds,
            features = derived,
        )
    }

    /** A region's model input, and its DSP features when the caller asked for them. */
    private class RegionInputs(val forModel: FloatArray?, val derived: TrackFeatures.Features?)

    /**
     * Reduces a decoded region to the buffers the models and the DSP actually read.
     *
     * Both come off one full-rate mono downmix, which is why they are made together rather than on
     * demand: that downmix is the largest single allocation in the analysis, and returning is the
     * only way to be rid of it before the models run.
     */
    private fun regionInputs(
        stereo: AudioDecoder.StereoPcm,
        seconds: Double,
        deriveFeatures: Boolean,
    ): RegionInputs {
        val mono = FloatArray(stereo.left.size) { index -> (stereo.left[index] + stereo.right[index]) * 0.5f }
        val forModel = if (abs(stereo.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
            MelSpectrogram.resample(mono, stereo.sampleRate, MelSpectrogram.sampleRate)
        } else {
            mono
        }

        // Derived here rather than by the caller so the mono buffer is still
        // live: handing it back would keep several megabytes reachable for the
        // rest of the analysis, which is the one thing this function exists to
        // avoid.
        val derived = if (deriveFeatures) {
            val forFeatures = if (abs(stereo.sampleRate - TrackFeatures.sampleRate) > 1.0) {
                TrackFeatures.resample(mono, stereo.sampleRate, TrackFeatures.sampleRate)
            } else {
                mono
            }
            forFeatures?.let { TrackFeatures.analyze(it, seconds) }
        } else {
            null
        }

        return RegionInputs(forModel, derived)
    }

    /**
     * A vocal-presence value for every point on the energy curve, filled only where the model
     * actually ran.
     *
     * The policy indexes the mask against energy-curve sample times and requires the two to be the
     * same length, but the model's window is fixed at about 22 seconds, far less than a track. So
     * the mask is built at full length and filled only over this region.
     *
     * Everywhere else stays at [NEUTRAL_VOCAL]. That is not a guess dressed up as data: it sits
     * below the policy's own VOCAL_ACTIVE_THRESHOLD, so unmeasured material can never trip vocal
     * logic in either direction.
     */
    private fun vocalMask(
        stereo: AudioDecoder.StereoPcm,
        features: TrackFeatures.Features,
        actualStart: Double,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty() || !VocalSpectrogram.available) return null

        // The beat model's window is longer than the vocal model's fixed input, so the region is
        // trimmed rather than handed over whole — [VocalTracker.track] refuses anything wider than
        // its graph, and refusing is how the tail of every region would otherwise go unmeasured.
        // Two frames of margin absorb the ±1 sample a rate conversion can land on.
        val maxSeconds = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val maxSamples = (maxSeconds * stereo.sampleRate).toInt().coerceAtMost(stereo.left.size)
        if (maxSamples <= 0) return null
        val left = if (maxSamples < stereo.left.size) stereo.left.copyOf(maxSamples) else stereo.left
        val right = if (maxSamples < stereo.right.size) stereo.right.copyOf(maxSamples) else stereo.right

        val values = vocals.track(left, right, stereo.sampleRate) ?: return null

        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }
        for (index in curve.indices) {
            val frame = ((curve[index].time - actualStart) * VocalSpectrogram.frameRate).toInt()
            if (frame in values.indices) mask[index] = values[frame].toDouble()
        }
        return mask
    }

    /**
     * Overlays the head and tail masks onto one full-length curve, or null when neither ran —
     * which the caller answers by keeping the DSP heuristic rather than reporting a mask of
     * nothing but [NEUTRAL_VOCAL].
     */
    private fun mergeMasks(size: Int, head: DoubleArray?, tail: DoubleArray?): List<Double>? {
        if (size <= 0 || (head == null && tail == null)) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in listOfNotNull(head, tail)) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(trackId: String, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    fun release() {
        executor.shutdownNow()
        sourceResolutionScope.cancel()
        sourceAnalysisUris.clear()
        sourceResolutions.clear()
        sourceResolutionAttempted.clear()
        headAttempts.clear()
        headSkipLogged.clear()
        provisional.clear()
        restoreAttempted.clear()
        requestPending.clear()
        shortDecodes.clear()
        badRenditions.clear()
        triedRenditions.clear()
        discarded.clear()
        headResults.clear()
        tailResults.clear()
        measuredWindows.clear()
        regionFailures.clear()
        analysisMillis.clear()
        tracker.release()
        vocals.release()
    }

    private companion object {
        const val TAG = "BitChordTrackAnalyzer"

        /**
         * What an unmeasured instant reads as. Below the policy's VOCAL_ACTIVE_THRESHOLD by
         * design, so absence of measurement is never mistaken for absence of a vocal, or for the
         * presence of one.
         */
        const val NEUTRAL_VOCAL = 0.5

        /**
         * How much more than the average-bitrate estimate of the opening window
         * to insist on before decoding it. Covers the container header and the
         * fact that a track's opening is rarely at its own average bitrate.
         */
        const val HEAD_BYTES_MARGIN = 1.35

        /**
         * Floor under the computed threshold, and the whole requirement when the
         * duration is unknown. Roughly fifteen seconds at 128 kbps — a little
         * over [MIN_HEAD_SECONDS], so it guarantees a parsable container and a
         * usable decode without quietly reinstating the thirty-second demand the
         * bitrate estimate was just lowered away from.
         */
        const val MIN_HEAD_BYTES = 256L * 1024L

        /**
         * How much of the container's stated duration must actually decode
         * before the whole-track pass is trusted. Not 1.0: a decoder legitimately
         * comes up a frame or two short of the container's rounding, and
         * refusing over that would refuse everything.
         */
        const val MIN_DECODED_FRACTION = 0.95

        /** Refusals before a track is written off as truncated rather than still filling in. */
        const val MAX_SHORT_DECODE_ATTEMPTS = 3

        /**
         * Attempts at one window before it is written off for the session.
         * Bounded so a container that cannot yield its tail is not decoded on
         * every tick; a transient abort is not counted (see [runWindow]).
         */
        const val MAX_REGION_ATTEMPTS = 2

        /**
         * Clean-slate retries per rendition. One: a discard is worth doing when
         * the bytes on disk are unrecoverable and nothing would otherwise
         * overwrite them, and worth doing exactly once, because a second identical
         * answer means the source is serving that file rather than the cache
         * having mangled it.
         */
        const val MAX_RENDITION_DISCARDS = 1

        /**
         * How far two renditions' container durations may differ and still count
         * as the same cut. Generous enough for codec padding and the player's own
         * rounding, tight enough that a different edit of the same song — where a
         * borrowed beat grid would be useless — is rejected.
         */
        const val RENDITION_DURATION_TOLERANCE = 1.0

        /**
         * Stand-in bitrate for a rendition whose real length isn't recorded yet,
         * used only to rank copies against each other in [headSecondsOf]. About
         * 160kbps, the middle of what the streams in play here run at.
         */
        const val ASSUMED_BYTES_PER_SECOND = 20_000.0

        /**
         * The least decoded audio a head-only tempo estimate is allowed to rest
         * on. Twelve seconds is around 24 beats at 120 bpm — enough for the
         * grid's own confidence measure to mean something.
         */
        const val MIN_HEAD_SECONDS = 12.0

        /**
         * How much more of the file has to be cached before the head is worth
         * decoding again. Doubling bounds the attempts to a handful over a whole
         * download while still catching up quickly on a high-bitrate rendition
         * whose first attempt covered only a few seconds.
         */
        const val HEAD_RETRY_GROWTH = 2
    }
}
