package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.LateVersionSwapPolicy
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.PowerState
import com.music.bitchord.playback.StreamChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * Analyzes audio waveforms/energy envelopes to find the exact time alignment
 * offset between two versions of the same track (e.g. YouTube music video with intro
 * skit vs studio catalogue audio release).
 */
@UnstableApi
object VersionAudioAligner {

    private const val TAG = "VersionAudioAligner"
    private const val HOP_MS = 10.0 // 100 frames per second (10ms resolution)
    private const val WIN_MS = 40.0 // 40ms analysis window
    /** How much of each recording's opening one measurement decodes: the window the late handover's cap is built on. */
    internal const val WINDOW_SECONDS = LateVersionSwapPolicy.ALIGNMENT_WINDOW_MS / 1000.0

    /** The least overlap a lag at the edge of the search is still judged on. */
    private const val EDGE_OVERLAP_SECONDS = 5.0

    /** Search up to ±40 s of drift/intro: the window less the overlap a lag needs to be judged at all. */
    private const val MAX_LAG_SECONDS = WINDOW_SECONDS - EDGE_OVERLAP_SECONDS

    /**
     * [KO] align-reach: how much of one side a second pass decodes when the
     * first one's peak is pinned to the lag cap — see [reachFor].
     */
    internal const val REACH_SECONDS = 2 * WINDOW_SECONDS

    /** The lag cap of that second pass, by the same rule as [MAX_LAG_SECONDS]. */
    internal const val REACH_MAX_LAG_SECONDS = REACH_SECONDS - EDGE_OVERLAP_SECONDS
    private const val MIN_CONFIDENCE = VersionSwapPolicy.MIN_SWAP_CONFIDENCE

    // Cache computed offsets so subsequent switches are instantaneous:
    // (sourceVideoId, targetVideoId) -> offsetMs
    private val offsetCache = ConcurrentHashMap<Pair<String, String>, Long>()

    /**
     * The correlation behind the last measurement of a pair, kept even when it
     * was too low to cache an offset: that is exactly the case the swap has to
     * refuse, and the refusal needs the figure to report.
     */
    private val confidenceCache = ConcurrentHashMap<Pair<String, String>, Double>()

    /**
     * Pairs whose measurement said the two recordings do not match. One
     * refusal per pair per process: trying the same hopeless decode again on
     * every future switch only costs time and swaps nothing.
     */
    private val rejectedPairs = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    /** Unit tests only: forgets every measured, refused and cached pair. */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        offsetCache.clear()
        confidenceCache.clear()
        rejectedPairs.clear()
    }

    /** Correlation measured for this pair, if one has been measured. */
    fun getConfidence(sourceVideoId: String, targetVideoId: String): Double? =
        confidenceCache[sourceVideoId to targetVideoId]

    /** True once [reject] has refused this pair in either direction. */
    fun isRejected(sourceVideoId: String, targetVideoId: String): Boolean =
        (sourceVideoId to targetVideoId) in rejectedPairs ||
            (targetVideoId to sourceVideoId) in rejectedPairs

    /** Remembers that this pair must not be swapped for the rest of the process. */
    fun reject(sourceVideoId: String, targetVideoId: String) {
        rejectedPairs.add(sourceVideoId to targetVideoId)
        rejectedPairs.add(targetVideoId to sourceVideoId)
    }

    /** Caches a measured shift both ways (target = source + offset). */
    internal fun storeOffset(sourceVideoId: String, targetVideoId: String, offsetMs: Long) {
        offsetCache[sourceVideoId to targetVideoId] = offsetMs
        offsetCache[targetVideoId to sourceVideoId] = -offsetMs
    }

    /**
     * [KO] align-revision: forgets every pair [videoId] is in. The cache is
     * keyed by id, and one id can change recordings: a downloaded file that
     * held a music video's sound is replaced by the song version under the
     * same id (the moment the song check is reset for it too). A shift or a
     * refusal measured against the old bytes must not be reused for the new.
     */
    fun forget(videoId: String) {
        offsetCache.keys.removeIf { it.first == videoId || it.second == videoId }
        confidenceCache.keys.removeIf { it.first == videoId || it.second == videoId }
        rejectedPairs.removeIf { it.first == videoId || it.second == videoId }
    }

    /** [KO] align-reach: which side a second, longer decode is for. */
    internal enum class Reach { SOURCE, TARGET }

    /**
     * [KO] align-reach: whether the true shift may lie past what one
     * [WINDOW_SECONDS] window can reach — a music video whose intro runs past
     * ~40 s — and so which side a second pass decodes [REACH_SECONDS] of.
     *
     * - A peak pinned to the ±[MAX_LAG_SECONDS] cap says so directly
     *   (phone/emulator log: lag -4000 of -4000..4000, refused). Negative means
     *   the source's music starts later, so the source needs more of its
     *   opening; positive, the target.
     * - An intro longer than the window leaves no overlap at all, so no peak
     *   and no pin: a weak correlation between a video and a song is the only
     *   sign.
     *
     * When exactly one side is a video, that side is reached either way: the
     * intro is the video's, and a pin over audio that does not match at all
     * points at a random edge.
     *
     * A short decode (its search range narrower than the cap) is a truncated
     * head, not a long intro: more of the same bytes cannot help, so it is left
     * to refuse. Every other pair pays nothing; a refused pair is not measured
     * again this process ([reject]).
     */
    internal fun reachFor(
        correlation: Correlation,
        sourceIsVideo: Boolean,
        targetIsVideo: Boolean,
        hopMs: Double = HOP_MS,
    ): Reach? {
        val cap = (MAX_LAG_SECONDS * 1000.0 / hopMs).toInt()
        if (correlation.floor != -cap || correlation.ceiling != cap) return null
        val pinned = correlation.lagFrames == correlation.floor || correlation.lagFrames == correlation.ceiling
        if (!pinned && correlation.confidence >= MIN_CONFIDENCE) return null
        return when {
            sourceIsVideo != targetIsVideo -> if (sourceIsVideo) Reach.SOURCE else Reach.TARGET
            correlation.lagFrames == correlation.floor -> Reach.SOURCE
            correlation.lagFrames == correlation.ceiling -> Reach.TARGET
            else -> null
        }
    }

    /** How far a switch to the other cut has got; published for stats for nerds. */
    enum class CutPhase { FETCHING, MEASURING, ALIGNED, FAILED }

    /**
     * What the switch to the other cut currently knows — the three things a
     * listener actually wants confirmation of: that the other version has been
     * *fetched*, that it has been *measured*, and what shift the transition is
     * going to apply.
     *
     * Kept after the swap lands rather than cleared with it, because the shift
     * is only readable once the cut it describes is the one playing. Scoped to
     * the pair: [NowPlayingScreen] ignores a record naming a track the player
     * is nowhere near, so yesterday's measurement never describes today's song.
     */
    data class AlignmentStatus(
        /** The cut playing when the switch started. */
        val sourceId: String,
        /** The cut being switched to. */
        val targetId: String,
        val phase: CutPhase,
        /** The shift that will be — or was — applied, once it is known. */
        val offsetMs: Long? = null,
        /** True when the figure came out of the cache instead of a fresh pass. */
        val fromCache: Boolean = false,
    )

    val status = MutableStateFlow<AlignmentStatus?>(null)

    /**
     * Names one run of a switch, so the phases that follow can be told apart
     * from the next switch's.
     *
     * A switch is cancelled the moment a second one outranks it, but only at
     * its next suspension point — the statement a cancelled pass is standing on
     * when the new one takes over still runs. Without this, the pass that had
     * already lost would publish [CutPhase.MEASURING] on top of the
     * replacement's [CutPhase.FETCHING], and the status would describe two
     * switches at once.
     */
    private val attempt = AtomicLong()

    /** Opens a switch's status and reports where it starts: fetching. */
    fun beginAttempt(sourceId: String, targetId: String): Long {
        val token = attempt.incrementAndGet()
        status.value = AlignmentStatus(sourceId, targetId, CutPhase.FETCHING)
        return token
    }

    /**
     * Moves the attempt named by [token] on to [phase], and does nothing if a
     * later attempt has taken over since — see [attempt].
     *
     * Non-suspending for the same reason: this is called from a coroutine that
     * can already be cancelled, and a suspending publish would have to survive
     * that to record a phase.
     */
    fun publishPhase(
        token: Long,
        phase: CutPhase,
        offsetMs: Long? = null,
        fromCache: Boolean = false,
    ) {
        if (attempt.get() != token) return
        val current = status.value ?: return
        status.value = current.copy(phase = phase, offsetMs = offsetMs, fromCache = fromCache)
    }

    /**
     * Returns cached offset in milliseconds if already computed, or null if unknown.
     */
    fun getCachedOffsetMs(sourceVideoId: String, targetVideoId: String): Long? {
        if (sourceVideoId == targetVideoId) return 0L
        return offsetCache[sourceVideoId to targetVideoId]
    }

    /**
     * Calculates or retrieves the time offset (in milliseconds) from [sourceSong] to [targetSong].
     * Target timestamp = (source timestamp + offsetMs).
     *
     * **null means it could not be measured** — no bytes on disk, a container
     * the decoder would not open, a correlation too weak to believe. Callers
     * must not fold that into `0`, which is a real answer meaning the two cuts
     * open on the same second; a silent zero is how a thirty-second difference
     * ends up as a swap that lands exactly where the other version left it,
     * with nothing on screen to say the measurement never happened.
     */
    suspend fun findOffsetMs(
        context: Context,
        sourceSong: Song,
        targetSong: Song,
        currentPosMs: Long = 0L,
        /**
         * Measure with the screen off too. For the wrong-song check only: a
         * wrong song in a pocket costs more battery than this one bounded
         * opening, the same reasoning as the song check in [VideoAudio].
         */
        ignoreScreen: Boolean = false,
    ): Long? = withContext(Dispatchers.IO) {
        if (sourceSong.videoId == targetSong.videoId) return@withContext 0L
        val cached = offsetCache[sourceSong.videoId to targetSong.videoId]
        if (cached != null) return@withContext cached
        if (!ignoreScreen && !PowerState.screenOn) return@withContext null

        val sourceDs = openDataSource(context, sourceSong)
        val targetDs = openDataSource(context, targetSong)

        if (sourceDs == null || targetDs == null) {
            safeClose(sourceDs)
            safeClose(targetDs)
            TrackLog.w(
                TAG,
                "Alignment for '${sourceSong.title}': no bytes on disk to measure against",
                sourceSong.videoId,
            )
            return@withContext null
        }

        try {
            // Both decodes at once rather than one after the other, and every
            // phase timed: the measurement runs against a deadline (see
            // [PlaybackService]'s ALIGNMENT_MEASURE_TIMEOUT_MS), and a result
            // arriving past it is discarded as if it had never happened — the
            // swap then goes out with no shift even though the offset was
            // computed. The field log caught exactly that race, with the
            // correlation succeeding 10 ms over a tighter budget.
            var decodes: Pair<Pair<AudioDecoder.Pcm, Double>?, Pair<AudioDecoder.Pcm, Double>?> =
                null to null
            // Cancellable for real: the caller's deadline (or a track change)
            // used to cancel only the coroutine around these blocking decodes,
            // so they sat on at the gate and decoded on while the next song
            // played, 100-330 s past their deadline (round-score 9), and the
            // finished result was then thrown away. Now the gate wait is
            // interrupted and the decode loop stops at its next pass. First in
            // line at the gate: a swap is waiting on this answer.
            val decodeMs = measureTimeMillis {
                decodes = coroutineScope {
                    val source = async { decodeOpening(sourceDs, WINDOW_SECONDS, ignoreScreen) }
                    val target = async { decodeOpening(targetDs, WINDOW_SECONDS, ignoreScreen) }
                    source.await() to target.await()
                }
            }
            val (sourceDecode, targetDecode) = decodes

            if (sourceDecode == null || targetDecode == null) {
                return@withContext null
            }

            val (pcmSource, firstSourceSec) = sourceDecode
            val (pcmTarget, firstTargetSec) = targetDecode
            var startSourceSec = firstSourceSec
            var startTargetSec = firstTargetSec

            if (pcmSource.samples.isEmpty() || pcmTarget.samples.isEmpty()) {
                return@withContext null
            }

            var envelopes: Pair<FloatArray, FloatArray> = FloatArray(0) to FloatArray(0)
            val envelopeMs = measureTimeMillis {
                envelopes = computeEnergyEnvelope(pcmSource.samples, pcmSource.sampleRate) to
                    computeEnergyEnvelope(pcmTarget.samples, pcmTarget.sampleRate)
            }

            var correlation: Correlation = Correlation(0, 0.0, 0, 0)
            val correlateMs = measureTimeMillis {
                correlation = crossCorrelate(envelopes.first, envelopes.second)
            }
            TrackLog.d(
                TAG,
                "Alignment timings for '${sourceSong.title}': decode=${decodeMs}ms, " +
                    "envelope=${envelopeMs}ms, correlate=${correlateMs}ms",
                sourceSong.videoId,
            )
            // [KO] align-reach: a long intro, measured again with more of the
            // side that has it. A fresh source: the first one may be closed by
            // its extractor, and the playing side has grown since. The first
            // pass's figure is cached before, so a second pass cut off by the
            // deadline still leaves the refusal its callers act on.
            confidenceCache[sourceSong.videoId to targetSong.videoId] = correlation.confidence
            confidenceCache[targetSong.videoId to sourceSong.videoId] = correlation.confidence
            reachFor(correlation, sourceSong.isVideo, targetSong.isVideo)?.let { side ->
                val song = if (side == Reach.SOURCE) sourceSong else targetSong
                val longer = openDataSource(context, song)?.let { ds ->
                    try {
                        decodeOpening(ds, REACH_SECONDS, ignoreScreen)
                    } finally {
                        safeClose(ds)
                    }
                } ?: return@let
                val longerEnv = computeEnergyEnvelope(longer.first.samples, longer.first.sampleRate)
                val shorterEnv = if (side == Reach.SOURCE) envelopes.first else envelopes.second
                if (longerEnv.size <= shorterEnv.size) return@let
                correlation = if (side == Reach.SOURCE) {
                    crossCorrelate(longerEnv, envelopes.second, maxLagSec = REACH_MAX_LAG_SECONDS)
                } else {
                    crossCorrelate(envelopes.first, longerEnv, maxLagSec = REACH_MAX_LAG_SECONDS)
                }
                if (side == Reach.SOURCE) startSourceSec = longer.second else startTargetSec = longer.second
                TrackLog.d(
                    TAG,
                    "Alignment for '${sourceSong.title}': peak pinned to the lag cap, " +
                        "measured again with ${longerEnv.size / 100}s of the ${side.name.lowercase()}",
                    sourceSong.videoId,
                )
            }
            val bestLagFrames = correlation.lagFrames
            val confidence = correlation.confidence
            confidenceCache[sourceSong.videoId to targetSong.videoId] = confidence
            confidenceCache[targetSong.videoId to sourceSong.videoId] = confidence
            TrackLog.d(
                TAG,
                "Alignment for '${sourceSong.title}' -> '${targetSong.title}': lagFrames=$bestLagFrames, conf=$confidence, range=${correlation.floor}..${correlation.ceiling}",
                sourceSong.videoId,
            )

            if (confidence >= MIN_CONFIDENCE) {
                // A peak sitting on the edge of what the window could search
                // means the true lag is further out than these bytes reach —
                // which is what a truncated head looks like from here. The
                // eight-second head that a half-finished fetch leaves decodes
                // to a five-second range, finds its best match pinned to it,
                // and would report that as measured: a swap landing a handful
                // of seconds off while claiming thirty. Refuse instead.
                val floor = correlation.floor
                val ceiling = correlation.ceiling
                if (bestLagFrames == floor || bestLagFrames == ceiling) {
                    TrackLog.w(
                        TAG,
                        "Alignment for '${sourceSong.title}' -> '${targetSong.title}': peak pinned to the searchable edge ($bestLagFrames of $floor..$ceiling) — window too short to trust",
                        sourceSong.videoId,
                    )
                    return@withContext null
                }

                val lagMs = (bestLagFrames * HOP_MS).toLong()
                val startDiffMs = ((startTargetSec - startSourceSec) * 1000.0).toLong()
                val totalOffsetMs = lagMs + startDiffMs

                storeOffset(sourceSong.videoId, targetSong.videoId, totalOffsetMs)
                return@withContext totalOffsetMs
            }

            TrackLog.w(
                TAG,
                "Alignment for '${sourceSong.title}' -> '${targetSong.title}': confidence $confidence below $MIN_CONFIDENCE — no shift measured",
                sourceSong.videoId,
            )
        } catch (cancelled: CancellationException) {
            // A deadline or a track change, not a failure: one line, no trace,
            // and the cancellation goes on up so nothing waits on this pass.
            TrackLog.d(
                TAG,
                "Alignment for ${sourceSong.videoId} -> ${targetSong.videoId} cancelled: ${cancelled.message}",
                sourceSong.videoId,
            )
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute audio alignment for ${sourceSong.videoId} -> ${targetSong.videoId}", e)
        } finally {
            safeClose(sourceDs)
            safeClose(targetDs)
        }

        null
    }

    /**
     * Opens the copy of [song] worth measuring: the largest same-source
     * opening on disk, and only a substitute (`#alt`) when nothing else is
     * there at all.
     *
     * Ordered by bytes rather than by whichever URI answers first, because
     * neither URI names a particular copy — the live one resolves to whatever
     * rendition the player is streaming *now*, and the analysis copy under the
     * plain id can be a stale stub while a sibling key holds the whole
     * opening. A stub hands the decoder a second of audio, and a second of
     * audio can only correlate to garbage, which is how every switch measured
     * against one came out "failed". [AudioCache] asks the same question in
     * the same order when it declares the head ready, so what the wait calls
     * ready is what gets opened here.
     */
    internal fun openDataSource(context: Context, song: Song): MediaDataSource? {
        song.localUri?.let { local ->
            val uri = Uri.parse(local)
            if (LocalAudioSource.isLocal(uri)) {
                return LocalAudioSource.open(context.contentResolver, uri)
            }
        }
        val watch = Uri.parse("bitchord://watch?v=${song.videoId}")
        val pick = renditionToMeasure(song.videoId) ?: return null
        return AudioCache.renditionDataSource(watch, pick)
    }

    /**
     * The first [endSeconds] of [ds], decoded for a measurement: first in line
     * at the decode gate (a swap waits on it), and cancellable for real — the
     * gate wait is interrupted and the decode loop stops at its next pass when
     * the caller's deadline or a track change cancels it, or the screen goes
     * off for a screen-bound pass. Before this the deadline cancelled only the
     * coroutine around the blocking decodes, so they sat at the gate and
     * decoded on 100-330 s past it (round-score 9).
     */
    private suspend fun decodeOpening(
        ds: MediaDataSource,
        endSeconds: Double,
        ignoreScreen: Boolean,
    ): Pair<AudioDecoder.Pcm, Double>? =
        withContext(Dispatchers.IO + AudioDecoder.firstInLine.asContextElement(true)) {
            val pass = coroutineContext.job
            AudioDecoder.interruptibleDecode {
                AudioDecoder.decodeRegion(
                    ds,
                    0.0,
                    endSeconds,
                    abort = { alignmentAbandoned(pass, ignoreScreen, PowerState.screenOn) },
                    label = "align",
                )
            }
        }

    /** The copy of [videoId] that [openDataSource] reads — see there for the order. */
    internal fun renditionToMeasure(videoId: String): AudioCache.Rendition? {
        val watch = Uri.parse("bitchord://watch?v=$videoId")
        return chooseMeasurementCopy(AudioCache.renditionsOf(watch), StreamChoice.isYouTube(videoId))
    }

    /**
     * Whether [key] holds YouTube's own bytes although its name says substitute.
     *
     * With a source ranked above YouTube the player writes under `#alt` or
     * `#alt-noatmos` whatever the race settles on, so a song that settled on
     * YouTube plays from there. Taken for a stranger, that copy was never heard
     * from: the check went round through the decoder and answered 100-240 s
     * late, after the song had gone by uncorrected (11-06: 3 of 7 songs, one of
     * them 11.6 s off with the right fit arriving two minutes after its end).
     * A `#hifi` upgrade has its own recording, and stays out.
     *
     * When nothing is settled in this process (a restart, or a check that runs
     * before the resolve), the cache's own META_ORIGIN record is the evidence:
     * an entry [recordedYouTube] filled is YouTube's clock. An addon's copy, or
     * one with no record, stays a stranger.
     */
    internal fun isYouTubeUnderAltKey(
        key: String,
        youTubeChosen: Boolean,
        recordedYouTube: Boolean = false,
    ): Boolean {
        if (!youTubeChosen && !recordedYouTube) return false
        val tag = key.substringAfter('#', "").substringBefore('#')
        return tag == "alt" || tag.startsWith("alt-")
    }

    /**
     * Which of [onDisk] a measurement would open — the same choice
     * [renditionToMeasure] makes, separated from the cache so readiness can be
     * tested with plain values.
     */
    internal fun chooseMeasurementCopy(
        onDisk: List<AudioCache.Rendition>,
        youTubeChosen: Boolean = false,
    ): AudioCache.Rendition? {
        val sameSource = onDisk
            .filter {
                !AutomixAnalysisSource.isSubstituteRendition(it.key) ||
                    isYouTubeUnderAltKey(it.key, youTubeChosen, it.origin == AudioCache.Origin.YOUTUBE)
            }
            .maxByOrNull { it.cachedPrefix }
        return if (sameSource != null &&
            sameSource.cachedPrefix >= AudioCache.MIN_ALIGNMENT_PREFIX_BYTES
        ) {
            sameSource
        } else {
            onDisk.maxByOrNull { it.cachedPrefix }
        }
    }

    /**
     * Whether an alignment decode must stop now: its pass was cancelled (a
     * deadline, a track change, the service going away), or it is a
     * screen-bound pass and the screen went off.
     */
    internal fun alignmentAbandoned(pass: Job, ignoreScreen: Boolean, screenOn: Boolean): Boolean =
        !pass.isActive || (!ignoreScreen && !screenOn)

    private fun safeClose(ds: MediaDataSource?) {
        if (ds != null) {
            runCatching { ds.close() }
        }
    }

    /**
     * Computes RMS energy envelope over sliding windows.
     */
    private fun computeEnergyEnvelope(
        samples: FloatArray,
        sampleRate: Double,
        hopMs: Double = HOP_MS,
        winMs: Double = WIN_MS,
    ): FloatArray {
        val hopSamples = max(1, (sampleRate * (hopMs / 1000.0)).toInt())
        val winSamples = max(hopSamples, (sampleRate * (winMs / 1000.0)).toInt())
        val numFrames = max(0, (samples.size - winSamples) / hopSamples)
        if (numFrames <= 0) return FloatArray(0)

        val env = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            val start = i * hopSamples
            val end = min(samples.size, start + winSamples)
            var sumSq = 0.0
            for (j in start until end) {
                val s = samples[j]
                sumSq += s * s
            }
            env[i] = sqrt(sumSq / (end - start)).toFloat()
        }
        return env
    }

    /**
     * A peak found by [crossCorrelate], together with the range it was found in.
     *
     * [floor] and [ceiling] are carried out of the search rather than left
     * there because they are the only way to tell a measured lag from a lag the
     * window was too small to move past — see [findOffsetMs].
     */
    data class Correlation(val lagFrames: Int, val confidence: Double, val floor: Int, val ceiling: Int)

    /**
     * Normalized cross-correlation between source envelope and target envelope.
     * Returns the best lag in frames, its confidence, and the lag range searched.
     * Positive lag means target event occurs later than source event (target = source + lag).
     *
     * Same numbers as the brute-force double loop this replaced — per-lag
     * means and variances from prefix sums, cross terms from one FFT pass —
     * but the cost drops from ~70M multiply-adds per pair of 45 s windows
     * (which on a cold device ran long enough for the measurement to blow its
     * whole deadline *after* finding the answer) to two O(n log n) transforms
     * and a linear scan over the lags.
     */
    fun crossCorrelate(
        sourceEnv: FloatArray,
        targetEnv: FloatArray,
        hopMs: Double = HOP_MS,
        maxLagSec: Double = MAX_LAG_SECONDS,
    ): Correlation {
        if (sourceEnv.isEmpty() || targetEnv.isEmpty()) return Correlation(0, 0.0, 0, 0)

        val maxLagFrames = (maxLagSec * 1000.0 / hopMs).toInt()
        val minOverlapFrames = (3.0 * 1000.0 / hopMs).toInt() // At least 3 seconds overlap

        var bestLag = 0
        var bestCorrelation = -1.0

        // lag d means targetEnv[i + d] corresponds to sourceEnv[i]
        val minLag = max(-maxLagFrames, -(sourceEnv.size - minOverlapFrames))
        val maxLag = min(maxLagFrames, targetEnv.size - minOverlapFrames)

        val crossTerms = crossTermsByLag(sourceEnv, targetEnv)

        val srcPrefix = DoubleArray(sourceEnv.size + 1)
        val srcSqPrefix = DoubleArray(sourceEnv.size + 1)
        for (i in sourceEnv.indices) {
            val v = sourceEnv[i].toDouble()
            srcPrefix[i + 1] = srcPrefix[i] + v
            srcSqPrefix[i + 1] = srcSqPrefix[i] + v * v
        }
        val tgtPrefix = DoubleArray(targetEnv.size + 1)
        val tgtSqPrefix = DoubleArray(targetEnv.size + 1)
        for (i in targetEnv.indices) {
            val v = targetEnv[i].toDouble()
            tgtPrefix[i + 1] = tgtPrefix[i] + v
            tgtSqPrefix[i + 1] = tgtSqPrefix[i] + v * v
        }

        for (lag in minLag..maxLag) {
            val srcStart = max(0, -lag)
            val tgtStart = max(0, lag)
            val overlapLen = min(sourceEnv.size - srcStart, targetEnv.size - tgtStart)
            if (overlapLen < minOverlapFrames) continue

            val sumSrc = srcPrefix[srcStart + overlapLen] - srcPrefix[srcStart]
            val sumTgt = tgtPrefix[tgtStart + overlapLen] - tgtPrefix[tgtStart]
            val sumSrcSq = srcSqPrefix[srcStart + overlapLen] - srcSqPrefix[srcStart]
            val sumTgtSq = tgtSqPrefix[tgtStart + overlapLen] - tgtSqPrefix[tgtStart]

            // The FFT hands back the raw Σx·y; the numerator the denominator's
            // variances expect is the centered one:
            // Σ(x−x̄)(y−ȳ) = Σxy − (Σx)(Σy)/n. Without this the confidence is
            // uncentered and can exceed 1 — a meaningless threshold.
            val num = crossTerms[lag + sourceEnv.size - 1] - sumSrc * sumTgt / overlapLen
            val denSrc = sumSrcSq - sumSrc * sumSrc / overlapLen
            val denTgt = sumTgtSq - sumTgt * sumTgt / overlapLen

            val denom = sqrt(denSrc * denTgt)
            if (denom > 1e-6) {
                val r = num / denom
                if (r > bestCorrelation) {
                    bestCorrelation = r
                    bestLag = lag
                }
            }
        }

        return Correlation(bestLag, bestCorrelation, minLag, maxLag)
    }

    /**
     * Σ source[i]·target[i+lag] for every lag the search may ask about,
     * indexed as `result[lag + source.size - 1]`.
     *
     * One zero-padded FFT pair does what the inner loop used to: the
     * cross-correlation theorem turns the per-lag sums into
     * `IFFT(conj(FFT(source)) · FFT(target))`, and padding to at least
     * source + target samples keeps the circular wrap out of the linear range
     * −(n−1)…(m−1), which covers any windowed lag with overlap.
     */
    private fun crossTermsByLag(source: FloatArray, target: FloatArray): DoubleArray {
        val n = source.size
        val m = target.size
        var fftLen = 1
        while (fftLen < n + m) fftLen = fftLen shl 1

        val srcRe = DoubleArray(fftLen)
        val srcIm = DoubleArray(fftLen)
        for (i in 0 until n) srcRe[i] = source[i].toDouble()
        val tgtRe = DoubleArray(fftLen)
        val tgtIm = DoubleArray(fftLen)
        for (i in 0 until m) tgtRe[i] = target[i].toDouble()

        fft(srcRe, srcIm)
        fft(tgtRe, tgtIm)

        // conj(X) · Y — the transform of the cross-correlation rather than the
        // convolution: the conjugate is what keeps the lag sign as written.
        for (i in 0 until fftLen) {
            val a = srcRe[i]
            val b = -srcIm[i]
            val c = tgtRe[i]
            val d = tgtIm[i]
            srcRe[i] = a * c - b * d
            srcIm[i] = a * d + b * c
        }
        fft(srcRe, srcIm, inverse = true)

        val result = DoubleArray(n + m - 1)
        for (lag in -(n - 1) until m) {
            val bin = ((lag % fftLen) + fftLen) % fftLen
            result[lag + n - 1] = srcRe[bin]
        }
        return result
    }

    /**
     * In-place iterative radix-2 FFT; the arrays' length must be a power of
     * two. [inverse] divides by the length on the way out.
     */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            for (start in 0 until n step len) {
                var twiddleRe = 1.0
                var twiddleIm = 0.0
                for (k in 0 until len / 2) {
                    val evenRe = re[start + k]
                    val evenIm = im[start + k]
                    val oddRe =
                        re[start + k + len / 2] * twiddleRe - im[start + k + len / 2] * twiddleIm
                    val oddIm =
                        re[start + k + len / 2] * twiddleIm + im[start + k + len / 2] * twiddleRe
                    re[start + k] = evenRe + oddRe
                    im[start + k] = evenIm + oddIm
                    re[start + k + len / 2] = evenRe - oddRe
                    im[start + k + len / 2] = evenIm - oddIm
                    val nextRe = twiddleRe * stepRe - twiddleIm * stepIm
                    twiddleIm = twiddleRe * stepIm + twiddleIm * stepRe
                    twiddleRe = nextRe
                }
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}
