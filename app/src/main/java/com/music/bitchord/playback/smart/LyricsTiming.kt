package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.DiagnosticsState
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.BaseChoicePolicy
import com.music.bitchord.data.lyrics.ChromaAlignment
import com.music.bitchord.data.lyrics.DownloadedSyncCheck
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsAiGuard
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsPerLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTimeMap
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.lyrics.RecordingPick
import com.music.bitchord.data.lyrics.RefineExtendPolicy
import com.music.bitchord.data.lyrics.RefinePolicy
import com.music.bitchord.data.lyrics.SongCheckWindow
import com.music.bitchord.data.lyrics.SongSyncRetry
import com.music.bitchord.data.lyrics.TimingFit
import com.music.bitchord.data.lyrics.TimingPieces
import com.music.bitchord.data.lyrics.retimedBy
import com.music.bitchord.data.lyrics.retimedByLine
import com.music.bitchord.data.lyrics.timedBy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.HqMatchPolicy
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.LyricsTag
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.NextPrepPolicy
import com.music.bitchord.playback.PowerState
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.SyncSafetyPolicy
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Moves a video's lyrics onto the video's own clock.
 *
 * Lyrics are timed against the record. A cover, a live clip or a music video
 * runs on its own clock — a longer intro, a stream cut that starts mid-chat, a
 * band a little slower than the record, a verse left out — so the right words
 * light up at the wrong moment. Three ways to fix that:
 *
 *  1. **Automatic, free** — [measure]: the original recording and the video are
 *     both reduced to what is being played, note by note, and lined up end to
 *     end ([ChromaAlignment]). That follows a different intro, a different
 *     pace, a cut or an insert and a different key; a different song, or a
 *     cover too far from the record, gives nothing rather than a guess. While
 *     the video is still downloading it is measured on what has arrived and
 *     measured again as more does.
 *  2. **AI (Gemini)** — [placeWithAi]: Gemini watches the YouTube video and says
 *     when each line starts. Its answers are fitted to one straight line —
 *     a start and a pace ([TimingFit]), or for a transcript, several stretches
 *     of such a line where the structure differs ([TimingPieces]) — rather than
 *     taken one by one, and refused when they agree on none. On the AI button, or automatically when
 *     the free measurement has nothing (the "가사 타이밍 자동 보정" setting).
 *  3. **By hand** — [setManual]: the listener's own correction for this one
 *     video, on top of whichever of the two is in place.
 *
 * All three are kept per video on disk, so each is paid for once.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object LyricsTiming {

    private const val TAG = "LyricsTiming"

    /**
     * Collects the engine-stage failure of one next-prep sync attempt.
     *
     * The lyrics screen and the player also run these checks, so the failure
     * is carried by the caller instead of being written to the store: only the
     * next-song bundle needs to tell a real failure from a healthy
     * `no-answer`, and a failed attempt must not be kept as a confirmed answer.
     */
    class NextSyncProbe {
        @Volatile
        var failure: NextPrepPolicy.SyncFailure? = null

        /**
         * Starts one check pass. A pass reports only its own engine-stage
         * failure: the short opening's "too few words" must not survive a
         * later pass that reached the engine, or that pass's answer is not
         * stored and the next playback pays for the same song again.
         */
        fun beginPass() {
            failure = null
        }
    }

    /**
     * Which audio clock a stored correction was measured on.
     *
     * [Unknown] is every record saved before the clock was kept: it keeps the
     * old behaviour exactly, including the saved HQ map's safety switch.
     * [YouTube] is the upload the providers and measurements agree on.
     * [Substitute] names the verified high-quality rendition actually read.
     */
    sealed interface Clock {
        data object Unknown : Clock
        data object YouTube : Clock
        data class Substitute(val sourceConfigId: String, val trackId: String) : Clock
    }

    private const val CLOCK_VERSION = 1
    private const val CLOCK_YOUTUBE = "yt"
    private const val CLOCK_SUBSTITUTE = "alt"

    /** How the lines were moved before any correction by hand. */
    sealed interface Adjustment {
        val byAi: Boolean

        /** Every line by the same amount — what ko-21/22 kept. */
        data class Shift(val ms: Long, override val byAi: Boolean) : Adjustment

        /**
         * Record time to video time through [map]: a shift, a shift and a pace,
         * or (measured) a path through a cut or an insert.
         */
        data class Mapped(
            val map: LyricsTimeMap,
            override val byAi: Boolean,
            /** Video seconds per record second; 1 when the video keeps the record's pace. */
            val rate: Double,
            /** How much of the song agreed with the answer, 0–100. */
            val matchPercent: Int?,
        ) : Adjustment
    }

    /**
     * Heard words for the per-line layer, with the rendition key and the clock
     * they belong to and the playing one. [timing] converts the words onto the
     * playing clock before building this, so the two clocks are equal whenever
     * the layer runs; a missing map leaves the words out instead.
     */
    data class PerLineWords(
        val words: List<LyricsWordAlign.Heard>,
        val wordsKey: Int,
        val currentKey: Int,
        val wordsClock: Clock = Clock.Unknown,
        val currentClock: Clock = Clock.Unknown,
    )

    /**
     * The refine's move at [atMs]: a constant or a drift up to [untilMs],
     * capped at the narrow or wide limit it belongs to ([wide]: the whole-song
     * drift and the wide window's one move, which grow with the song). Shared by
     * the timing actually displayed and the "is this timing still unsettled?"
     * check, so the two cannot disagree about how far the refine reached.
     */
    private fun refineMove(
        atMs: Long,
        refineMs: Long,
        slopePerMin: Double,
        untilMs: Long,
        wide: Boolean = false,
    ): Long {
        val move = refineMs + slopePerMin / 60_000.0 * minOf(atMs, untilMs)
        // Only a move measured across the whole song may pass the narrow cap: a
        // narrow constant or line is held to ±[RefinePolicy.MAX_SHIFT_MS]
        // (11-04 Sol: a mis-aligned chorus must not move lines 10 s). A wide move
        // with no slope is one the policy already held to its own cap; only a
        // drift can still grow past it here.
        val cap = when {
            !wide -> RefinePolicy.MAX_SHIFT_MS
            slopePerMin == 0.0 -> RefinePolicy.MAX_FLAT_SHIFT_MS
            else -> RefinePolicy.MAX_WIDE_SHIFT_MS
        }
        return move.roundToLong().coerceIn(-cap, cap)
    }

    /**
     * Everything applied to one video's lyrics: [base], then [manualMs] on
     * top — and, for Gemini's answer, [aiBiasMs] taken off: how late its
     * answers have run against the measurement, learned over many songs.
     */
    data class Timing(
        val base: Adjustment?,
        val manualMs: Long,
        val aiBiasMs: Long = 0L,
        /** Who gave [base] when it is an AI answer — "Deepgram", "Deepgram Whisper" or "Gemini". */
        val aiEngine: String? = null,
        /**
         * What the transcript heard for this video, already on the displayed
         * lines' clock; empty unless the applied [base] is the AI answer the
         * words came with. See [LyricsWordAlign.Heard] and `heardWords`.
         */
        val heardWords: List<LyricsWordAlign.Heard> = emptyList(),
        /**
         * YouTube song version → the high-quality copy actually playing.
         *
         * Set only when the current stream is a substitute that passed the
         * sound comparison. The lyrics are timed against the YouTube version
         * (that is the clock the measurement and every lyric provider agree
         * on), so this map is applied after [base] to put them on the copy's
         * own clock. Null means the clocks are the same.
         */
        val hq: Adjustment.Mapped? = null,
        /**
         * The last line-level correction, on top of [base] and [hq] and under
         * [manualMs]: the constant offset learned from the words heard for
         * this video, plus the drift per minute when the residuals followed a
         * straight line. See [RefinePolicy].
         */
        val refineMs: Long = 0L,
        /** The refine's drift in ms per minute; 0 for a constant move. */
        val refineSlopePerMin: Double = 0.0,
        /**
         * The last moment the refine's words were heard (video clock). A drift
         * is only known up to here; past it the move is held, not extended
         * (11-02: an opening's 333 ms/min, carried on to the end, left wN7 0.3 s
         * late with a fresh drift the song never had).
         */
        val refineUntilMs: Long = Long.MAX_VALUE,
        /**
         * The refine's move was measured across the whole song (a drift, or the
         * wide window's one move), so it may reach the wide cap. A narrow
         * constant or line is held to the narrow one.
         */
        val refineWide: Boolean = false,
        /**
         * The words the per-line layer uses, on the base/HQ/refine clock (the
         * hand's correction is not in them), and the rendition keys they were
         * heard on. Null when no word-level transcript belongs to the sound
         * now playing. See [LyricsPerLine].
         */
        val perLine: PerLineWords? = null,
        /** The song these words belong to, for the per-line log line. */
        val videoId: String? = null,
    ) {
        val isEmpty: Boolean
            get() = base == null && manualMs == 0L && hq == null &&
                refineMs == 0L && refineSlopePerMin == 0.0 && perLine == null

        /** What goes on top of [base]: the hand's correction, less Gemini's lateness. */
        val extraMs: Long get() = manualMs - if (base?.byAi == true) aiBiasMs else 0L

        /** The refine's move at [atMs], capped at the narrow or wide limit it belongs to. */
        fun refineAt(atMs: Long): Long =
            refineMove(atMs, refineMs, refineSlopePerMin, refineUntilMs, refineWide)
    }

    /**
     * Sources whose timings already belong to the video they were fetched
     * for: the video's own captions. YouTube Music's and SimpMusic's lyrics
     * are asked for by video too, but they are the song's lyrics, timed to
     * the record — on a cover they run as early or late as any other source.
     * What Gemini wrote down off the video is timed to the video too.
     */
    fun timedToVideo(source: LyricsSource?): Boolean =
        source == LyricsSource.YOUTUBE_TRANSCRIPT || source == LyricsSource.GEMINI_TRANSCRIPT

    /** Fires the video id whose timing just changed, for screens showing it. */
    val changes: SharedFlow<String> get() = _changes
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * A substitute was just recorded for [videoId]: let the lyrics screen, the
     * lock screen and the next-row preparation re-read the clock on this event.
     * No timer and no poll.
     */
    fun notifyStreamChoice(videoId: String) {
        _changes.tryEmit(videoId)
    }

    /**
     * Rows whose sheet has to be re-chosen for the recording that plays: a
     * song version found only after the first sound means this video keeps
     * playing its own audio, and every provider's sheet is timed to the
     * release instead. With AI timing off nothing will move it onto the
     * video's clock, so the one sheet that can belong to the recording
     * playing is the video's own — see [timedToVideo]. The lyrics screen
     * asks that one source, once, on this event. No timer and no poll.
     */
    val lateSheetWanted: SharedFlow<String> get() = _lateSheetWanted
    private val _lateSheetWanted = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** A measured shift smaller than this, at the record's pace, is the two being the same. */
    private const val MIN_SHIFT_MS = 150L

    /** A correction by hand goes no further than this either way. */
    const val MAX_MANUAL_MS = 60_000L

    /**
     * What is known for [videoId] and these [lines]: an AI answer first, then
     * the measurement, then the correction by hand. [baseAllowed] false keeps
     * only the correction. [measuredAllowed] false leaves the measurement out —
     * for lyrics timed by the video's own captions, which an AI answer asked
     * for may still move but a record's measurement has nothing to say about.
     */
    suspend fun timing(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        baseAllowed: Boolean,
        measuredAllowed: Boolean = baseAllowed,
        isVideo: Boolean = false,
        audioSource: String? = null,
        /** Which caller's gate set [baseAllowed] false, for the base-apply line. */
        deniedBy: String? = null,
        // A whole song's words against its sheet is real work, and the screen
        // asks this on its own thread.
    ): Timing? = withContext(Dispatchers.Default) {
        timingNow(context, videoId, lines, baseAllowed, measuredAllowed, isVideo, audioSource, deniedBy)
    }

    private suspend fun timingNow(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        baseAllowed: Boolean,
        measuredAllowed: Boolean,
        isVideo: Boolean,
        audioSource: String?,
        deniedBy: String?,
    ): Timing? {
        // A missing entry is not "nothing to do": the stream may still be a
        // verified high-quality copy whose clock differs from YouTube's, and
        // that map applies even when no measurement or AI answer exists.
        val entry = store(context).get(videoId) ?: Entry()
        var key = textKey(lines, audioSource)
        var rule = if (baseAllowed) RULE_NOTHING else "not-allowed(reason=${deniedBy ?: "caller"})"
        var originalBase = if (baseAllowed) {
            baseOf(entry, lines, measuredAllowed, isVideo, audioSource, key, videoId) { rule = it }
        } else {
            null
        }
        val playing = playingClock(videoId)
        // A song check started on the stream can finish after playback moved to
        // the verified file and leave its answer under the stream key (one AI
        // slot, newest wins), so the file-keyed screen found none (11-06
        // cCfPDrRQp9k: base-choice → ai, applied none). The other source's
        // answer is the same recording only when a substitute clock proves it;
        // [conversion] below then carries it or drops it. Only when this key
        // had no candidate at all: a NONE chosen here stays none.
        val sibling = siblingSource(videoId, audioSource)
        // Why the other source's answer was not taken, for the base-apply line.
        var siblingWhy: String? = null
        if (originalBase == null && rule == RULE_NOTHING && baseAllowed && !isVideo && sibling != null) {
            val aiClock = clockOf(entry.aiClock)
            if (aiClock !is Clock.Unknown && (aiClock is Clock.Substitute || playing is Clock.Substitute)) {
                var siblingRule = RULE_NOTHING
                baseOf(entry, lines, false, false, sibling, textKey(lines, sibling), videoId) {
                    siblingRule = it
                }?.takeIf { it.byAi }?.let {
                    originalBase = it
                    rule = "sibling $siblingRule"
                }
                if (originalBase == null) siblingWhy = siblingRule
            } else {
                siblingWhy = when {
                    entry.aiTextKey == null -> "no-ai"
                    aiClock is Clock.Unknown -> "untagged"
                    else -> "no-substitute-clock"
                }
            }
        }
        // A downloaded video's answer may have been kept under its file key
        // by an older song check; the video key and the file key name the same
        // recording for a video, so look there before saying there is none.
        if (originalBase == null && baseAllowed && isVideo && audioSource == null) {
            val fileSource = audioSourceKey(videoId, localFile = true)
            val fileKey = textKey(lines, fileSource)
            var fileRule = RULE_NOTHING
            baseOf(entry, lines, measuredAllowed, isVideo, fileSource, fileKey, videoId) { fileRule = it }?.let {
                key = fileKey
                originalBase = it
                rule = "video-file $fileRule"
            }
        }
        var converted = 0
        var dropped = 0
        // A correction tagged with the clock it was measured on is put on the
        // playing clock now. Old records carry no tag and keep hqAdjustment's
        // switch-gated behaviour below.
        val baseClock = when {
            originalBase == null -> Clock.Unknown
            originalBase.byAi -> clockOf(entry.aiClock)
            else -> clockOf(entry.autoClock)
        }
        var hq = hqAdjustment(context, videoId)
        // A substitute is not refused here: its clock is a clock like any
        // other. What is refused is an answer that cannot be placed on the
        // playing one — dropped, never shown on the wrong clock.
        val saved = originalBase
        val base = if (saved == null) {
            null
        } else if (baseClock == playing && playing !is Clock.Unknown) {
            // Already on the clock playing: nothing to carry across, and the
            // YouTube→copy map must not run a second time.
            hq = null
            saved
        } else if (baseClock is Clock.Unknown && (playing is Clock.YouTube || hq != null)) {
            // An untagged record was made on the upload. On the upload that is
            // the playing clock; on a copy only the verified HQ map carries it,
            // and it is applied on top of it below.
            saved
        } else {
            val conversion = conversion(context, videoId, baseClock, playing)
            if (conversion == null) {
                dropped++
                null
            } else {
                hq = null
                converted++
                convertAdjustment(saved, conversion)
            }
        }
        logBaseApply(
            videoId = videoId,
            base = base,
            shiftMs = base?.let { shownShiftMs(lines, it) } ?: 0L,
            rule = when {
                saved != null && base == null -> "$rule, clock dropped"
                rule == RULE_NOTHING -> buildString {
                    // No candidate at all: say whether the song check ran, was
                    // held or spent its two replies, so "nothing" can be told apart.
                    val retryHeld = entry.songRetryKey == key &&
                        (entry.songRetryAtMs ?: 0L) > System.currentTimeMillis()
                    append(rule).append(", song-check=")
                    append(if (isVideo) "video" else AiTimingPolicy.songCheckState(knowledge(entry), key, retryHeld))
                    if (entry.aiTextKey != null && entry.aiTextKey != key) append(", ai=other-lyrics")
                    siblingWhy?.let { append(", sibling=").append(it) }
                }
                else -> rule
            },
            source = audioSource?.substringAfterLast('|') ?: if (isVideo) "video" else "none",
        )
        val manualClock = clockOf(entry.manualClock)
        var manualMs = entry.manualMs ?: 0L
        if (manualMs != 0L && manualClock !is Clock.Unknown && playing !is Clock.Unknown &&
            manualClock != playing
        ) {
            val conversion = conversion(context, videoId, manualClock, playing)
            if (conversion == null) {
                dropped++
                manualMs = 0L
            } else {
                converted++
                manualMs = conversion.delta(manualMs)
            }
        }
        val engine = if (base?.byAi == true) entry.aiEngine ?: ENGINE_GEMINI else null
        val bias = engine?.let { aiBias(context, it) } ?: 0L
        val timing = Timing(
            base = base,
            manualMs = manualMs,
            aiBiasMs = bias,
            aiEngine = engine,
            hq = hq,
            videoId = videoId,
        )
        // The provider's own line times can stay a fifth of a second early or
        // late even when [base] has already put the record on the video's
        // clock, and a longer video can drift. One constant move or one
        // straight line, learned from the words heard, goes on top of base and
        // HQ and under the listener's own correction.
        val refineSource = refineWords(entry, key, base)
        val refineClock = when {
            refineSource.words.isEmpty() -> emptyList()
            refineSource.clock is Clock.Unknown || playing is Clock.Unknown ->
                onHqClock(refineSource.words, timing)
            refineSource.clock == playing -> refineSource.words
            else -> {
                val conversion = conversion(context, videoId, refineSource.clock, playing)
                if (conversion == null) {
                    dropped++
                    emptyList()
                } else {
                    converted++
                    conversion.words(refineSource.words)
                }
            }
        }
        val refine = if (baseAllowed && refineClock.isNotEmpty()) {
            RefinePolicy.compute(
                apply(
                    lines,
                    timing.copy(
                        manualMs = 0L,
                        refineMs = 0L,
                        refineSlopePerMin = 0.0,
                        perLine = null,
                    ),
                ),
                refineClock,
                lineLevelWords = refineSource.lineLevel,
            )
        } else {
            RefinePolicy.Result.NONE
        }
        val refined = timing.copy(
            refineMs = if (refine.applied) refine.medianMs else 0L,
            refineSlopePerMin = if (refine.applied) refine.slopePerMin else 0.0,
            refineUntilMs = refineClock.maxOfOrNull { it.endMs } ?: Long.MAX_VALUE,
            refineWide = refine.applied && refine.model.wide,
        )
        // The per-line layer runs after refine and under the hand's correction.
        // Its words are already on the playing clock, so [apply] compares them
        // as they are with the lines it has after base, HQ and refine. Adding
        // the refine to the words too counted it twice: every anchored line
        // moved by the refine again (hidden while English lines never anchored,
        // 11-03).
        val perLineWords = if (baseAllowed && refineClock.isNotEmpty() &&
            SyncSafetyPolicy.perLineTimingAllowed(AppSettings.syncSafetySwitches)
        ) {
            refineClock.map { word -> LyricsWordAlign.Heard(word.text, word.startMs, word.endMs) }
        } else {
            emptyList()
        }
        val perLine = perLineWords.takeIf { it.isNotEmpty() }?.let {
            PerLineWords(
                words = it,
                wordsKey = key,
                currentKey = key,
                wordsClock = playing,
                currentClock = playing,
            )
        }
        val refinedWithPerLine = refined.copy(perLine = perLine)
        val refinedLines = applyThroughRefine(lines, refinedWithPerLine)
        // What the per-line layer does to the lines, so the sweep's words can
        // move with their own line instead of staying on the unshifted clock.
        val perLineLayer = perLine?.let { words ->
            LyricsPerLine.compute(refinedLines, words.words, words.wordsKey, words.currentKey)
        } ?: LyricsPerLine.Layer.identity(lines.size)
        fun perLineShiftAt(ms: Long): Long {
            var index = -1
            for (i in refinedLines.indices) {
                if (refinedLines[i].timeMs <= ms) index = i else break
            }
            return perLineLayer.shiftsMs.getOrElse(index) { 0L }
        }
        // The transcript is kept on the video's own clock, and a displayed line
        // is map(record) + refine + per-line + extraMs; the words are already
        // video time, so the rest is still missing. Read it here rather than
        // bake it into the kept words: a correction by hand, a refine, a
        // per-line move, or a newly learned engine lateness must move the heard
        // words with their line.
        val savedHeard = if (base?.byAi == true && entry.aiTextKey == key) {
            entry.heardWords
        } else {
            emptyList()
        }
        val heardSourceClock = if (savedHeard.isEmpty()) Clock.Unknown else clockOf(entry.heardClock)
        val heardOnClock = when {
            savedHeard.isEmpty() -> emptyList()
            heardSourceClock is Clock.Unknown || playing is Clock.Unknown -> savedHeard
            heardSourceClock == playing -> savedHeard
            else -> {
                val conversion = conversion(context, videoId, heardSourceClock, playing)
                if (conversion == null) {
                    dropped++
                    emptyList()
                } else {
                    converted++
                    conversion.savedWords(savedHeard)
                }
            }
        }
        val hqMap = if (heardSourceClock is Clock.Unknown || playing is Clock.Unknown) {
            refined.hq?.map
        } else {
            null
        }
        val heard = heardOnClock.map { word ->
            fun heardAt(ms: Long): Long {
                val onClock = hqMap?.map(ms) ?: ms
                val refinedAt = (onClock + refined.refineAt(onClock)).coerceAtLeast(0L)
                return (refinedAt + perLineShiftAt(refinedAt) + refined.extraMs).coerceAtLeast(0L)
            }
            val start = heardAt(word.s)
            LyricsWordAlign.Heard(
                text = word.t,
                startMs = start,
                endMs = maxOf(start, heardAt(word.e)),
            )
        }
        logClocks(videoId, playing, baseClock, refineSource.clock, manualClock, converted, dropped)
        return refinedWithPerLine.copy(heardWords = heard).takeUnless { it.isEmpty }
    }

    /**
     * The words a refine may compare with: the AI answer's own transcript when
     * that is the base, otherwise the transcript kept for refine (a measured
     * song's first ninety seconds), falling back to any AI words already heard.
     * The clock is the one those words were heard on.
     */
    private class WordSource(
        val words: List<LyricsWordAlign.Heard>,
        val clock: Clock,
        /**
         * [words] are an AI's own line times, not a transcript of what was sung.
         * A drift read from those is the answer agreeing with itself, so
         * [RefinePolicy] must not fit one.
         */
        val lineLevel: Boolean = false,
    )

    private fun refineWords(
        entry: Entry,
        key: Int,
        base: Adjustment?,
    ): WordSource {
        // The refine's own transcript first: an AI answer's words can be
        // Gemini's, which are line-level and match no line's opening letters
        // (vc80: qQNbfxDLxq0, base=ai, refine found 0 lines and stayed -0.24 s).
        val (saved, clock) = when {
            entry.refineHeardKey == key && entry.refineHeardWords.isNotEmpty() ->
                entry.refineHeardWords to clockOf(entry.refineHeardClock)
            base?.byAi == true && entry.aiTextKey == key ->
                entry.heardWords to clockOf(entry.heardClock)
            entry.aiTextKey == key ->
                entry.heardWords to clockOf(entry.heardClock)
            else -> emptyList<SavedWord>() to Clock.Unknown
        }
        // Only the AI answer's own words can be line-level: the refine's listen
        // is a transcript, and Gemini's "words" are its line times.
        val lineLevel = entry.aiEngine == ENGINE_GEMINI &&
            saved.isNotEmpty() &&
            (entry.refineHeardKey != key || entry.refineHeardWords.isEmpty())
        return WordSource(saved.map { LyricsWordAlign.Heard(it.t, it.s, it.e) }, clock, lineLevel)
    }

    /** Heard words moved onto the HQ copy's clock, where [apply] puts the lines after [Timing.hq]. */
    private fun onHqClock(words: List<LyricsWordAlign.Heard>, timing: Timing): List<LyricsWordAlign.Heard> {
        val map = timing.hq?.map ?: return words
        return words.map { word ->
            val start = map.map(word.startMs)
            LyricsWordAlign.Heard(word.text, start, maxOf(start, map.map(word.endMs)))
        }
    }

    /** A refine's stored words put onto the clock now playing, for a result written after a listen. */
    private fun refineWordsOnClock(
        context: Context,
        videoId: String?,
        source: WordSource,
        timing: Timing,
    ): List<LyricsWordAlign.Heard> {
        if (source.words.isEmpty()) return emptyList()
        val playing = videoId?.let(::playingClock) ?: Clock.Unknown
        return when {
            source.clock is Clock.Unknown || playing is Clock.Unknown ->
                onHqClock(source.words, timing)
            source.clock == playing -> source.words
            else -> videoId?.let {
                conversion(context, it, source.clock, playing)?.words(source.words)
            } ?: emptyList()
        }
    }

    // ---- Clock identity and conversion ------------------------------------------------

    private fun clockOf(saved: String?): Clock = when {
        saved == CLOCK_YOUTUBE -> Clock.YouTube
        saved != null && saved.startsWith("$CLOCK_SUBSTITUTE\u0001") -> {
            val parts = saved.split('\u0001')
            if (parts.size >= 3 && parts[1].isNotBlank() && parts[2].isNotBlank()) {
                Clock.Substitute(parts[1], parts[2])
            } else {
                Clock.Unknown
            }
        }
        else -> Clock.Unknown
    }

    private fun Clock.saved(): String? = when (this) {
        Clock.Unknown -> null
        Clock.YouTube -> CLOCK_YOUTUBE
        is Clock.Substitute -> "$CLOCK_SUBSTITUTE\u0001$sourceConfigId\u0001$trackId"
    }

    /** A clock as [SongHearing] files it; null when it is not known. */
    internal fun clockToKey(clock: Clock): String? = clock.saved()

    internal fun clockFromKey(key: String?): Clock = clockOf(key)

    private fun Clock.label(): String = when (this) {
        Clock.Unknown -> "unknown"
        Clock.YouTube -> "youtube"
        is Clock.Substitute -> "alt:$sourceConfigId/$trackId"
    }

    /** The substitute [StreamChoice] recorded for [videoId], or unknown when it named no source row. */
    private fun substituteClock(videoId: String): Clock {
        val choice = StreamChoice.of(videoId) ?: return Clock.Unknown
        val source = choice.sourceConfigId ?: return Clock.Unknown
        val track = choice.trackId ?: return Clock.Unknown
        return Clock.Substitute(source, track)
    }

    /** The copy actually serving [videoId] — not the copy it may swap to later. */
    internal fun playingClock(videoId: String): Clock = when {
        StreamChoice.isSubstitute(videoId) -> substituteClock(videoId)
        // An explicit YouTube choice is the stream; a saved file only answers
        // when nothing has been chosen yet.
        StreamChoice.isYouTube(videoId) -> Clock.YouTube
        else -> {
            val meta = KoDownloadRecords.savedMetadataFor(videoId)
            clockForLocalCopy(meta?.hqSourceConfigId, meta?.hqTrackId)
        }
    }

    /** The clock of the rendition a measurement read; any substitute key is the substitute's. */
    internal fun clockForRendition(videoId: String, key: String?): Clock =
        if (key != null && AutomixAnalysisSource.isSubstituteRendition(videoId, key) &&
            !VersionAudioAligner.isYouTubeUnderAltKey(key, StreamChoice.isYouTube(videoId))
        ) {
            substituteClock(videoId)
        } else {
            Clock.YouTube
        }

    /**
     * The clock a remembered [StreamChoice] names, without asking the store.
     * Used when a choice is removed: what the playing clock was just before.
     */
    internal fun clockOfChoice(
        substituted: Boolean,
        sourceConfigId: String?,
        trackId: String?,
    ): Clock = if (!substituted) {
        Clock.YouTube
    } else if (sourceConfigId != null && trackId != null) {
        Clock.Substitute(sourceConfigId, trackId)
    } else {
        Clock.Unknown
    }

    /**
     * The clock of a local file: an HQ download carries the source pair it was
     * proved against, so it is the same substitute clock as that rendition.
     */
    internal fun clockForLocalCopy(sourceConfigId: String?, trackId: String?): Clock =
        if (sourceConfigId != null && trackId != null) {
            Clock.Substitute(sourceConfigId, trackId)
        } else {
            Clock.YouTube
        }

    /** The clock of the audio a song check listened to, from the copy it actually opened. */
    internal fun clockForSongAudio(song: Song, copy: VideoAudio.SongAudio): Clock =
        if (copy.fromFile) {
            // Captured when the data source was opened; see [VideoAudio.SongAudio].
            clockForLocalCopy(copy.localSourceConfigId, copy.localTrackId)
        } else {
            clockForRendition(song.videoId, copy.renditionKey)
        }

    private class VerifiedClock(val offsetMs: Long, val rate: Double)

    private fun verifiedClock(context: Context, videoId: String, clock: Clock.Substitute): VerifiedClock? {
        val verified = HqVerificationStore.get(
            context,
            videoId,
            clock.sourceConfigId,
            clock.trackId,
        ) ?: return null
        if (!verified.rate.isFinite() || verified.rate <= 0.0) return null
        return VerifiedClock(verified.offsetMs, verified.rate)
    }

    private class ClockConversion(val map: LyricsTimeMap, val deltaScale: Double) {
        val identity: Boolean get() = deltaScale == 1.0 && map.map(0L) == 0L

        fun delta(ms: Long): Long = (ms * deltaScale).roundToLong()

        fun words(words: List<LyricsWordAlign.Heard>): List<LyricsWordAlign.Heard> =
            if (identity) words else words.map { word ->
                val start = map.map(word.startMs)
                LyricsWordAlign.Heard(word.text, start, maxOf(start, map.map(word.endMs)))
            }

        fun savedWords(words: List<SavedWord>): List<SavedWord> =
            if (identity) words else words.map { word ->
                SavedWord(t = word.t, s = map.map(word.s), e = maxOf(map.map(word.s), map.map(word.e)))
            }
    }

    /**
     * The conversion from [from]'s clock to [to]'s, or null when there is no
     * verified copy to carry the correction across. A missing map drops the
     * correction rather than showing it on the wrong clock.
     */
    private fun conversion(
        context: Context,
        videoId: String,
        from: Clock,
        to: Clock,
    ): ClockConversion? {
        if (from == to) return ClockConversion(LyricsTimeMap.shift(0L), 1.0)
        if (from is Clock.Unknown || to is Clock.Unknown) return null
        return when {
            from == Clock.YouTube && to is Clock.Substitute -> {
                val verified = verifiedClock(context, videoId, to) ?: return null
                ClockConversion(
                    LyricsTimeMap.linear(verified.rate, verified.offsetMs),
                    verified.rate,
                )
            }
            from is Clock.Substitute && to == Clock.YouTube -> {
                val verified = verifiedClock(context, videoId, from) ?: return null
                val rate = 1.0 / verified.rate
                ClockConversion(
                    LyricsTimeMap.linear(rate, (-verified.offsetMs / verified.rate).roundToLong()),
                    rate,
                )
            }
            from is Clock.Substitute && to is Clock.Substitute -> {
                val a = verifiedClock(context, videoId, from) ?: return null
                val b = verifiedClock(context, videoId, to) ?: return null
                val rate = b.rate / a.rate
                val offset = (b.offsetMs - b.rate * a.offsetMs / a.rate).roundToLong()
                ClockConversion(LyricsTimeMap.linear(rate, offset), rate)
            }
            else -> null
        }
    }

    /** [adjustment] composed after [conversion]: its map is measured clock → playing clock. */
    private fun convertAdjustment(adjustment: Adjustment, conversion: ClockConversion): Adjustment {
        if (conversion.identity) return adjustment
        val baseMap = when (adjustment) {
            is Adjustment.Shift -> LyricsTimeMap.shift(adjustment.ms)
            is Adjustment.Mapped -> adjustment.map
        }
        val moved = composeMaps(baseMap, conversion.map)
        val rate = (adjustment as? Adjustment.Mapped)?.rate?.times(conversion.deltaScale)
            ?: conversion.deltaScale
        return Adjustment.Mapped(
            map = moved,
            byAi = adjustment.byAi,
            rate = rate,
            matchPercent = (adjustment as? Adjustment.Mapped)?.matchPercent,
        )
    }

    /** [map] followed by [after]; [after] is a verified linear map. */
    private fun composeMaps(map: LyricsTimeMap, after: LyricsTimeMap): LyricsTimeMap {
        val flat = map.flatten().chunked(2).flatMap { (src, dst) -> listOf(src, after.map(dst)) }
        return LyricsTimeMap.unflatten(
            flat,
            map.headRate * after.headRate,
            map.tailRate * after.tailRate,
        ) ?: map
    }

    private val loggedClocks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun logClocks(
        videoId: String,
        playing: Clock,
        base: Clock,
        refine: Clock,
        manual: Clock,
        converted: Int,
        dropped: Int,
    ) {
        val key = "$videoId|${playing.label()}|${base.label()}|${refine.label()}|${manual.label()}"
        if (!loggedClocks.add(key)) return
        if (loggedClocks.size > 3_000) loggedClocks.clear()
        TrackLog.i(
            "BitChord",
            "sync clock: playing=${playing.label()} base=${base.label()} " +
                "refine=${refine.label()} manual=${manual.label()} " +
                "converted=$converted dropped=$dropped",
            videoId,
        )
    }

    private const val RULE_MANUAL = "manual"
    private const val RULE_NOTHING = "nothing"

    /** The other audio source of the same row (stream ↔ file), or null for any other key. */
    private fun siblingSource(videoId: String, audioSource: String?): String? = when (audioSource) {
        audioSourceKey(videoId, localFile = true) -> audioSourceKey(videoId, localFile = false)
        audioSourceKey(videoId, localFile = false) -> audioSourceKey(videoId, localFile = true)
        else -> null
    }

    /** Last base-apply line per (song, source): logged again only when it changes. */
    private val loggedBaseApply = ConcurrentHashMap<String, String>()

    private fun logBaseApply(videoId: String, base: Adjustment?, shiftMs: Long, rule: String, source: String) {
        val name = when {
            base == null -> "none"
            base.byAi -> "ai"
            else -> "measured"
        }
        val line = "base-apply: $videoId base=$name shift=${shiftMs}ms (chosen by $rule) src=$source"
        if (loggedBaseApply.put("$videoId|$source", line) == line) return
        if (loggedBaseApply.size > 3_000) loggedBaseApply.clear()
        TrackLog.i("BitChord", line, videoId)
    }

    /**
     * Video or cover upload, by the one test the lyrics screen, the next-song
     * bundle and the cache all use (11-02: the screen read every MV and cover
     * as a song, so answers stored under the video key were never found).
     */
    private fun isVideoRow(song: Song): Boolean =
        com.music.bitchord.data.cover.CoverTitles.lyricsQuery(song).isVideo

    /** One line per (song, stored lyrics, shown lyrics): why a kept AI answer was not used. */
    private val loggedAiOtherLyrics = ConcurrentHashMap.newKeySet<String>()

    private fun logAiForOtherLyrics(videoId: String?, entry: Entry?, key: Int) {
        val answerKey = entry?.aiTextKey ?: return
        if (answerKey == key || videoId == null) return
        if (entry.ai == null && entry.aiShiftMs == null && entry.aiStarts == null) return
        if (!loggedAiOtherLyrics.add("$videoId|$answerKey|$key")) return
        if (loggedAiOtherLyrics.size > 3_000) loggedAiOtherLyrics.clear()
        TrackLog.d(TAG, "ai answer for other lyrics: $answerKey", videoId)
    }

    private fun baseOf(
        entry: Entry,
        lines: List<LyricLine>,
        measuredAllowed: Boolean = true,
        isVideo: Boolean = false,
        audioSource: String? = null,
        key: Int = textKey(lines, audioSource),
        videoId: String? = null,
        onRule: (String) -> Unit = {},
    ): Adjustment? {
        logAiForOtherLyrics(videoId, entry, key)
        val measuredAdjustment = entry.auto
            ?.takeIf { measuredAllowed && entry.autoVersion == AUTO_VERSION }
            ?.toAdjustment(byAi = false)
        val noChange = if (measuredAllowed && entry.autoVersion == AUTO_VERSION && entry.autoNoChange) {
            Adjustment.Shift(0L, byAi = false)
        } else {
            null
        }
        val ai = aiOf(entry, lines, isVideo, audioSource)
        // Asked for by hand, over a measurement the listener saw was off: Gemini's.
        val manualAi = ai.takeIf { entry.aiPreferred }
        if (manualAi != null) {
            onRule(RULE_MANUAL)
            return manualAi
        }
        // The fixed order: measurement, then a measurement that found no
        // change, then the AI answer. Kept when the heard words cannot tell
        // the candidates apart or there are too few of them.
        val measured = measuredAdjustment ?: noChange
        val defaultName = when {
            measured != null -> BaseChoicePolicy.MEASURED
            ai != null -> BaseChoicePolicy.AI
            else -> {
                onRule(RULE_NOTHING)
                return null
            }
        }
        val default = measured ?: ai ?: return null
        val heard = savedHeardWords(entry, key)
        if (heard.isEmpty()) {
            // Nothing heard yet to confirm it: a move past the widest the free
            // evidence can express is not taken on the AI's word alone, however
            // sure the answer sounds. The provider's own times stand until the
            // measurement proves the move (11-04 ekzHIouo8Q4: a stored AI answer
            // moved the sheet 11.78 s with match=100%, and the measurement that
            // landed twelve seconds later found −38 ms).
            val shift = shownShiftMs(lines, default)
            if (default.byAi && abs(shift) > RefinePolicy.MAX_WIDE_SHIFT_MS) {
                TrackLog.d(TAG, "ai shift ${shift}ms held for the measurement", videoId)
                onRule("held")
                return null
            }
            return holdIfBuried(lines, default, videoId).also {
                onRule(if (it == null) "buried" else "fixed-order")
            }
        }
        val heardStarts = BaseChoicePolicy.heardStarts(lines.map { it.text }, heard)
        val candidates = buildList {
            add(BaseChoicePolicy.Candidate(BaseChoicePolicy.NONE, lines.map { it.timeMs }))
            ai?.let { add(BaseChoicePolicy.Candidate(BaseChoicePolicy.AI, choiceStarts(lines, it))) }
            measured?.let {
                add(BaseChoicePolicy.Candidate(BaseChoicePolicy.MEASURED, choiceStarts(lines, it)))
            }
        }
        val choice = BaseChoicePolicy.choose(
            defaultName = defaultName,
            heardStarts = heardStarts,
            candidates = candidates,
            manual = entry.aiPreferred,
        )
        if (videoId != null) {
            val detail = choice.scores.joinToString(" ") { (name, score) ->
                "$name:${score.medianAbsMs}/${(score.withinRatio * 100).roundToInt()}%/${score.matched}"
            }
            TrackLog.d(
                TAG,
                "base-choice: $detail -> ${choice.name}" + (choice.reason?.let { " ($it)" } ?: ""),
                videoId,
            )
        }
        val chosen = when (choice.name) {
            BaseChoicePolicy.MEASURED -> measured
            BaseChoicePolicy.AI -> ai
            BaseChoicePolicy.NONE -> null
            else -> default
        }
        return holdIfBuried(lines, chosen, videoId).also {
            onRule(if (it == null && chosen != null) "buried" else "base-choice")
        }
    }

    /**
     * [adjustment] unless it is a measurement that puts most of the sheet
     * before the song starts — a map to another arrangement, not this sheet's
     * timing (see [BaseChoicePolicy.buriesSheet]). Null leaves the provider's
     * own times showing, which is what "no base" already means.
     */
    private fun holdIfBuried(
        lines: List<LyricLine>,
        adjustment: Adjustment?,
        videoId: String?,
    ): Adjustment? {
        if (adjustment == null || adjustment.byAi) return adjustment
        val starts = choiceStarts(lines, adjustment)
        if (!BaseChoicePolicy.buriesSheet(starts)) return adjustment
        TrackLog.d(
            TAG,
            "measured base buries ${starts.count { it != null && it <= 0L }} of " +
                "${starts.count { it != null }} lines; provider times kept",
            videoId,
        )
        return null
    }

    /** [lines]' starts as [adjustment] puts them, for the base-choice comparison. */
    private fun choiceStarts(lines: List<LyricLine>, adjustment: Adjustment): List<Long?> =
        lines.map { line ->
            when (adjustment) {
                is Adjustment.Shift -> line.timeMs + adjustment.ms
                is Adjustment.Mapped -> adjustment.map.map(line.timeMs)
            }
        }

    /**
     * The words heard for [key], whichever transcript kept them: the refine's
     * own listen first, then the AI answer's. Empty means the base can only
     * follow the fixed order.
     */
    private fun savedHeardWords(entry: Entry, key: Int): List<LyricsWordAlign.Heard> {
        val saved = when {
            entry.refineHeardKey == key && entry.refineHeardWords.isNotEmpty() -> entry.refineHeardWords
            // Only word-stamped transcripts are a judge. Gemini's "words" are its
            // own line times: scoring the AI answer against them always says the
            // AI answer is perfect, and would hand it every disagreement with a
            // measurement that was in fact right (Opus review, 11-02).
            entry.aiTextKey == key && entry.heardWords.isNotEmpty() &&
                entry.aiEngine != null && entry.aiEngine != ENGINE_GEMINI -> entry.heardWords
            else -> emptyList()
        }
        return saved.map { LyricsWordAlign.Heard(it.t, it.s, it.e) }
    }

    private fun aiOf(
        entry: Entry,
        lines: List<LyricLine>,
        isVideo: Boolean = false,
        audioSource: String? = null,
    ): Adjustment? {
        if (entry.aiTextKey != null && entry.aiTextKey == textKey(lines, audioSource)) {
            entry.ai?.toAdjustment(byAi = true)?.takeIf { aiMayApply(lines, it, isVideo) }?.let { return it }
            entry.aiShiftMs?.takeIf { LyricsAiGuard.shiftAllows(isVideo, it) }
                ?.let { return Adjustment.Shift(it, byAi = true) }
            // A ko-21/22 per-line answer: the very one that drifted. Its
            // starts go through today's fit; when they don't make a line,
            // the measurement applies instead.
            entry.aiStarts?.takeIf { it.size == lines.size }?.let { starts ->
                val sung = lines.indices.filter { lines[it].text.isNotBlank() }
                fitAi(lines, sung, sung.map { starts[it] })?.takeIf { aiMayApply(lines, it, isVideo) }?.let { return it }
            }
        }
        return null
    }

    /** The agreement and video-shift floors an AI answer must clear to be shown. */
    private fun aiMayApply(lines: List<LyricLine>, adjustment: Adjustment.Mapped, isVideo: Boolean): Boolean =
        LyricsAiGuard.allowsAiTiming(isVideo, adjustment.matchPercent, shownShiftMs(lines, adjustment))

    /**
     * What the last measurement of [videoId] made of it, in figures — which
     * recording it was measured against and how the two compared — for the
     * sync drawer, so a screenshot says why a video was or wasn't timed.
     */
    suspend fun note(context: Context, videoId: String): String? = store(context).get(videoId)?.autoNote

    /** [lines] as [timing] puts them; words, syllables and backing vocals move with their line. */
    fun apply(lines: List<LyricLine>, timing: Timing): List<LyricLine> {
        val refined = applyThroughRefine(lines, timing)
        // The per-line residual goes between the whole-song refine and the
        // listener's own correction: only lines the heard words clearly place
        // elsewhere move, and their neighbours follow or hold as
        // [LyricsPerLine] decides. Memoised per timing and lines, so the
        // display does not re-run the alignment on every frame.
        val layered = refined.retimedByLine(perLineLayer(refined, timing).shiftsMs)
        if (timing.extraMs == 0L) return layered
        return layered.retimedBy { ms -> (ms + timing.extraMs).coerceAtLeast(0L) }
    }

    /** [lines] after base, HQ and refine, before the per-line layer and the hand. */
    private fun applyThroughRefine(lines: List<LyricLine>, timing: Timing): List<LyricLine> {
        val baseMap = when (val base = timing.base) {
            null -> null
            is Adjustment.Shift -> LyricsTimeMap.shift(base.ms)
            is Adjustment.Mapped -> base.map
        }
        val hq = timing.hq?.map
        // The HQ map is the second half of the clock: YouTube song version →
        // the copy playing. The refine is a move after both — a constant, or
        // an offset and a drift — capped at ±[RefinePolicy.MAX_SHIFT_MS] even
        // where the transcript only covered the opening, or at the wide
        // window's ±[RefinePolicy.MAX_WIDE_SHIFT_MS] for an arrangement-sized
        // constant.
        val mapped = if (hq == null) {
            lines.timedBy(baseMap)
        } else {
            lines.timedBy(baseMap).timedBy(hq)
        }
        return mapped.retimedBy { ms -> (ms + timing.refineAt(ms)).coerceAtLeast(0L) }
    }

    private class PerLineMemo(
        val timing: Timing,
        val lines: List<LyricLine>,
        val layer: LyricsPerLine.Layer,
    )

    @Volatile
    private var perLineMemo: PerLineMemo? = null

    /** The memoised per-line layer for this timing, logging the one diagnostic line when it is computed. */
    private fun perLineLayer(lines: List<LyricLine>, timing: Timing): LyricsPerLine.Layer {
        val words = timing.perLine ?: return LyricsPerLine.Layer.identity(lines.size)
        perLineMemo?.let { memo ->
            if (memo.timing == timing && memo.lines == lines) return memo.layer
        }
        val layer = LyricsPerLine.compute(lines, words.words, words.wordsKey, words.currentKey)
        TrackLog.i(
            "BitChord",
            "sync perline: anchors=${layer.anchors} applied=${layer.applied} maxAbsMs=${layer.maxAbsMs}",
            timing.videoId,
        )
        perLineMemo = PerLineMemo(timing, lines, layer)
        return layer
    }

    /**
     * The YouTube → high-quality map for the stream serving [videoId], when it
     * is a substitute that was verified. Read from [StreamChoice] rather than
     * passed by every caller: the player and the lyrics screen both already
     * record and read that choice, and a caller that forgot would silently
     * show the lyrics on the wrong clock.
     */
    private suspend fun hqAdjustment(context: Context, videoId: String): Adjustment.Mapped? =
        withContext(Dispatchers.IO) {
            // Off by default: the saved HQ offset/rate was a post-ko-101
            // addition, and applying it on top of a measurement that already
            // used the HQ copy moved the same lyrics twice.
            if (!SyncSafetyPolicy.hqAdjustmentAllowed(AppSettings.syncSafetySwitches)) {
                return@withContext null
            }
            val choice = StreamChoice.of(videoId) ?: return@withContext null
            if (!StreamChoice.isSubstitute(videoId)) return@withContext null
            val sourceId = choice.sourceConfigId ?: return@withContext null
            val trackId = choice.trackId ?: return@withContext null
            val verified = HqVerificationStore.get(
                context,
                videoId,
                sourceId,
                trackId,
            ) ?: return@withContext null
            val map = HqMatchPolicy.mapFor(verified.offsetMs, verified.rate)
                ?: return@withContext null
            Adjustment.Mapped(
                map = map,
                byAi = false,
                rate = verified.rate,
                matchPercent = verified.matchPercent,
            )
        }

    /** How far [adjustment] moves the first sung line of [lines] — the figure worth showing. */
    fun shownShiftMs(lines: List<LyricLine>, adjustment: Adjustment): Long {
        val first = lines.firstOrNull { it.text.isNotBlank() } ?: lines.firstOrNull() ?: return 0L
        return when (adjustment) {
            is Adjustment.Shift -> adjustment.ms
            is Adjustment.Mapped -> adjustment.map.map(first.timeMs) - first.timeMs
        }
    }

    /** Where the first sung line sits after [Timing.base] and [Timing.hq], before refine and the hand. */
    fun baseDisplayedMs(lines: List<LyricLine>, timing: Timing): Long {
        val first = lines.firstOrNull { it.text.isNotBlank() } ?: lines.firstOrNull() ?: return 0L
        val baseMap = when (val base = timing.base) {
            null -> null
            is Adjustment.Shift -> LyricsTimeMap.shift(base.ms)
            is Adjustment.Mapped -> base.map
        }
        val afterBase = baseMap?.map(first.timeMs) ?: first.timeMs
        return timing.hq?.map?.map(afterBase) ?: afterBase
    }

    // ---- The last line-level correction ----------------------------------------------

    /**
     * Records what the words heard say about the lines already on screen: one
     * song-wide move from [RefinePolicy], for the kept entry and the one
     * `lyrics-refine:` log line. The lines themselves already carry it from
     * [timing]; this only writes it down when it changes. No request, no timer.
     */
    suspend fun refine(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        timing: Timing,
        audioSource: String? = null,
        stillCurrent: () -> Boolean = { true },
    ): RefinePolicy.Result = refineWithSnapshot(
        context,
        videoId,
        lines,
        timing,
        audioSource,
        stillCurrent,
        null,
    )

    /**
     * [refine] anchored to an entry captured before a long listen; see
     * [RefineSnapshot]. Not part of the public surface because the anchor is
     * only meaningful to a caller that suspends between the capture and here.
     */
    internal suspend fun refineWithSnapshot(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        timing: Timing,
        audioSource: String? = null,
        stillCurrent: () -> Boolean = { true },
        snapshot: RefineSnapshot? = null,
    ): RefinePolicy.Result {
        // No base is a song version shown on its lyric provider's own clock —
        // the commonest case of all. That provider can still sit a fifth of a
        // second off (vc78: Espresso -0.23 s, Viva La Vida -0.25 s, never
        // refined), so the refine runs without a base too.
        val base = timing.base
        val stored = store(context).get(videoId) ?: Entry()
        // A caller that listened for a long while before this call captures
        // the entry first ([RefineSnapshot]); its refine then computes and
        // writes against that older entry so a correction saved during the
        // listen is detected below instead of becoming the new baseline.
        val entry = snapshot?.entry ?: stored
        val key = textKey(lines, audioSource)
        val result = refineResult(context, entry, key, base, lines, timing)
        val words = refineWords(entry, key, base).words
        if (words.isEmpty()) return result
        // The snapshot was taken before the caller's long listen. If the
        // stored entry no longer matches it, another path corrected this song
        // meanwhile and this result is about facts that changed.
        if (snapshot != null && stored != snapshot.entry) {
            TrackLog.i(
                "BitChord",
                "lyrics-refine: $videoId stale result dropped (stored entry changed)",
                videoId,
            )
            return result
        }
        val appliedMs = if (result.applied) result.medianMs else null
        val appliedSlope = if (result.applied) result.slopePerMin else 0.0
        val appliedWide = if (result.applied) result.model.wide else null
        if (entry.refineMs == appliedMs && entry.refineSlopePerMin == appliedSlope &&
            entry.refineLines == result.lines && entry.refineMad == result.madMs &&
            entry.refineWide == appliedWide
        ) {
            return result
        }
        // The refine suspends on store I/O. A track switch or a newer timing
        // pass may have landed meanwhile, so do not let these stale facts
        // clear or overwrite the newer correction before publishing them.
        if (!stillCurrent()) {
            TrackLog.i(
                "BitChord",
                "lyrics-refine: $videoId stale result dropped (track changed)",
                videoId,
            )
            return result
        }
        val saved = store(context).updateIfUnchanged(videoId, entry) {
            it.copy(
                clockVersion = CLOCK_VERSION,
                refineMs = appliedMs,
                refineSlopePerMin = appliedSlope,
                refineLines = result.lines,
                refineMad = result.madMs,
                refineWide = appliedWide,
                refineClock = playingClock(videoId).saved(),
            )
        }
        if (!saved) {
            TrackLog.i(
                "BitChord",
                "lyrics-refine: $videoId stale result dropped (stored entry changed)",
                videoId,
            )
            return result
        }
        val baseLabel = when {
            base == null -> "none"
            base.byAi -> "ai"
            else -> "measured"
        }
        TrackLog.i(
            "BitChord",
            "lyrics-refine: $videoId lines=${result.lines} median=${result.medianMs}ms " +
                "mad=${result.madMs}ms slope=${result.slopePerMin.roundToLong()} " +
                "model=${when (result.model) {
                    RefinePolicy.Model.WIDE -> "wide"
                    RefinePolicy.Model.LINEAR -> "linear"
                    RefinePolicy.Model.DRIFT -> "drift"
                    RefinePolicy.Model.CONST -> "const"
                }} " +
                "applied=${if (result.applied) "y" else "n"} base=$baseLabel",
            videoId,
        )
        return result
    }

    /**
     * The saved entry a refine started from, taken before a long listen so
     * [refine] can tell whether another path saved a newer correction while
     * the words were being heard. See [refineSnapshot].
     */
    internal class RefineSnapshot internal constructor(internal val entry: Entry)

    /** Captures [videoId]'s saved entry for a later [refine]'s staleness check. */
    internal suspend fun refineSnapshot(context: Context, videoId: String): RefineSnapshot =
        RefineSnapshot(store(context).get(videoId) ?: Entry())

    /** The refine [result] for the words kept for these lyrics and sound, without writing anything. */
    private suspend fun refineResult(
        context: Context,
        entry: Entry,
        key: Int,
        base: Adjustment?,
        lines: List<LyricLine>,
        timing: Timing,
    ): RefinePolicy.Result = withContext(Dispatchers.Default) {
        val source = refineWords(entry, key, base)
        val words = refineWordsOnClock(context, timing.videoId, source, timing)
        if (words.isEmpty()) return@withContext RefinePolicy.Result.NONE
        RefinePolicy.compute(
            apply(
                lines,
                timing.copy(
                    manualMs = 0L,
                    refineMs = 0L,
                    refineSlopePerMin = 0.0,
                    perLine = null,
                ),
            ),
            words,
            lineLevelWords = source.lineLevel,
        )
    }

    /** The last sung line's time, or the last line when every one is blank. */
    private fun lastLineMs(lines: List<LyricLine>): Long =
        lines.lastOrNull { it.text.isNotBlank() }?.timeMs ?: lines.lastOrNull()?.timeMs ?: 0L

    /** How much of the song the kept refine transcript covers; old entries were the first ninety seconds. */
    private fun refineHeardSeconds(entry: Entry): Double =
        entry.refineHeardSeconds.takeIf { it > 0.0 } ?: VideoAudio.SONG_CHECK_SECONDS

    /** Whether the words a refine needs are already kept for these lyrics and this sound. */
    suspend fun refineHeardKnown(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        timing: Timing,
        audioSource: String? = null,
    ): Boolean {
        val entry = store(context).get(videoId) ?: return false
        val key = textKey(lines, audioSource)
        if (entry.refineHeardKey == key) {
            if (entry.refineHeardExtended) return true
            val heardSeconds = refineHeardSeconds(entry)
            val result = refineResult(context, entry, key, timing.base, lines, timing)
            return !RefineExtendPolicy.shouldExtend(result, heardSeconds, lastLineMs(lines))
        }
        // Only word-level transcripts serve a refine; Gemini's are line-level.
        val wordLevel = entry.aiEngine != null && entry.aiEngine != ENGINE_GEMINI
        if (!wordLevel || entry.aiTextKey != key || entry.heardWords.isEmpty()) return false
        // An answer's opening knows nothing of the lines past it: 137 of 139
        // listens stopped at 40 or 90 s, because this said "known" after them.
        return lastLineMs(lines) < entry.heardWords.maxOf { it.e } + RefineExtendPolicy.MARGIN_MS
    }

    /**
     * A measured song's first ninety seconds, heard once, so [RefinePolicy] has
     * words to compare with lines the measurement already put on the video's
     * clock. Downloaded files are read from the file; a stream from the
     * opening already on disk. Kept per lyrics and sound and reused, so a
     * replay pays nothing. Null audio or a refusal is not marked, and the next
     * playback may try again — there is no timer here.
     *
     * When that first listen could not apply and the lyrics run past it, or it
     * applied a drift it only measured to the transcript's end, the same song
     * is heard once more to its own end (or
     * [RefineExtendPolicy.MAX_EXTEND_SECONDS], when longer), so a cover's wide
     * window can see the lines the opening missed and a drift is measured over
     * more of the song. The longer transcript replaces the short one and the
     * length is kept; a failed extension is not recorded and is retried when
     * the rest of the file finishes caching (see [refineHeardKnown]'s caller)
     * or on the next playback. At most one extension per song.
     */
    suspend fun ensureRefineHeard(
        context: Context,
        videoId: String,
        song: Song,
        lines: List<LyricLine>,
        timing: Timing,
        audioSource: String? = null,
    ): Boolean {
        // Also without a base: a song version on its provider's clock is
        // refined too (see [refine]). An AI base usually came with the words that placed it, and then
        // refineHeardKnown below answers true. Answers saved before words were
        // kept have none (10-27: qQNbfxDLxq0, base=ai, stayed -0.24 s with no
        // refine at all), so they are heard once like a measured song.
        if (!AppSettings.heavyWorkAllowed || !AppSettings.aiTimingAhead.value) {
            TrackLog.d(
                TAG,
                "refine-heard skipped heavy=${AppSettings.heavyWorkAllowed} " +
                    "ahead=${AppSettings.aiTimingAhead.value}",
                videoId,
            )
            return false
        }
        if (!com.music.bitchord.data.lyrics.Deepgram.usable) {
            TrackLog.d(TAG, "refine-heard skipped: Deepgram not usable", videoId)
            return false
        }
        if (refineHeardKnown(context, videoId, lines, timing, audioSource)) return false
        val key = textKey(lines, audioSource)
        val lock = refining.getOrPut(videoId) { Mutex() }
        return lock.withLock {
            if (refineHeardKnown(context, videoId, lines, timing, audioSource)) return@withLock false
            val before = store(context).get(videoId)
            val hadWords = before?.refineHeardKey == key && before.refineHeardWords.isNotEmpty()
            var heardAnchor: RefineSnapshot? = null
            if (!hadWords) {
                // The whole song at once when it is all on disk: the ninety seconds
                // first would pay for the same sound twice.
                val whole = extendSeconds(song)
                val firstUntil = if (VideoAudio.secondsOnDisk(song) >= whole) whole else VideoAudio.SONG_CHECK_SECONDS
                val heard = hearForRefine(context, song, lines, firstUntil)
                    ?: return@withLock false
                heardAnchor = saveRefineHeard(
                    context,
                    videoId,
                    before,
                    key,
                    heard.words,
                    heard.clock,
                    heard.seconds,
                    extended = false,
                )
            }
            // Anchor the refine to the entry from before the listen, with our
            // own words write folded in. A newer correction saved during the
            // listen then makes the stored entry differ and the result is
            // dropped instead of overwriting it.
            val firstAnchor = heardAnchor ?: RefineSnapshot(before ?: Entry())
            val entry = store(context).get(videoId) ?: return@withLock false
            val heardSeconds = refineHeardSeconds(entry)
            val result = refineWithSnapshot(context, videoId, lines, timing, audioSource, snapshot = firstAnchor)
            // A newer correction landed during the first listen, so the refine
            // above dropped its result. A second listen would anchor on that
            // newer entry and then overwrite it; leave it as it is.
            if (entry != firstAnchor.entry) {
                if (!hadWords) _changes.tryEmit(videoId)
                return@withLock !hadWords
            }
            if (entry.refineHeardExtended || heardSeconds >= RefineExtendPolicy.MAX_EXTEND_SECONDS ||
                !RefineExtendPolicy.shouldExtend(result, heardSeconds, lastLineMs(lines))
            ) {
                if (!hadWords) _changes.tryEmit(videoId)
                return@withLock !hadWords
            }
            val target = extendSeconds(song)
            if (target <= heardSeconds) {
                // The song is no longer than the listen already made: nothing
                // more to hear, but it is not a failure to retry.
                store(context).update(videoId) { it.copy(refineHeardExtended = true) }
                if (!hadWords) _changes.tryEmit(videoId)
                return@withLock !hadWords
            }
            val beforeExtend = store(context).get(videoId) ?: Entry()
            val onDisk = VideoAudio.secondsOnDisk(song)
            if (!RefineExtendPolicy.hasAudioFor(target, onDisk)) {
                // Not a failure: the marker stays unset so the copy finishing
                // caching (the rendition-completion event re-asks, see
                // LyricsController) or a later play tries the longer listen
                // again.
                TrackLog.d(
                    TAG,
                    "extend deferred: ${onDisk}s on disk of ${target}s " +
                        "copy=${VersionAudioAligner.renditionToMeasure(videoId)}",
                    videoId,
                )
                if (!hadWords) _changes.tryEmit(videoId)
                return@withLock !hadWords
            }
            val heard = hearForRefine(context, song, lines, target) ?: return@withLock !hadWords
            // Same anchor, now taken before this second listen; the first
            // refine's own write is already part of [beforeExtend].
            val extendAnchor = saveRefineHeard(
                context,
                videoId,
                beforeExtend,
                key,
                heard.words,
                heard.clock,
                maxOf(target, heard.seconds),
                extended = true,
            )
            refineWithSnapshot(context, videoId, lines, timing, audioSource, snapshot = extendAnchor)
            _changes.tryEmit(videoId)
            true
        }
    }

    /**
     * Saves what a refine listen heard and returns the anchor its refine
     * compares the store against: [before] with this same save folded in.
     */
    internal suspend fun saveRefineHeard(
        context: Context,
        videoId: String,
        before: Entry?,
        key: Int,
        words: List<SavedWord>,
        clock: Clock,
        seconds: Double,
        extended: Boolean,
    ): RefineSnapshot {
        val withHeard = { start: Entry ->
            start.copy(
                clockVersion = CLOCK_VERSION,
                refineHeardKey = key,
                refineHeardWords = words,
                refineHeardClock = clock.saved(),
                refineHeardSeconds = seconds,
                refineHeardExtended = extended,
            )
        }
        store(context).update(videoId, withHeard)
        return RefineSnapshot(withHeard(before ?: Entry()))
    }

    private class RefineHeard(val words: List<SavedWord>, val clock: Clock, val seconds: Double)

    /** What a refine compares with: [SongHearing]'s kept words when they reach far enough, else a request. */
    private suspend fun hearForRefine(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
        untilSeconds: Double,
    ): RefineHeard? {
        val language = com.music.bitchord.data.lyrics.Deepgram.languageFor(
            lines.filter { it.text.isNotBlank() }.map { it.text },
        )
        val heard = hearThrough429(context, song, untilSeconds, untilSeconds, language) ?: return null
        val words = heard.words?.take(MAX_HEARD_WORDS)
            ?.map { word -> SavedWord(t = word.text, s = word.startMs, e = word.endMs) }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return RefineHeard(words, heard.clock, heard.audioMs / 1000.0)
    }

    private fun extendSeconds(song: Song): Double {
        val duration = TrackMatcher.secondsOf(song.durationText)?.toDouble()
        return if (duration != null && duration < RefineExtendPolicy.MAX_EXTEND_SECONDS) {
            duration
        } else {
            RefineExtendPolicy.MAX_EXTEND_SECONDS
        }
    }

    private val refining = ConcurrentHashMap<String, Mutex>()

    // ---- Automatic measurement ------------------------------------------------------

    /** Whether the measurement has said all it is going to for [videoId]. */
    suspend fun measured(context: Context, videoId: String): Boolean =
        store(context).get(videoId)?.let {
            it.autoVersion == AUTO_VERSION && it.autoComplete && !it.autoRecordPartial && !searchOutdated(it)
        } == true

    /**
     * Found nothing to measure against with an older way of searching: worth
     * one more search, since the newer one finds originals the old one missed.
     */
    private fun searchOutdated(entry: Entry): Boolean =
        entry.auto == null && !entry.autoNoChange && entry.searchVersion < SEARCH_VERSION

    /**
     * Whether measuring [videoId] now could tell more than last time: it never
     * has been; it was, against only the opening of the record; or only on an
     * opening of the video, and much more of it has reached the disk since.
     */
    suspend fun worthMeasuring(context: Context, videoId: String): Boolean {
        val entry = store(context).get(videoId)
        if (entry == null || entry.autoVersion != AUTO_VERSION) return true
        if (entry.autoRecordPartial || searchOutdated(entry)) return true
        if (entry.autoComplete) return false
        val copy = VersionAudioAligner.renditionToMeasure(videoId) ?: return false
        return copy.isComplete || copy.cachedPrefix >= entry.autoVideoBytes + maxOf(REMEASURE_GROWTH_BYTES, entry.autoVideoBytes / 4)
    }

    /**
     * True when the measurement is done with [videoId] and found nothing: no
     * original recording, or a video that doesn't line up with it — the case
     * the automatic AI fallback is for. A refusal on only the opening of a
     * video counts once that opening is long enough to have shown the song.
     */
    suspend fun measurementGaveUp(context: Context, videoId: String): Boolean {
        val entry = store(context).get(videoId) ?: return false
        if (entry.autoVersion != AUTO_VERSION || entry.auto != null || entry.autoNoChange || entry.autoRecordPartial) return false
        if (searchOutdated(entry)) return false
        return entry.autoComplete || entry.autoVideoSeconds >= GIVE_UP_AFTER_SECONDS
    }

    /**
     * [measurementGaveUp], or the kept transcript hears the lines further
     * from where they are shown than a narrow refine can move. A provider or
     * an accepted answer this far off is not settled timing: the automatic AI
     * fallback may still correct it.
     */
    suspend fun measurementGaveUp(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        audioSource: String? = null,
    ): Boolean = measurementGaveUp(context, videoId) || timingUnsettled(context, videoId, lines, audioSource)

    /**
     * Whether the words already heard put the current line starts beyond
     * [RefinePolicy.MAX_SHIFT_MS], with enough lines to be the song and not a
     * guess. The base is applied first so a measurement that correctly moved
     * a provider's offset is not mistaken for a disagreement.
     */
    private suspend fun timingUnsettled(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        audioSource: String?,
    ): Boolean = withContext(Dispatchers.Default) {
        // The word alignment below is a few million cells per call and this
        // decision is reached from the player's main-thread path; run it off the
        // main thread (11-04: the emulator tour hit an ANR with 38 skipped
        // frames and 1 s Davey frames right after lyrics loaded).
        val entry = store(context).get(videoId) ?: return@withContext false
        // A correction by hand is the listener's own answer; do not spend
        // another engine on top of it.
        if (entry.manualMs != null) return@withContext false
        val key = textKey(lines, audioSource)
        val heard = savedHeardWords(entry, key)
        if (heard.isEmpty()) return@withContext false
        val base = baseOf(entry, lines, audioSource = audioSource, key = key, videoId = videoId)
        val starts: List<Long?> = base?.let { choiceStarts(lines, it) } ?: lines.map { it.timeMs as Long? }
        // The kept refine already moved the lines toward these words; what is
        // left to judge is the timing the listener would actually see, so the
        // move goes through the same cap and hold as the displayed timing.
        // The hold ends with the last word heard, as it does for Timing.
        val untilMs = heard.maxOfOrNull { it.endMs } ?: Long.MAX_VALUE
        val seen = entry.refineMs?.let { refineMs ->
            // Entries saved before the model was kept: a constant past the
            // narrow cap can only be the wide window's move.
            val wide = entry.refineWide ?: (abs(refineMs) > RefinePolicy.MAX_SHIFT_MS)
            starts.map { start ->
                start?.let { it + refineMove(it, refineMs, entry.refineSlopePerMin, untilMs, wide) }
            }
        } ?: starts
        return@withContext BaseChoicePolicy.grosslyOff(
            seen,
            BaseChoicePolicy.heardStarts(lines.map { it.text }, heard),
        )
    }

    private val measuring = ConcurrentHashMap<String, Mutex>()

    /**
     * (video, original) pairs whose late-version measurement is already
     * running. The shared lookup and the timed-out retry both land at first
     * sound, and both used to start the same pair: the mutex serialised them,
     * but the second then remeasured the still-partial answer. One pair, one
     * measurement at a time.
     */
    private val lateMeasurements = MeasurementFlights()

    /**
     * Whether measuring [videoId] now is still wanted: it is the song playing
     * or the one prepared after it. A measurement for any other row gives up
     * before it takes [AudioDecoder]'s decode gate.
     */
    internal fun measurementWanted(videoId: String, currentId: String?, nextId: String?): Boolean =
        videoId == currentId || videoId == nextId

    /**
     * Measures how [video] runs against its original recording, on as much of
     * the video as is on disk, and keeps the answer. The record is searched
     * for under [names] — (title, artist), most trusted first, see
     * [RecordingPick] — and [videoMs] is the video's length, which helps tell
     * the record from other songs of the same name. The lyrics screen, the
     * lock screen and the next-song preparation may all ask at once; they
     * share one measurement.
     *
     * Fast first, then right: the candidates are tried at once, each first
     * against the opening of its recording the moment that much has arrived
     * — a first answer is kept and shown then — and again against all of it.
     */
    suspend fun measure(
        context: Context,
        video: Song,
        names: List<Pair<String, String>>,
        videoMs: Long,
        /**
         * The catalogue release already known for [video] (its ATV). It is
         * measured first and the search is skipped: YouTube itself paired the
         * two, so there is nothing better to look for.
         */
        preferredOriginal: Song? = null,
        /**
         * Wait on the cache's own write signal rather than polling the disk.
         * The next row is not playing, so its opening arrives from read-ahead
         * (or the player buffering ahead) at unpredictable moments; a poll
         * would wake the CPU for a track nobody is listening to yet.
         */
        awaitCacheSignal: Boolean = false,
        /**
         * Run the pass in the caller's job rather than [measureScope]. The
         * next-row bundle uses this so replacing that row cancels the
         * measurement with it; the screen and the late-version path keep the
         * app-lifetime behaviour so their answer is not lost when a screen
         * closes.
         */
        cancelWithCaller: Boolean = false,
        /** The sung lines, when the caller has them, for the lateness sample. */
        lines: List<LyricLine>? = null,
        /**
         * Whether the answer is still worth having. A measurement runs on
         * [measureScope], so a screen closing or the track changing does not
         * cancel it; without this, a decode for a song that had already left
         * kept the one decode slot (11-02 sync-g: four accepted measurements
         * of a song that ended 3½ minutes earlier).
         */
        wanted: () -> Boolean = {
            measurementWanted(
                video.videoId,
                DiagnosticsState.currentTrackId,
                DiagnosticsState.nextTrackId,
            )
        },
        /**
         * The recording a late-version event is measuring against. With it the
         * pass is not gated by the generic [worthMeasuring] (a complete answer
         * from a guessed original must not block the authoritative one); it
         * stops only when an answer for this same pair is already kept. The
         * check runs again under the per-video lock, so an ordinary pass that
         * stores its answer while this one waits is reused instead of repeated.
         */
        lateOriginalId: String? = null,
    ) {
        // The song version already resolved for this row. Measuring against
        // the right recording first is what stops a karaoke arrangement or a
        // namesake by the same artist from being taken as the reference.
        val preferred = preferredOriginal
            ?.takeIf { it.videoId.isNotBlank() && it.videoId != video.videoId }
            ?: YtMusicRepository.cachedSongVersionFor(video)
                ?.takeIf { it.videoId.isNotBlank() && it.videoId != video.videoId }
        val lock = measuring.getOrPut(video.videoId) { Mutex() }
        // Off the caller's thread — the lyrics screen asks from the main one —
        // since the kept records are read from disk along the way. Normally
        // also in [measureScope], so the caller going away doesn't abandon it;
        // the next-row bundle opts into its own job instead.
        suspend fun runMeasurement() {
            lock.withLock {
                if (!wanted()) return@withLock
                if (lateOriginalId != null) {
                    if (measurementAnswerFor(store(context).get(video.videoId), lateOriginalId)) {
                        return@withLock
                    }
                } else if (!worthMeasuring(context, video.videoId)) {
                    return@withLock
                }
                val outcome = try {
                    measureFresh(context, video, names, videoMs, preferred, awaitCacheSignal, wanted, lateOriginalId != null) { early ->
                        // A quick answer only where there is none yet: a second pass
                        // (more of the video has arrived) must not trade an answer from
                        // the whole record for one from its opening, even for a minute.
                        // An answer from a different original is not that answer, so the
                        // authoritative late pass may replace it.
                        val had = store(context).get(video.videoId)
                        val sameOriginal = had?.autoVersion == AUTO_VERSION && had.autoOriginal != null &&
                            had.autoOriginal == early.originalId
                        if (!sameOriginal) {
                            keep(context, video.videoId, early, lines)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    TrackLog.w(TAG, "measurement failed: ${e.message}", video.videoId)
                    return@withLock
                }
                // Only a finished measurement is remembered: a dropped connection or
                // a decoder that wouldn't open is worth trying again, a refusal is
                // not (until more of the video, or of the record, is there to look at).
                if (outcome !is Measured.Done) return@withLock
                keep(context, video.videoId, outcome, lines)
            }
        }
        if (cancelWithCaller) {
            withContext(Dispatchers.IO) { runMeasurement() }
        } else {
            measureScope.async { runMeasurement() }.await()
        }
    }

    /**
     * A music video whose song version was found only after its first sound
     * has already started. The recording is not swapped — that rule stands —
     * but its lyrics can now be measured against the right release instead of
     * being left unaligned.
     */
    suspend fun measureLateVersion(context: Context, video: Song, original: Song, videoMs: Long) {
        if (original.videoId.isBlank() || original.videoId == video.videoId) return
        // An answer already kept for this exact (video, original) pair is
        // reused: the late-version event must not buy the same measurement
        // again. An answer from another original does not count — this event
        // is the authoritative pairing. A partial answer is an answer; the
        // ordinary paths still extend it while the song is playing, but
        // nothing starts one for a song that has gone.
        if (measurementAnswerFor(store(context).get(video.videoId), original.videoId)) return
        val pair = "${video.videoId}\u0000${original.videoId}"
        if (!lateMeasurements.begin(pair)) return
        try {
            TrackLog.i(
                "BitChord",
                "lyrics-sync: ${video.videoId} late version ${original.videoId} -> measuring",
                video.videoId,
            )
            // With AI timing off, the measurement is the only thing that could
            // put the release's sheet on this video's clock, and it has no
            // words to work from. The video's own captions are timed to the
            // recording that is playing, so the screen is asked to re-choose
            // its sheet from them once.
            if (!AppSettings.autoLyricsTiming.value) _lateSheetWanted.tryEmit(video.videoId)
            measure(
                context,
                video,
                listOf(video.title to video.artist),
                videoMs,
                preferredOriginal = original,
                lateOriginalId = original.videoId,
            )
        } finally {
            lateMeasurements.end(pair)
        }
    }

    /**
     * Whether [entry] already holds a measurement made against [originalId].
     *
     * A record from before the original was kept (null) has unknown
     * provenance, so it is measured once more rather than reused for a pair it
     * may not belong to.
     */
    internal fun measurementAnswerFor(entry: Entry?, originalId: String): Boolean =
        entry != null && hasAutoAnswer(entry) && !searchOutdated(entry) &&
            entry.autoOriginal == originalId

    /** A chroma measurement (even a partial one) or a "no shift needed" finding is kept. */
    private fun hasAutoAnswer(entry: Entry?): Boolean =
        entry != null && entry.autoVersion == AUTO_VERSION && (entry.auto != null || entry.autoNoChange)

    /** Pairs whose late-version measurement is running; see [lateMeasurements]. */
    internal class MeasurementFlights {
        private val running = ConcurrentHashMap.newKeySet<String>()

        /** True for the caller that now owns [key]; false while another one is running. */
        fun begin(key: String): Boolean = running.add(key)

        fun end(key: String) {
            running.remove(key)
        }
    }

    private suspend fun keep(
        context: Context,
        videoId: String,
        done: Measured.Done,
        lines: List<LyricLine>? = null,
    ) {
        store(context).update(videoId) {
            // A pass with no fresh answer keeps the earlier measurement; its
            // clock must stay with it rather than becoming unknown.
            val keepEarlier = done.result == null && !done.noChange && it.autoVersion == AUTO_VERSION
            it.copy(
                clockVersion = CLOCK_VERSION,
                autoVersion = AUTO_VERSION,
                auto = autoAfter(
                    previous = it.auto,
                    previousCurrent = it.autoVersion == AUTO_VERSION,
                    fresh = done.result?.let(::savedOf),
                    noChange = done.noChange,
                ),
                autoClock = if (keepEarlier) it.autoClock else done.clock.saved(),
                autoOriginal = if (keepEarlier) it.autoOriginal else done.originalId,
                autoComplete = done.complete,
                autoRecordPartial = done.recordPartial,
                autoNoChange = done.noChange,
                autoVideoBytes = done.videoBytes,
                autoVideoSeconds = done.videoSeconds,
                autoNote = done.note,
                searchVersion = SEARCH_VERSION,
                biasSampled = false,
            )
        }
        sampleBias(context, videoId, lines, lines?.let { textKey(it) })
        _changes.tryEmit(videoId)
    }

    /**
     * The measurement to keep after a pass ends with [fresh].
     *
     * A pass that could not line the whole video up (no result, and not "the
     * two are the same") says nothing against the answer an earlier pass found:
     * on 10-26 FRIENDS (ZjZRLy3A3QA) had a +199 ms measurement from the
     * record's opening, the full pass then came back empty, the null replaced
     * it and the lyrics fell to the AI answer, 0.40 s early on every line.
     * Only a new result, or a finding that no shift is needed, replaces it.
     */
    internal fun <T> autoAfter(previous: T?, previousCurrent: Boolean, fresh: T?, noChange: Boolean): T? =
        fresh ?: previous.takeIf { previousCurrent && !noChange }

    internal sealed interface Measured {
        data class Done(
            val result: ChromaAlignment.Result?,
            val complete: Boolean,
            val videoBytes: Long = 0L,
            val videoSeconds: Double = 0.0,
            /** Measured, and the video already keeps the record's time. */
            val noChange: Boolean = false,
            /** What it was measured against and how that went, for the drawer. */
            val note: String? = null,
            /** Measured against only the opening of the record: worth doing again once all of it is in. */
            val recordPartial: Boolean = false,
            /** The audio clock the rendition actually read was on. */
            val clock: Clock = Clock.Unknown,
            /** The recording this answer was measured against, for reuse by the late-version path. */
            val originalId: String? = null,
        ) : Measured

        data object Retry : Measured
    }

    /** The video's own upload is the record: it is always on YouTube's clock. */
    internal fun originalNoChangeMeasurement(originalId: String? = null): Measured.Done = Measured.Done(
        null,
        complete = true,
        noChange = true,
        note = "비디오가 원곡과 같음",
        clock = Clock.YouTube,
        originalId = originalId,
    )

    /** How one candidate recording fared. */
    private class Tried(
        val original: Song,
        val result: ChromaAlignment.Result?,
        /** The record's audio came down at all. */
        val reached: Boolean,
        /** Only the opening of it did, in the time given. */
        val partial: Boolean,
        val note: String,
    )

    private suspend fun measureFresh(
        context: Context,
        video: Song,
        names: List<Pair<String, String>>,
        videoMs: Long,
        preferredOriginal: Song?,
        awaitCacheSignal: Boolean,
        wanted: () -> Boolean,
        /** The row's song version arrived after its first sound; see [awaitVideoOpening]. */
        lateVersion: Boolean = false,
        early: suspend (Measured.Done) -> Unit,
    ): Measured {
        val began = System.currentTimeMillis()
        val times = StringBuilder()
        fun lap(label: String, since: Long): Long {
            val now = System.currentTimeMillis()
            if (times.isNotEmpty()) times.append(" · ")
            times.append(label).append(' ').append(seconds(now - since))
            return now
        }
        // The song version pair YouTube already gave us is the reference; only
        // when there is none is a recording searched for. Searched once per
        // video: later passes over the same video ask again only if the first
        // search found nothing.
        val candidates = preferredOriginal?.let { listOf(it) }
            ?: synchronized(picks) { picks[video.videoId] }
            ?: RecordingPick.find(names, videoMs, max = MAX_CANDIDATES).also { found ->
                if (found.isNotEmpty()) synchronized(picks) {
                    picks[video.videoId] = found
                    while (picks.size > PICKS_KEPT) picks.remove(picks.keys.first())
                }
            }
        var mark = lap("검색", began)
        if (candidates.isEmpty()) {
            TrackLog.d(TAG, "no original recording found for $names", video.videoId)
            return Measured.Done(null, complete = true, note = "원곡을 찾지 못함 ($times)")
        }
        // The video is the record itself: nothing to move.
        if (candidates.any { it.videoId == video.videoId }) {
            CoverOriginals.remember(video.videoId, candidates.first { it.videoId == video.videoId })
            return originalNoChangeMeasurement(video.videoId)
        }

        // The records start coming down now, while the video's opening is
        // still on its way, rather than once it has arrived.
        //
        // Not in low power: this is a background fetch of every candidate's
        // opening, and the measurement itself still reads what the player is
        // already streaming. It is picked up again once power saving is off.
        if (AppSettings.heavyWorkAllowed) {
            candidates
                .filter { it.localUri == null && kept(context, it.videoId) == null }
                .forEach {
                    AudioCache.keepForTiming(it.videoId)
                    AudioCache.requestAnalysisHead(Uri.parse(AutomixAnalysisSource.opusUri(it.videoId)))
                }
        }
        // The video too: when it is the next song being prepared, nothing plays it yet.
        AudioCache.keepForTiming(video.videoId)

        // The video, as much as has arrived (it is usually the track playing,
        // and the player is the one fetching it), read once for every candidate
        // and, on later passes, only from where the last one stopped.
        val copy = if (awaitCacheSignal) {
            awaitNextVideoOpening(video)
        } else {
            awaitVideoOpening(video, lateVersion)
        } ?: run {
            // Saying nothing here is what made the 11-05 MV rows unreadable:
            // the late answer was measured once, its opening was not on disk
            // yet, and the pass ended without a line to show for it.
            TrackLog.i(TAG, "lyrics-sync: no video audio yet; measurement dropped", video.videoId)
            return Measured.Retry
        }
        mark = lap("비디오 대기", mark)
        // Read from the rendition actually opened, not from what is playing now:
        // after a mid-song swap the YouTube copy may already be gone.
        val measurementClock = clockForRendition(video.videoId, copy.key)
        val complete = copy.isComplete || video.localUri != null
        val job = currentCoroutineContext()[Job]
        // The video's opening first: decoding all of it before any answer was the
        // slowest step (two minutes on a slow device). The rest is read on in the
        // background — from where the opening stopped — for the whole-record pass.
        val opening = runInterruptible(analysis) { videoFeatures(context, video, copy.key, job, VIDEO_OPENING_SECONDS, wanted) }
            ?: return Measured.Retry
        lap("비디오 앞부분 분석", mark)
        val openingIsAll = opening.durationSeconds < VIDEO_OPENING_SECONDS - 5
        val earlyNote = "비디오 앞 ${opening.durationSeconds.roundToInt()}초"

        // Every candidate at once; the first to line up on its opening is kept
        // and shown straight away, while all of them go on to the whole record.
        val earlyGate = Mutex()
        var earlySent = false
        val (tried, clip) = coroutineScope {
            val full = async(analysis) {
                if (openingIsAll) opening else runInterruptible { videoFeatures(context, video, copy.key, job, MAX_SECONDS, wanted) } ?: opening
            }
            candidates.map { original ->
                async {
                    tryRecording(context, original, video, opening, openingIsAll && complete, full, complete, job, wanted) { firstResult, firstNote ->
                        earlyGate.withLock {
                            if (earlySent) return@withLock
                            earlySent = true
                            CoverOriginals.remember(video.videoId, original)
                            early(
                                Measured.Done(
                                    firstResult, complete = false, videoBytes = copy.cachedPrefix,
                                    videoSeconds = opening.durationSeconds, recordPartial = true,
                                    note = "$firstNote\n$times · $earlyNote (원곡 앞부분으로 먼저 맞춤)",
                                    clock = measurementClock,
                                    originalId = original.videoId,
                                ),
                            )
                        }
                    }
                }
            }.awaitAll() to full.await()
        }
        val videoNote = "비디오 ${clip.durationSeconds.roundToInt()}초" + if (complete) "" else " (받는 중)"
        val note = (tried.map { it.note } + "$times · $videoNote · 전체 ${seconds(System.currentTimeMillis() - began)}")
            .joinToString("\n")
        // Nothing to measure against came down: worth another go later.
        if (tried.none { it.reached }) return Measured.Retry
        val best = tried.filter { it.result != null }.minByOrNull { it.result!!.chance }
        // Lined up note for note: this is the record, so the cover is shown under its name.
        best?.let { CoverOriginals.remember(video.videoId, it.original) }
        val recordPartial = best?.partial ?: tried.any { it.partial }
        val found = best?.result
            ?: return Measured.Done(
                null,
                complete,
                copy.cachedPrefix,
                clip.durationSeconds,
                note = note,
                recordPartial = recordPartial,
                clock = measurementClock,
            )
        val same = found.linear && found.rate == 1.0 && abs(found.map.map(0L)) < MIN_SHIFT_MS
        return Measured.Done(
            found.takeUnless { same }, complete, copy.cachedPrefix, clip.durationSeconds,
            noChange = same, note = note, recordPartial = recordPartial,
            clock = measurementClock, originalId = best.original.videoId,
        )
    }

    /**
     * [original] against the video: its opening against the video's [opening]
     * first (handing a match to [first]), then all of it against the [full] video.
     */
    private suspend fun tryRecording(
        context: Context,
        original: Song,
        video: Song,
        /** The video's opening — what the first, quick answer is made from. */
        opening: ChromaAlignment.Features,
        openingComplete: Boolean,
        /** All of the video that is on disk, read on in the background. */
        full: kotlinx.coroutines.Deferred<ChromaAlignment.Features>,
        complete: Boolean,
        job: Job?,
        wanted: () -> Boolean,
        first: suspend (ChromaAlignment.Result, String) -> Unit,
    ): Tried {
        val began = System.currentTimeMillis()
        val name = "'${original.title}' / '${original.artist}'"
        suspend fun aligned(record: ChromaAlignment.Features, whole: Boolean = true): Pair<ChromaAlignment.Result?, String> {
            val clip = if (whole) full.await() else opening
            return withContext(analysis) {
                val result = ChromaAlignment.align(record, clip, videoComplete = if (whole) complete else openingComplete)
                result to ChromaAlignment.lastVerdict
            }
        }
        fun log(verdict: String, record: ChromaAlignment.Features, stage: String) = TrackLog.d(
            TAG,
            "chroma $name (${original.videoId}) -> ${video.videoId} [$stage]: $verdict " +
                "(record ${record.durationSeconds.roundToInt()} s" + (if (complete) "" else ", video so far") + ")",
            video.videoId,
        )

        kept(context, original.videoId)?.let { record ->
            // A record already known: an answer from the video's opening at once, then the whole.
            aligned(record, whole = false).first?.let { quick -> first(quick, "$name: 저장된 원곡, 비디오 앞부분") }
            val (result, verdict) = aligned(record)
            log(verdict, record, "kept")
            return Tried(original, result, reached = true, partial = false, note = "$name: $verdict (저장된 원곡, ${seconds(System.currentTimeMillis() - began)})")
        }
        if (original.localUri == null) {
            AudioCache.keepForTiming(original.videoId)
            val uri = Uri.parse(AutomixAnalysisSource.opusUri(original.videoId))
            val head = withTimeoutOrNull(HEAD_TIMEOUT_MS + 2_000L) { AudioCache.awaitAnalysisHead(uri, HEAD_TIMEOUT_MS) } == true
            if (!head) return Tried(original, null, reached = false, partial = false, note = "$name: 원곡을 받지 못함")
            val headAt = System.currentTimeMillis()
            val alreadyWhole = VersionAudioAligner.renditionToMeasure(original.videoId)?.isComplete == true
            run {
                // The opening first: most of the time it is all the answer needs.
                // Even when the whole record is already on disk (it downloads in
                // seconds) — decoding all of it before showing anything took
                // minutes on a slow device, long enough for the song to end and the
                // measurement to be dropped; its first ~70 s decode in a quarter of
                // the time, and the rest only refines the answer.
                val until = if (alreadyWhole) OPENING_SECONDS else MAX_SECONDS
                runInterruptible(analysis) { recordFeatures(context, original, job, until, wanted) }?.let { start ->
                    val (result, verdict) = aligned(start, whole = false)
                    log(verdict, start, "opening")
                    if (result != null) {
                        first(result, "$name: $verdict (원곡 앞 ${start.durationSeconds.roundToInt()}초, ${seconds(System.currentTimeMillis() - began)})")
                    }
                }
                withTimeoutOrNull(RECORD_TIMEOUT_MS + 2_000L) { AudioCache.awaitAnalysisTrack(uri, RECORD_TIMEOUT_MS) }
            }
            val downloaded = System.currentTimeMillis()
            val record = runInterruptible(analysis) { recordFeatures(context, original, job, wanted = wanted) }
                ?: return Tried(original, null, reached = false, partial = false, note = "$name: 원곡을 읽지 못함")
            val whole = VersionAudioAligner.renditionToMeasure(original.videoId)?.isComplete == true
            if (whole) keep(context, original.videoId, record)
            val read = System.currentTimeMillis()
            val (result, verdict) = aligned(record)
            log(verdict, record, if (whole) "whole" else "part")
            val note = "$name: $verdict (원곡 첫 부분 ${seconds(headAt - began)} · 나머지 ${seconds(downloaded - headAt)} · " +
                "분석 ${seconds(read - downloaded)} · 정렬 ${seconds(System.currentTimeMillis() - read)}" +
                (if (whole) "" else " · 원곡 일부만") + ")"
            return Tried(original, result, reached = true, partial = !whole, note = note)
        }
        val record = runInterruptible(analysis) { recordFeatures(context, original, job, wanted = wanted) }
            ?: return Tried(original, null, reached = false, partial = false, note = "$name: 원곡을 읽지 못함")
        keep(context, original.videoId, record)
        val (result, verdict) = aligned(record)
        log(verdict, record, "download")
        return Tried(original, result, reached = true, partial = false, note = "$name: $verdict")
    }

    private fun seconds(ms: Long): String = String.format(java.util.Locale.ROOT, "%.1f초", ms / 1000.0)

    /**
     * Waits (a while) for enough of [video]'s audio to be on disk to say
     * something — about forty seconds of it, or all of it — and returns the
     * copy to read. Asks for the opening itself: an audio row's arrives from
     * the player's own stream, but a video row's is written only because
     * something asked for it.
     *
     * [lateVersion] gives a row whose song version arrived after its first
     * sound the longer budget in [VideoAudio.measurementOpeningWaitMs].
     */
    private suspend fun awaitVideoOpening(video: Song, lateVersion: Boolean = false): AudioCache.Rendition? {
        // A downloaded file is already the whole recording: there is no cache
        // rendition to wait for and [VersionAudioAligner.openDataSource] reads
        // it directly.
        if (video.localUri != null) {
            return AudioCache.Rendition(key = "local:${video.videoId}", contentLength = 0L, cachedPrefix = 0L)
        }
        fun readyCopy(): AudioCache.Rendition? =
            VersionAudioAligner.renditionToMeasure(video.videoId)
                ?.takeIf { it.isComplete || it.cachedPrefix >= MIN_VIDEO_BYTES }
        readyCopy()?.let { return it }
        // A video row plays the video stream, so nothing is fetching its audio
        // and waiting on the cache alone waits for bytes nobody asked for. Ask
        // for the opening first, as the reference recording below and the song
        // check already do; the event wait under it stays as the fallback.
        AudioCache.keepForTiming(video.videoId)
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(video.videoId))
        if (AudioCache.awaitAnalysisHead(opus, VideoAudio.measurementOpeningWaitMs(lateVersion))) {
            readyCopy()?.let { return it }
        }
        // Event-driven: wake when the player opens the next stream for this
        // video, or when playback has read another chunk of it (more bytes are
        // on disk to measure), never on a timer. The deadline is the same bound
        // as before; a pass that never sees the opening simply gives up.
        return withTimeoutOrNull(VIDEO_TIMEOUT_MS) {
            AudioCache.served
                // Subscribe first, then look at what is already on disk. The
                // check used to run before subscribing, so bytes that landed
                // in between were missed and the pass slept until the timeout.
                // onSubscription runs once the subscription is live and wakes
                // the collector for bytes that are already there.
                .onSubscription { readyCopy()?.let { emit(video.videoId) } }
                .filter { it == video.videoId }
                .mapNotNull { readyCopy() }
                .first()
        }
    }

    /**
     * The same wait as [awaitVideoOpening], for a row that is not playing.
     *
     * A playing track's bytes arrive through the player, which is why the
     * ordinary path may look at the disk on a short cadence. The next row has
     * no player: its opening is written by read-ahead, the player buffering
     * ahead, or an analysis head, so this suspends on the cache's span-added
     * signal and re-checks only when something was actually written. No timer,
     * no delay loop; the same [VIDEO_TIMEOUT_MS] bounds the wait.
     */
    private suspend fun awaitNextVideoOpening(video: Song): AudioCache.Rendition? {
        if (video.localUri != null) {
            return AudioCache.Rendition(key = "local:${video.videoId}", contentLength = 0L, cachedPrefix = 0L)
        }
        val deadline = SystemClock.elapsedRealtime() + VIDEO_TIMEOUT_MS
        while (true) {
            val copy = VersionAudioAligner.renditionToMeasure(video.videoId)
            if (copy != null && (copy.isComplete || copy.cachedPrefix >= MIN_VIDEO_BYTES)) return copy
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0L) return null
            if (!AudioCache.awaitRenditionPrefix(video.videoId, MIN_VIDEO_BYTES, left)) return null
        }
    }

    // ---- Recordings' profiles, kept ---------------------------------------------------

    /**
     * A recording's profiles, from memory or disk: a song has many covers, and
     * measuring each against a record already read costs nothing but the
     * alignment. Kept on disk at about 30 kB a song.
     */
    private fun kept(context: Context, videoId: String): ChromaAlignment.Features? {
        synchronized(recordCache) { recordCache[videoId] }?.let { return it }
        val file = recordFile(context, videoId)
        if (!file.exists()) return null
        val features = runCatching { ChromaAlignment.Features.fromBytes(file.readBytes()) }.getOrNull() ?: run {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        remember(videoId, features)
        return features
    }

    private fun keep(context: Context, videoId: String, features: ChromaAlignment.Features) {
        remember(videoId, features)
        runCatching {
            val dir = recordFile(context, videoId).parentFile ?: return
            dir.mkdirs()
            val temporary = File(dir, "$videoId.tmp")
            temporary.writeBytes(features.toBytes())
            temporary.renameTo(File(dir, "$videoId.bin"))
            val files = dir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty()
            if (files.size > RECORDS_ON_DISK) {
                files.sortedBy { it.lastModified() }.take(files.size - RECORDS_ON_DISK).forEach { it.delete() }
            }
        }
    }

    private fun remember(videoId: String, features: ChromaAlignment.Features) = synchronized(recordCache) {
        recordCache.remove(videoId)
        recordCache[videoId] = features
        while (recordCache.size > RECORDS_KEPT) recordCache.remove(recordCache.keys.first())
    }

    private fun recordFile(context: Context, videoId: String): File =
        File(File(context.applicationContext.filesDir, "chroma_records"), "$videoId.bin")

    private val recordCache = LinkedHashMap<String, ChromaAlignment.Features>()
    private const val RECORDS_KEPT = 4
    private const val RECORDS_ON_DISK = 1_000

    // ---- A video, read as it arrives ---------------------------------------------------

    /** How far a video has been read, so the next pass reads only what came after. */
    private class VideoReading(
        val copyKey: String,
        val extractor: ChromaAlignment.Extractor,
        val rate: Int,
        val startSeconds: Double,
        var untilSeconds: Double,
    )

    private val videoReadings = LinkedHashMap<String, VideoReading>()

    /**
     * [video]'s profiles: everything on disk, read only from where the last
     * pass stopped (the copy being the same one). A copy the player swapped
     * for another is read again from the start.
     */
    private fun videoFeatures(
        context: Context,
        video: Song,
        copyKey: String,
        job: Job?,
        /** Read no further than this (seconds into the audio); later calls carry on from there. */
        untilSeconds: Double = MAX_SECONDS,
        wanted: () -> Boolean = { true },
    ): ChromaAlignment.Features? =
        // One reader per recording at a time: two covers of one song measured
        // at once may both be reading its record.
        synchronized(readingLocks.getOrPut(video.videoId) { Any() }) {
            readFeatures(context, video, copyKey, job, untilSeconds, wanted)
        }

    private val readingLocks = ConcurrentHashMap<String, Any>()

    /**
     * Where the decoding and the alignment run: a few threads at background
     * priority, so a measurement — the song starting, the next one being
     * prepared — never takes the CPU from scrolling and animation.
     */
    private val analysis = java.util.concurrent.Executors.newFixedThreadPool(3) { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "lyrics-timing").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private fun readFeatures(
        context: Context,
        video: Song,
        copyKey: String,
        job: Job?,
        untilSeconds: Double = MAX_SECONDS,
        wanted: () -> Boolean = { true },
        restartedStaleReading: Boolean = false,
    ): ChromaAlignment.Features? {
        if (!PowerState.screenOn || !wanted()) return null
        var reading = synchronized(videoReadings) { videoReadings[video.videoId] }?.takeIf { it.copyKey == copyKey }
        val from = reading?.untilSeconds ?: 0.0
        // Already read this far (an opening pass asked for less than is there): nothing new to decode.
        if (reading != null && from >= untilSeconds) {
            return reading.extractor.finish(startSeconds = reading.startSeconds).takeIf { it.frames > 0 }
        }
        val source = VersionAudioAligner.openDataSource(context, video) ?: return null
        val decoded = try {
            AudioDecoder.decodeMonoStream(
                source,
                from,
                minOf(MAX_SECONDS, untilSeconds),
                abort = { job?.isActive == false || !PowerState.screenOn || !wanted() },
                label = "lyrics-timing",
            ) { chunk, sampleRate, at ->
                val r = reading ?: VideoReading(copyKey, ChromaAlignment.Extractor(sampleRate), sampleRate, at, at)
                    .also { reading = it }
                if (sampleRate != r.rate) return@decodeMonoStream
                // The decoder starts at the nearest point it can seek to, which
                // may be before where the last pass stopped: skip what is in.
                val skip = ((r.untilSeconds - at) * sampleRate).roundToInt().coerceIn(0, chunk.size)
                if (skip < chunk.size) {
                    r.extractor.push(if (skip == 0) chunk else chunk.copyOfRange(skip, chunk.size))
                    r.untilSeconds = maxOf(r.untilSeconds, at + chunk.size.toDouble() / sampleRate)
                }
            }
        } finally {
            runCatching { source.close() }
        }
        // A kept read position can outlive the bytes it described: the entry
        // was evicted, rewritten, or only ever held a prefix. Seeking to it then
        // finds no sample at all ("no-first-sample", 11-02 sync-g x6). Start
        // this recording over once rather than lose the measurement. Any other
        // decode failure (codec, gate, aborted pass) keeps the position: reading
        // the whole prefix again would only cost battery.
        val failure = if (decoded == null) AudioDecoder.takeLastFailure() else null
        if (decoded == null && staleReadingNeedsRestart(from, failure, restartedStaleReading) &&
            job?.isActive != false && PowerState.screenOn && wanted()
        ) {
            synchronized(videoReadings) { videoReadings.remove(video.videoId) }
            return readFeatures(context, video, copyKey, job, untilSeconds, wanted, restartedStaleReading = true)
        }
        if (job?.isActive == false) return null
        // The screen going off aborts the decoder mid-read; what is in
        // [videoReadings] is a prefix, not a measurement, and must not be
        // published as one. The next screen-on pass resumes from it.
        if (!PowerState.screenOn) return null
        val done = reading ?: return null
        synchronized(videoReadings) {
            videoReadings.remove(video.videoId)
            videoReadings[video.videoId] = done
            while (videoReadings.size > VIDEOS_KEPT) videoReadings.remove(videoReadings.keys.first())
        }
        return done.extractor.finish(startSeconds = done.startSeconds).takeIf { it.frames > 0 }
    }

    /**
     * Whether a failed read should be restarted from zero. Only a stale
     * position ("no-first-sample") on a read that had already advanced is
     * restarted, and only once.
     */
    internal fun staleReadingNeedsRestart(from: Double, failure: String?, alreadyRestarted: Boolean): Boolean =
        !alreadyRestarted && from > 0.0 && failure == AudioDecoder.FAILURE_NO_FIRST_SAMPLE

    /**
     * A record's profiles read the same way as a video's: its opening first,
     * then only what came after it, rather than the opening decoded twice.
     */
    private fun recordFeatures(
        context: Context,
        original: Song,
        job: Job?,
        untilSeconds: Double = MAX_SECONDS,
        wanted: () -> Boolean = { true },
    ): ChromaAlignment.Features? =
        videoFeatures(
            context,
            original,
            VersionAudioAligner.renditionToMeasure(original.videoId)?.key ?: "record",
            job,
            untilSeconds,
            wanted,
        )

    /** Readings kept: the video playing, the next one, and two records each. */
    private const val VIDEOS_KEPT = 6

    /** The recordings found for each video lately. */
    private val picks = LinkedHashMap<String, List<Song>>()
    private const val PICKS_KEPT = 16

    /** Recordings tried against a video at once: a wrong guess costs a download, not a wrong timing. */
    private const val MAX_CANDIDATES = 2

    /** The opening of a record: this long to arrive. */
    private const val HEAD_TIMEOUT_MS = 25_000L

    /** The rest of it: this much longer. */
    private const val RECORD_TIMEOUT_MS = 60_000L

    /** The video: this long for an opening worth measuring to arrive. */
    private const val VIDEO_TIMEOUT_MS = 30_000L

    /**
     * About forty seconds of a YouTube Opus stream: enough to get past an
     * intro and into the song, and within what the player buffers ahead.
     */
    private const val MIN_VIDEO_BYTES = AudioCache.MIN_ALIGNMENT_PREFIX_BYTES

    /** Measure again once at least this much more of the video is on disk. */
    private const val REMEASURE_GROWTH_BYTES = 400_000L

    /** Nothing past this much of either is read. */
    private const val MAX_SECONDS = 720.0

    /** The record's opening read first when all of it is already on disk. */
    private const val OPENING_SECONDS = 75.0

    /** The video's opening read first: room for an intro before the record's [OPENING_SECONDS]. */
    private const val VIDEO_OPENING_SECONDS = 150.0

    /**
     * Measurements outlive whoever asked: the lyrics screen and the next-song
     * preparation are cancelled the moment the track changes, and a slow
     * device could be most of the way through decoding by then — the work was
     * thrown away and the video never timed. Run here, it finishes and is kept.
     */
    private val measureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A refusal on this much of a video is the measurement giving up. */
    private const val GIVE_UP_AFTER_SECONDS = 150.0

    /** ko-27: covers read singer-first, title-only searches for covers that don't name the original. */
    private const val SEARCH_VERSION = 3 // ko-97: reference rows are now the same song and the same artist

    /**
     * Measurements kept before this version are measured again. ko-97: the
     * reference recording rules changed, so a kept shift may describe a
     * karaoke arrangement or a namesake by the same artist (The Hills).
     */
    internal const val AUTO_VERSION = 4

    // ---- AI ----------------------------------------------------------------------------

    /**
     * Whether an accepted AI answer is kept for [videoId] and these [lines] —
     * not merely whether it is what applies right now. The measurement wins
     * [baseOf] whenever both exist, so asking "is the base AI?" made a paid
     * answer invisible and the next caller bought it again.
     */
    suspend fun hasAi(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        audioSource: String? = null,
    ): Boolean =
        store(context).get(videoId)?.let {
            AiTimingPolicy.isAccepted(knowledge(it), textKey(lines, audioSource))
        } == true

    /**
     * Asks Gemini where each of [lines] falls in the YouTube video [videoId]
     * and keeps the answer. Null when Gemini had nothing usable — and, for an
     * [automatic] request, that is remembered so it isn't asked again for the
     * same lyrics. A correction by hand made on top of the old timing no
     * longer fits the new one and is dropped.
     */
    suspend fun placeWithAi(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        automatic: Boolean = false,
        title: String = "",
        artist: String = "",
        isVideo: Boolean = false,
        audioSource: String? = null,
        probe: NextSyncProbe? = null,
        /** The next-prep bundle stops its own request when its row is replaced. */
        cancelWithCaller: Boolean = false,
        /** Word-stamped engines only: asked alongside the measurement, which may yet answer for free. */
        wordsOnly: Boolean = false,
    ): Adjustment? {
        val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
        if (sung.size < MIN_AI_LINES) {
            TrackLog.d(TAG, "AI timing skipped: only ${sung.size} sung lines", videoId)
            probe?.failure = NextPrepPolicy.SyncFailure.TOO_FEW_LINES
            return null
        }
        val key = textKey(lines, audioSource)
        // One decision for every automatic caller, made from what is kept —
        // the screen, the next-song preparation and the calibration sample
        // used to each guard on their own and each miss a case.
        val entry = store(context).get(videoId)
        // Whisper is slow and paid for by the minute, so an automatic request
        // gets it only once the measurement has given up — while the free
        // measurement may still time the video, the quick engines answer ahead.
        val engines = if (automatic) {
            val gaveUp = measurementGaveUp(context, videoId)
            // Independent of gaveUp: a measurement that gave up and then got an
            // accepted AI answer that the heard words contradict is exactly the
            // case this check exists for (Sol review, 11-04).
            val unsettled = timingUnsettled(context, videoId, lines, audioSource)
            AiTimingPolicy.nextEngines(
                knowledge(entry),
                key,
                manual = false,
                measurementGaveUp = gaveUp || unsettled,
                unsettled = unsettled,
            )
        } else {
            AiTimingPolicy.ALL_ENGINES
        }.let { pool -> if (wordsOnly) pool - ENGINE_GEMINI else pool }
        // Paid for and agreed, or already spent on these lyrics: reuse it
        // without decoding the audio again or paying an engine twice.
        if (engines.isEmpty()) {
            logAiForOtherLyrics(videoId, entry, key)
            return entry?.let { aiOf(it, lines, isVideo, audioSource) }
        }
        // One request per video, lyrics and kind, whoever asks — the button,
        // the song starting, the next-song preparation. The engines are no
        // longer part of the key: two callers that read the cache a moment
        // apart could ask for different sets and both decode the audio. A
        // manual tap is kept apart so it can still force a fresh answer.
        val flight = if (automatic) "$videoId:$key" else "$videoId:$key:manual"
        var fresh: kotlinx.coroutines.Deferred<Adjustment.Mapped?>? = null
        val running = synchronized(aiInFlight) {
            aiInFlight[flight] ?: run {
                if (!AiTimingPolicy.mayStartRequest(automatic, aiInFlight.size)) {
                    TrackLog.d(TAG, "lyrics-sync: ai request waits (${aiInFlight.size} running)", videoId)
                    return@synchronized null
                }
                aiScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    askAi(context, videoId, lines, sung, key, engines, title, artist, isVideo, probe)
                }.also {
                    fresh = it
                    aiInFlight[flight] = it
                }
            }
        } ?: return null
        running.cancelledWithCaller(currentCoroutineContext()[Job], cancelWithCaller)
        fresh?.let { created ->
            created.invokeOnCompletion { aiInFlight.remove(flight, created) }
            created.start()
        }
        val adjustment = running.await() ?: return null
        if (!automatic) {
            // Asked for by hand: it now wins over the measurement, and a
            // correction made on top of the old timing no longer fits it.
            store(context).update(videoId) { it.copy(aiPreferred = true, manualMs = null, manualClock = null) }
            _changes.tryEmit(videoId)
        }
        return adjustment
    }

    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aiInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Adjustment.Mapped?>>()

    /**
     * Who is still waiting on a shared flight. A next-prep bundle may start one
     * and a screen or another bundle may join it; the flight is only cancelled
     * once no live waiter remains, so a cancelled joiner cannot cancel work the
     * other caller still expects.
     */
    private class FlightWaiters {
        private var total = 0

        @Synchronized fun join() {
            total++
        }

        @Synchronized fun leave(cancelWithCaller: Boolean, cancelled: Boolean): Boolean {
            total--
            return cancelled && cancelWithCaller && total <= 0
        }

        @Synchronized fun isEmpty(): Boolean = total <= 0
    }

    private val flightWaiters = ConcurrentHashMap<kotlinx.coroutines.Deferred<*>, FlightWaiters>()

    /**
     * Ties an app-lifetime flight to the callers waiting on it when those
     * callers are next-prep bundles: cancelling the bundle must stop its own
     * request, not leave it paying for two more minutes after the row is gone.
     * A flight another caller started is only awaited, never cancelled here
     * while a caller that expects it to outlive its screen is still waiting.
     */
    internal fun <T> kotlinx.coroutines.Deferred<T>.cancelledWithCaller(
        caller: Job?,
        cancelWithCaller: Boolean,
    ): kotlinx.coroutines.Deferred<T> {
        if (caller == null) return this
        val waiters = flightWaiters.computeIfAbsent(this) { FlightWaiters() }
        waiters.join()
        val left = AtomicBoolean(false)
        fun leave(cancelled: Boolean) {
            if (!left.compareAndSet(false, true)) return
            if (waiters.leave(cancelWithCaller, cancelled)) {
                cancel(CancellationException("caller cancelled"))
            }
            if (waiters.isEmpty()) flightWaiters.remove(this, waiters)
        }
        caller.invokeOnCompletion { cause -> leave(cause != null) }
        // A flight that finishes on its own stops being awaited; drop its
        // waiter bookkeeping rather than keeping it for the session.
        invokeOnCompletion { leave(false) }
        return this
    }

    private suspend fun askAi(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        sung: List<IndexedValue<LyricLine>>,
        key: Int,
        engines: List<String>,
        title: String,
        artist: String,
        isVideo: Boolean,
        probe: NextSyncProbe? = null,
    ): Adjustment.Mapped? {
        val started = System.currentTimeMillis()
        // The next-song bundle honours the existing 429 hold: a previous
        // playback may have been turned away, and asking again before the
        // server's wait has passed would spend the same key.
        if (probe != null) {
            val held = store(context).get(videoId)
            val retryAt = held?.songRetryAtMs
            if (held?.songRetryKey == key &&
                retryAt != null && retryAt > System.currentTimeMillis()
            ) {
                TrackLog.d(TAG, "AI timing deferred until $retryAt", videoId)
                probe.failure = NextPrepPolicy.SyncFailure.RATE_LIMIT
                return null
            }
            // The 429 wait is account-wide, not per song: another video's
            // turn-away holds this one too, as it does for song checks.
            val quietUntil = songQuietUntilMs
            if (quietUntil > System.currentTimeMillis()) {
                TrackLog.d(TAG, "AI timing deferred until $quietUntil", videoId)
                probe.failure = NextPrepPolicy.SyncFailure.RATE_LIMIT
                return null
            }
        }
        // Whether any sound was there to hear, and the clock of what was: Gemini
        // watching the video URL with no local audio is on YouTube's clock.
        var audioPresent = false
        var listenedClock: Clock = Clock.YouTube
        val texts = sung.map { it.value.text }
        // Deepgram's Whisper first: every word it hears comes with its time to
        // the hundredth of a second, and it hears the singing nova-3 mostly
        // can't (measured on real covers: nova-3 placed 3 of 25 lines, Whisper
        // 22, within a tenth of a second). The song check uses the same
        // request and key; a later accepted Gemini answer may still get the
        // separate one-time Whisper upgrade below.
        var engine = ENGINE_DEEPGRAM_WHISPER
        var adjustment: Adjustment.Mapped? = null
        // The words behind the answer that is kept, so the panel can use their
        // real per-word times instead of guessing them from the line's slot.
        var acceptedHeard: List<LyricsWordAlign.Heard>? = null
        // Words heard for a sheet they did not fit: kept as evidence that the
        // sheet is not this video's song, without becoming a timing answer.
        var rejectedHeard: List<LyricsWordAlign.Heard>? = null
        // Kept before the call, not after: a request that fails, times out or
        // is cancelled has still been paid for, and must not be made again for
        // these lyrics. [AiTimingPolicy] reads this to pick the next engine.
        // A next-prep probe defers the write until the outcome is known: an
        // engine-stage failure is not a confirmed attempt and must be asked
        // again on the next playback.
        val pendingAttempts = mutableListOf<String>()
        var pendingWhisper = false
        // Whether any engine actually answered, even with a transcript that
        // did not fit: only "no engine answered" is an engine-stage failure.
        var anyEngineAnswered = false
        // Set when Deepgram turned the request away with a 429, so the next
        // playback waits out the existing deferral instead of treating the
        // refusal as a generic error.
        var rateLimitedWaitMs: Long? = null
        suspend fun persistAttempts() {
            if (probe == null || pendingAttempts.isEmpty()) return
            store(context).update(videoId) { entry ->
                val sameLyrics = entry.aiAttemptedKey == key
                entry.copy(
                    aiAttemptedKey = key,
                    aiAttemptedEngines = ((if (sameLyrics) entry.aiAttemptedEngines else emptyList()) + pendingAttempts).distinct(),
                    whisperKey = if (pendingWhisper) key else entry.whisperKey,
                )
            }
            pendingAttempts.clear()
        }
        suspend fun noteAttempt(name: String) {
            if (probe != null) {
                pendingAttempts += name
                return
            }
            store(context).update(videoId) { entry ->
                val sameLyrics = entry.aiAttemptedKey == key
                entry.copy(
                    aiAttemptedKey = key,
                    aiAttemptedEngines = ((if (sameLyrics) entry.aiAttemptedEngines else emptyList()) + name).distinct(),
                )
            }
        }
        // Only an instrumental upload is refused now; the log names whichever
        // reason actually applies rather than the old Korean label, which no
        // guard has produced since whisper-large was shown to hear Korean.
        val deepgramSkip = LyricsAiGuard.deepgramSkipReason(title)
        val deepgramWanted = deepgramSkip == null
        if (deepgramSkip != null) {
            TrackLog.d(TAG, "Deepgram skipped ($deepgramSkip)", videoId)
        }
        if (deepgramWanted && com.music.bitchord.data.lyrics.Deepgram.usable) {
            val language = com.music.bitchord.data.lyrics.Deepgram.languageFor(texts)
            val song = Song(videoId = videoId, title = title, artist = artist, thumbnailUrl = null, isVideo = isVideo)
            for (name in listOf(ENGINE_DEEPGRAM_WHISPER, ENGINE_WHISPER).filter { it in engines }) {
                val asked = System.currentTimeMillis()
                // Whatever of the video's sound has landed, up to all of it: a
                // cover's offset shows in its opening, and no decode or whole
                // file is waited for any more. Nothing to hear spends no engine.
                val hearing = hearThrough429(
                    context,
                    song,
                    AI_HEAR_SECONDS,
                    AI_HEAR_LEAST_SECONDS,
                    language,
                ) ?: break
                audioPresent = true
                listenedClock = hearing.clock
                // A 429 heard nothing. Spent anyway, this video's Whisper never came back for these lyrics.
                val spent = hearing.httpCode != 429
                if (spent) noteAttempt(name)
                if (spent && name == ENGINE_WHISPER) {
                    if (probe == null) {
                        store(context).update(videoId) { it.copy(whisperKey = key) }
                    } else {
                        pendingWhisper = true
                    }
                }
                val heardAnswer = hearing
                val heard = heardAnswer.words
                if (heard != null) anyEngineAnswered = true
                // Lines past what was heard cannot have been placed; they are
                // not disagreement. The whole song leaves every line in.
                val inReach = sung.filter { it.value.timeMs <= hearing.audioMs }
                    .takeIf { it.size >= MIN_AI_LINES } ?: sung
                val placement = heard?.let {
                    com.music.bitchord.data.lyrics.LyricsWordAlign.placeLinesDetailed(inReach.map { s -> s.value.text }, it)
                }
                val starts = placement?.starts
                val raw = placement?.let {
                    fitHeard(videoId, lines, inReach.map { s -> s.index }, it) { fit -> aiMayApply(lines, fit, isVideo) }
                }
                val fitted = raw?.takeIf { aiMayApply(lines, it, isVideo) }
                TrackLog.d(
                    TAG,
                    "$name timing ($language, ${(System.currentTimeMillis() - asked) / 1000}s): " + when {
                        heard == null -> "no answer"
                        raw == null -> "${heard.size} words, ${starts?.count { it != null } ?: 0} of ${inReach.size} lines placed: not enough"
                        !LyricsAiGuard.agreementAllows(raw.matchPercent) ->
                            "${heard.size} words, ${raw.matchPercent}% agree: below ${LyricsAiGuard.MIN_MATCH_PERCENT}%"
                        !LyricsAiGuard.shiftAllows(isVideo, shownShiftMs(lines, raw), raw.matchPercent) ->
                            "${heard.size} words, shift ${shownShiftMs(lines, raw)} ms, ${raw.matchPercent}% agree: too far for a video"
                        else -> "${heard.size} words, rate ${"%.3f".format(java.util.Locale.ROOT, raw.rate)}, " +
                            "shift ${shownShiftMs(lines, raw)} ms, ${raw.matchPercent}% agree"
                    },
                    videoId,
                )
                if (heardAnswer.httpCode == 429) {
                    // The retries ran out; the hold they left is the wait.
                    val wait = (songQuietUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    rateLimitedWaitMs = maxOf(rateLimitedWaitMs ?: 0L, wait)
                    // The same key is refused for Whisper too; Gemini is a
                    // separate provider and may still answer below.
                    break
                }
                if (fitted != null) {
                    adjustment = fitted
                    engine = name
                    acceptedHeard = heard
                    break
                }
                if (heard != null) rejectedHeard = heard
                // A key Deepgram turned away is turned away for Whisper too.
                if (!com.music.bitchord.data.lyrics.Deepgram.usable) break
            }
        }
        // Gemini when Deepgram isn't set up, had no audio to hear, or heard too little.
        var answer: List<Long?>? = null
        if (adjustment == null && ENGINE_GEMINI in engines) {
            noteAttempt(ENGINE_GEMINI)
            engine = ENGINE_GEMINI
            // The sound the phone already has, when it has all of it; the
            // YouTube address (Gemini fetching and watching the video) otherwise.
            val audioCopy = runCatching { VideoAudio.wavCopy(context, videoId) }.getOrNull()
            val audio = audioCopy?.bytes
            if (audioCopy != null) {
                audioPresent = true
                listenedClock = clockForRendition(videoId, audioCopy.renditionKey)
            } else {
                listenedClock = Clock.YouTube
            }
            answer = LyricsTranslation.placeLinesInVideo(
                videoId,
                sung.map { LyricsTranslation.LineToPlace(it.value.text, it.value.timeMs) },
                audioWav = audio,
            )
            if (answer != null) anyEngineAnswered = true
            val raw = answer?.let { fitAi(lines, sung.map { s -> s.index }, it) }
            adjustment = raw?.takeIf { aiMayApply(lines, it, isVideo) }
            TrackLog.d(
                TAG,
                "AI timing (${if (audio != null) "audio" else "video"}, ${(System.currentTimeMillis() - started) / 1000}s): " +
                    when {
                        answer == null -> "no answer"
                        raw == null -> "answers agree on no line (${answer.count { it != null }} of ${sung.size} placed)"
                        !LyricsAiGuard.agreementAllows(raw.matchPercent) ->
                            "${raw.matchPercent}% agree: below ${LyricsAiGuard.MIN_MATCH_PERCENT}%"
                        !LyricsAiGuard.shiftAllows(isVideo, shownShiftMs(lines, raw), raw.matchPercent) ->
                            "shift ${shownShiftMs(lines, raw)} ms, ${raw.matchPercent}% agree: too far for a video"
                        else -> "rate ${"%.3f".format(java.util.Locale.ROOT, raw.rate)}, " +
                            "shift ${shownShiftMs(lines, raw)} ms, ${raw.matchPercent}% agree"
                    },
                videoId,
            )
        }
        if (adjustment == null) {
            // Any engine that answered — even with a transcript that did not
            // fit — is a healthy no-answer. Only no answer from every engine
            // is an engine-stage failure; keep it out of the confirmed
            // attempts so the next playback asks again.
            if (probe != null) {
                probe.failure = NextPrepPolicy.engineFailure(
                    anyEngineAnswered = anyEngineAnswered,
                    rateLimited = rateLimitedWaitMs != null,
                    audioPresent = audioPresent,
                )
            }
            if (rateLimitedWaitMs != null) {
                markRateLimited(context, videoId, key, rateLimitedWaitMs!!)
            }
            if (NextPrepPolicy.shouldStoreSyncResult(probe?.failure)) {
                persistAttempts()
                // This key is what tells Gemini it was asked; a pass that left it
                // out must not use up its turn.
                if (anyEngineAnswered && ENGINE_GEMINI in engines) {
                    store(context).update(videoId) { it.copy(aiFailedKey = key) }
                }
            }
            rejectedHeard?.let { heard ->
                store(context).update(videoId) {
                    it.copy(
                        clockVersion = CLOCK_VERSION,
                        heardCheckKey = key,
                        heardCheckWords = heard.take(MAX_HEARD_WORDS)
                            .map { word -> SavedWord(t = word.text, s = word.startMs, e = word.endMs) },
                        heardCheckClock = listenedClock.saved(),
                    )
                }
            }
            return null
        }
        persistAttempts()
        store(context).update(videoId) {
            it.copy(
                clockVersion = CLOCK_VERSION,
                aiTextKey = key,
                ai = savedOf(adjustment),
                aiShiftMs = null,
                aiStarts = null,
                aiFailedKey = null,
                // The transcript words belong to this answer: another engine's
                // answer (or Gemini's, which hears no words) replaces them, and
                // old words for other lyrics must not outlive their answer.
                heardWords = acceptedHeard.orEmpty()
                    .take(MAX_HEARD_WORDS)
                    .map { word -> SavedWord(t = word.text, s = word.startMs, e = word.endMs) },
                aiClock = listenedClock.saved(),
                heardClock = listenedClock.saved(),
                heardCheckKey = null,
                heardCheckWords = emptyList(),
                heardCheckClock = null,
                // A new answer to the same lyrics keeps whether it was wanted
                // over the measurement; it's [placeWithAi] that says it is.
                aiPreferred = it.aiPreferred && it.aiTextKey == key,
                biasSampled = false,
                aiEngine = engine,
            )
        }
        sampleBias(context, videoId, lines, key)
        _changes.tryEmit(videoId)
        return adjustment
    }

    /**
     * Checks a record-timed song's own lyrics against its audio, once.
     *
     * A song version plays the record itself, so there is no other recording
     * to measure against (chroma): the provider's times may still be wrong for
     * this file, so Deepgram's word timings are fitted to them with the same
     * [fitAi] and the same 70% floor as a video. Only the opening the phone
     * has audio for is asked about, and only [LyricsAiGuard.SONG_MAX_SHIFT_MS]
     * may move the words. The answer is kept like any other AI answer, so the
     * screen and the lock screen read it without another request; an engine is
     * spent at most once per lyrics text ([AiTimingPolicy.nextEngines] with
     * `songOnly`).
     *
     * No timer or poll: the caller runs this when the lyrics arrive, once per
     * playback. Missing audio is not written to the store — the next playback
     * may have it — and the caller keeps it to one try per session. A 429 is
     * not spent as an answer: it is retried per [SongSyncRetry], then held until
     * its wait has passed so the next playback tries again. Returns true
     * when an engine answered and its answer was recorded; false when there
     * was nothing to ask, or the song was held for a 429.
     */
    /**
     * Whether this song's sync check already has an answer for these lyrics
     * and this audio source. The next-song bundle uses it to skip the step
     * entirely on a replay, rather than asking [verifySong] to re-derive the
     * same "nothing left to do".
     */
    suspend fun songVerified(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
    ): Boolean {
        val key = textKey(
            lines,
            audioSourceKey(song.videoId, song.localUri != null),
        )
        val entry = store(context).get(song.videoId)
        if (entry == null) {
            // Not a skip: the caller runs the check next.
            TrackLog.d(TAG, "lyrics-sync: song-check not done yet (no entry)", song.videoId)
            return false
        }
        return entry.aiTextKey == key && entry.ai != null
    }

    suspend fun verifySong(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
        probe: NextSyncProbe? = null,
        /** The next-prep bundle stops its own request when its row is replaced. */
        cancelWithCaller: Boolean = false,
    ): Boolean {
        val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
        val timed = lines.any { it.timeMs > 0 }
        if (!LyricsAiGuard.songTimingWanted(
                isVideo = isVideoRow(song),
                timed = timed,
                sungLines = sung.size,
                title = song.title,
                artist = song.artist,
                texts = sung.map { it.value.text },
            )
        ) {
            TrackLog.d(
                TAG,
                "lyrics-sync: song-check skipped by guard video=${isVideoRow(song)} " +
                    "timed=$timed sung=${sung.size}",
                song.videoId,
            )
            return false
        }
        val key = textKey(
            lines,
            audioSourceKey(song.videoId, song.localUri != null),
        )
        // A fresh next row has no entry until its first check writes one. That
        // is the normal case for the next-song bundle, not a reason to skip.
        val entry = store(context).get(song.videoId)
        // A 429 is not a failed answer: the engine was never really asked, so
        // it is not recorded as spent. The song waits instead until the
        // server's own delay has passed, and the next playback (an event, not
        // a timer) tries again.
        val retryAt = entry?.songRetryAtMs
        val retryHeld = entry?.songRetryKey == key &&
            retryAt != null && retryAt > System.currentTimeMillis()
        if (retryHeld) {
            TrackLog.d(
                TAG,
                "lyrics-sync: song-check deferred until $retryAt",
                song.videoId,
            )
            probe?.failure = NextPrepPolicy.SyncFailure.RATE_LIMIT
            return false
        }
        val engines = AiTimingPolicy.nextEngines(
            knowledge(entry),
            key,
            manual = false,
            // There is no measurement for a song version; the song pool
            // already holds only the song check's engine.
            measurementGaveUp = false,
            songOnly = true,
        )
        val engine = ENGINE_DEEPGRAM_WHISPER
        val engineAllowed = engine in engines &&
            com.music.bitchord.data.lyrics.Deepgram.usable
        if (!com.music.bitchord.playback.NextPrepPolicy.songCheckMayStart(
                hasEntry = entry != null,
                retryHeld = retryHeld,
                engineAllowed = engineAllowed,
            )
        ) {
            TrackLog.d(
                TAG,
                "lyrics-sync: song-check skipped engine=$engine allowed=${engine in engines} " +
                    "deepgram=${com.music.bitchord.data.lyrics.Deepgram.usable} " +
                    "why=${AiTimingPolicy.songSkipReason(knowledge(entry), key, com.music.bitchord.data.lyrics.Deepgram.usable)}",
                song.videoId,
            )
            return false
        }
        val retry = AiTimingPolicy.songRetry(knowledge(entry), key)
        if (retry) TrackLog.d(TAG, "song-check retry (previous answer rejected)", song.videoId)
        // Whisper takes a minute or two, and a screen job is cancelled the
        // moment the track changes: run the request on the app-lifetime scope
        // so it finishes and keeps its answer. One request per video and
        // lyrics text, whoever asks. A cancelled caller stops waiting only.
        val flight = "song:${song.videoId}:$key"
        newestSongKey[song.videoId] = key
        val fresh = aiScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            verifySongRequest(context.applicationContext, song, lines, key, engine, probe, retry)
        }
        val running = songInFlight.putIfAbsent(flight, fresh) ?: fresh
        running.cancelledWithCaller(currentCoroutineContext()[Job], cancelWithCaller)
        if (running === fresh) {
            fresh.invokeOnCompletion { songInFlight.remove(flight, fresh) }
            fresh.start()
        }
        return running.await()
    }

    /**
     * The free half of [prepareNextPaid]: the measurement and the timing it
     * already has, while [song] is still the next queue row.
     *
     * The only thing moved is the moment: [measure] reads the copy read-ahead
     * already put on disk, and [timing] reads whatever the store already holds.
     * The alignment maths and its floors are untouched. This runs immediately,
     * before any paid-engine gate: the free measurement needs no upload and is
     * the half that times most covers on its own.
     *
     * Returns the timing that will apply when the row starts; null means the
     * existing paths had nothing to add.
     */
    suspend fun prepareNextFree(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
        fromFile: Boolean,
        source: LyricsSource?,
        searchedAs: Pair<String, String>?,
    ): Timing? {
        val query = CoverTitles.lyricsQuery(song)
        val timeable = !fromFile || query.isVideo
        val recordTimed = timeable && (!fromFile || source != null) &&
            !timedToVideo(source)
        // A cover is an upload even when its queue row lost the video flag —
        // the same [CoverTitles.lyricsQuery] test the screen and the cache use,
        // so the saved answer is looked up under the key it was stored with.
        val audioSource = if (query.isVideo) {
            null
        } else {
            audioSourceKey(song.videoId, song.localUri != null)
        }
        // A video is measured against its original from the bytes read-ahead
        // already wrote. A song version has no other recording to measure
        // against; its existing path is the AI song check.
        if (recordTimed && query.isVideo && worthMeasuring(context, song.videoId)) {
            measure(
                context = context,
                video = song,
                names = nextPrepNames(query, searchedAs),
                videoMs = song.durationMillis(),
                awaitCacheSignal = true,
                cancelWithCaller = true,
                lines = lines,
            )
        }
        return timing(
            context = context,
            videoId = song.videoId,
            lines = lines,
            baseAllowed = timeable,
            measuredAllowed = recordTimed,
            deniedBy = "file-own-timing@next-free",
            // Same test as audioSource above: a video key read with the song
            // version's shift rule would refuse a video's honest offset.
            isVideo = query.isVideo,
            audioSource = audioSource,
        )
    }

    /**
     * The paid half of the next-row path: the same AI fallback and refine the
     * lyrics screen uses, run only after the caller's 30 s AI gate has opened.
     *
     * The engine choice, the agreement/shift floors and the refine caps are
     * untouched; only [timing] from [prepareNextFree] is carried in. The
     * measurement is not repeated here.
     */
    suspend fun prepareNextPaid(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
        fromFile: Boolean,
        source: LyricsSource?,
        timing: Timing?,
        probe: NextSyncProbe? = null,
        /** The next-prep bundle stops the requests it started when its row is replaced. */
        cancelWithCaller: Boolean = false,
    ): Timing? {
        val query = CoverTitles.lyricsQuery(song)
        val timeable = !fromFile || query.isVideo
        val recordTimed = timeable && (!fromFile || source != null) &&
            !timedToVideo(source)
        if (!recordTimed) return timing
        // Same video/cover test as the screen and the first-frame cache: the
        // answer and its key must agree with what the lyrics screen will ask.
        val audioSource = if (query.isVideo) {
            null
        } else {
            audioSourceKey(song.videoId, song.localUri != null)
        }
        var result = timing

        if (query.isVideo) {
            // The existing automatic fallback: Gemini/Deepgram only once the
            // measurement has given up, with the same wantsAi policy.
            if (measurementGaveUp(context, song.videoId, lines, audioSource) &&
                wantsAi(context, song.videoId, lines, audioSource)
            ) {
                placeWithAi(
                    context = context,
                    videoId = song.videoId,
                    lines = lines,
                    automatic = true,
                    title = query.title,
                    artist = query.artist,
                    isVideo = true,
                    audioSource = audioSource,
                    probe = probe,
                    cancelWithCaller = cancelWithCaller,
                )
                result = timing(
                    context, song.videoId, lines, timeable, recordTimed, true, audioSource,
                )
            } else if (wantsAi(context, song.videoId, lines, audioSource)) {
                // The words alongside the free measurement, as on the screen: a
                // cover or video starts with its offset known. Nothing to hear is
                // no failure here; the row asks again when it plays.
                placeWithAi(
                    context = context,
                    videoId = song.videoId,
                    lines = lines,
                    automatic = true,
                    title = query.title,
                    artist = query.artist,
                    isVideo = true,
                    audioSource = audioSource,
                    probe = probe,
                    cancelWithCaller = cancelWithCaller,
                    wordsOnly = true,
                )
                result = timing(
                    context, song.videoId, lines, timeable, recordTimed, true, audioSource,
                )
            }
        } else if (!songVerified(context, song, lines)) {
            // The record-timed song check: same Deepgram/Whisper request and
            // same fit/guard the screen runs when the lyrics arrive.
            verifySong(context, song, lines, probe, cancelWithCaller = cancelWithCaller)
            result = timing(
                context, song.videoId, lines, timeable, recordTimed, false, audioSource,
            )
        }

        if (result != null && VideoAudio.secondsOnDisk(song) >= VideoAudio.SONG_CHECK_SECONDS) {
            // The refine is the last existing stage; it may use an AI transcript
            // it already has or hear the next row's opening. Same policy and caps.
            // Only with the opening on disk: otherwise this waits for sound that
            // comes once the row plays, and the screen asks again then.
            ensureRefineHeard(context, song.videoId, song, lines, result, audioSource)
            result = timing(
                context, song.videoId, lines, timeable, recordTimed, query.isVideo, audioSource,
            )
        }
        return result
    }

    /**
     * The names a cover is searched under, in the same order the lyrics screen
     * uses: the video's own reading first when it names the original's artist,
     * then what its lyrics were found as, then the title alone for a cover
     * that never names the original, and the query's alternatives.
     */
    private fun nextPrepNames(
        query: CoverTitles.LyricsQuery,
        searchedAs: Pair<String, String>?,
    ): List<Pair<String, String>> = buildList {
        if (query.artistKnown) add(query.title to query.artist)
        searchedAs?.takeIf { query.artistKnown || it.second != query.artist }?.let(::add)
        if (!query.artistKnown) add(query.title to "")
        add(query.title to query.artist)
        addAll(query.alternatives)
    }.distinct()

    private val songInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Boolean>>()

    /**
     * The sheet each video's song check was last asked about: the one on screen.
     * There is one AI slot per video and newest wins, so the answer to a sheet
     * that was replaced, landing late, took the slot from the sheet showing and
     * left the screen with none (11-06 rlYOsUfpi3M: applied at 88%, overwritten
     * a second later, ended sync=none).
     */
    private val newestSongKey = ConcurrentHashMap<String, Int>()

    private fun replacedSheet(videoId: String, key: Int): Boolean {
        val replaced = newestSongKey[videoId].let { it != null && it != key }
        if (replaced) TrackLog.d(TAG, "song-sync: answer for a replaced sheet dropped", videoId)
        return replaced
    }

    /**
     * Whether words are being fetched for [videoId] right now. A refine started
     * then would buy the same sound beside them; it runs when they land (the
     * screen refreshes its timing then), and reads them for free.
     */
    fun hearingBusy(videoId: String): Boolean =
        songInFlight.keys.any { it.startsWith("song:$videoId:") } ||
            aiInFlight.keys.any { it.startsWith("$videoId:") }

    /**
     * The one song-sync request allowed at a time. A 429 is an account-wide
     * statement, so serialising the checks keeps a queue of skipped songs from
     * re-tripping the limiter while another check is in flight. Nothing polls:
     * waiters are suspended on this mutex, and a wait longer than
     * [SongSyncRetry.MAX_INLINE_WAIT_MS] is deferred to the next playback.
     */
    private val songSyncGate = Mutex()

    /**
     * Bumped for a video whenever [resetSongCheck] drops the answer of the
     * recording that was there. A re-check captures the value before it waits
     * on [songSyncGate]; if it changed while it waited, the file it was about
     * to answer for is gone and its result must not be written over the new
     * one. Reset also clears [syncCheckVersion], so the old guard alone let a
     * waiting check see "unverified" and re-apply the previous bytes' answer.
     */
    private val songSyncGenerations = ConcurrentHashMap<String, Long>()

    private fun songSyncGenerationOf(videoId: String): Long = songSyncGenerations[videoId] ?: 0L

    private fun bumpSongSyncGeneration(videoId: String) {
        songSyncGenerations.merge(videoId, 1L, Long::plus)
    }

    /** No song check may be sent before this, after a 429; 0 when clear. */
    @Volatile
    private var songQuietUntilMs = 0L

    /** The request itself, outliving its caller; see [verifySong]. */
    private suspend fun verifySongRequest(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
        key: Int,
        engine: String,
        probe: NextSyncProbe? = null,
        retry: Boolean = false,
    ): Boolean {
        // The gate is for applying an answer. Listening used to happen under it,
        // and fetching the audio before that: the next row's check held the
        // playing song's for minutes (11-03: answers landed 3-5 minutes in).
        // Each window is now heard first, off the gate, so a song waits for
        // nobody's request; [SongHearing] caps how many run at once and shares
        // one between callers. A reset during that wait voids the answer.
        val startedGeneration = songSyncGenerationOf(song.videoId)
        // The first pass listens to the short opening that is already on disk,
        // so the screen is corrected while the song is still playing. The full
        // opening follows on the same cold playback: applied as a refinement,
        // or as the first answer when the short one could not fit. Already on
        // disk in full (a replay, a row read ahead), one pass does for both.
        val firstUntil = if (VideoAudio.secondsOnDisk(song) >= VideoAudio.SONG_CHECK_SECONDS) {
            VideoAudio.SONG_CHECK_SECONDS
        } else {
            SongCheckWindow.FIRST_SECONDS
        }
        val firstAsk = hearSongCheck(context, song, lines, key, probe, firstUntil, retry) ?: return false
        val (first, firstOk) = songSyncGate.withLock {
            if (songSyncGenerationOf(song.videoId) != startedGeneration) return@withLock null
            if (replacedSheet(song.videoId, key)) return@withLock null
            // Checked again under the gate: another song's 429 may have marked
            // this one while its flight was waiting here.
            val pending = store(context).get(song.videoId)
            val retryAt = pending?.songRetryAtMs
            if (pending?.songRetryKey == key && retryAt != null && retryAt > System.currentTimeMillis()) {
                probe?.failure = NextPrepPolicy.SyncFailure.RATE_LIMIT
                return@withLock null
            }
            val first = fitSongCheck(context, song, lines, key, engine, probe, firstAsk, retry)
            first to applySongCheck(context, song, key, engine, first)
        } ?: return false
        if (!SongCheckWindow.needsFullOpening(first.result) ||
            first.seconds >= VideoAudio.SONG_CHECK_SECONDS - 1
        ) {
            return firstOk
        }
        // An opening that fitted waits for the whole song's words, not a longer
        // opening: the same fit from 45 s and 88 s differed by 39 ms (11-06
        // rlYOsUfpi3M), and each listen is paid for. Only a first pass that could
        // not fit is worth more of the opening.
        if (first.fitted != null) return firstOk
        // A row not playing yet has only its head: the rest would hold up the
        // preparation for sound that will not come until it plays.
        if (probe != null && VideoAudio.secondsOnDisk(song) < VideoAudio.SONG_CHECK_SECONDS) return firstOk
        val fullAsk = hearSongCheck(context, song, lines, key, probe, VideoAudio.SONG_CHECK_SECONDS, retry)
            ?: return firstOk
        return songSyncGate.withLock {
            if (songSyncGenerationOf(song.videoId) != startedGeneration) return@withLock firstOk
            if (replacedSheet(song.videoId, key)) return@withLock firstOk
            val full = fitSongCheck(context, song, lines, key, engine, probe, fullAsk, retry)
            if (!SongCheckWindow.fullMayReplace(
                    firstFitted = first.fitted != null,
                    fullFitted = full.fitted != null,
                )
            ) {
                return@withLock firstOk
            }
            val fullOk = applySongCheck(context, song, key, engine, full)
            fullOk || firstOk
        }
    }

    /**
     * Keeps what one song check found: the answer when it fitted, or the words
     * heard as wrong-song evidence. Returns whether the check reached an engine.
     */
    private suspend fun applySongCheck(
        context: Context,
        song: Song,
        key: Int,
        engine: String,
        check: SongCheck,
    ): Boolean {
        if (check.failure != null) return false
        if (check.result == "no-words") return false
        val checkClock = check.clock
        val fitted = check.fitted
        if (fitted == null) {
            // The engine answered but its transcript does not fit this sheet.
            // Keep the words as wrong-song evidence; no timing is applied.
            if (check.heard.isNotEmpty()) {
                store(context).update(song.videoId) {
                    it.copy(
                        clockVersion = CLOCK_VERSION,
                        heardCheckKey = key,
                        heardCheckWords = check.heard.take(MAX_HEARD_WORDS)
                            .map { word -> SavedWord(t = word.text, s = word.startMs, e = word.endMs) },
                        heardCheckClock = checkClock.saved(),
                    )
                }
            }
            return true
        }
        store(context).update(song.videoId) {
            it.copy(
                clockVersion = CLOCK_VERSION,
                aiTextKey = key,
                ai = savedOf(fitted),
                aiShiftMs = null,
                aiStarts = null,
                aiFailedKey = null,
                heardWords = check.heard.take(MAX_HEARD_WORDS)
                    .map { word -> SavedWord(t = word.text, s = word.startMs, e = word.endMs) },
                aiClock = checkClock.saved(),
                heardClock = checkClock.saved(),
                heardCheckKey = null,
                heardCheckWords = emptyList(),
                heardCheckClock = null,
                aiPreferred = it.aiPreferred && it.aiTextKey == key,
                biasSampled = false,
                aiEngine = engine,
                songRetryKey = null,
                songRetryAtMs = null,
            )
        }
        _changes.tryEmit(song.videoId)
        songLog(
            song.videoId,
            "applied",
            check.match,
            check.shift,
            check.seconds,
            words = check.words,
            http = check.http,
            detail = check.detail,
        )
        return true
    }

    /**
     * The one song check, used by the streaming path and by the download
     * re-check. Reads the audio, listens to the opening and fits the lines to
     * the words heard; null means nothing could be listened to (no audio, or a
     * 429 held for later), so the caller must not record the attempt as spent.
     */
    private suspend fun runSongCheck(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
        key: Int,
        engine: String,
        probe: NextSyncProbe? = null,
        untilSeconds: Double = VideoAudio.SONG_CHECK_SECONDS,
        /** This is the one [AiTimingPolicy.songRetry] check; its spent replies say so. */
        retry: Boolean = false,
    ): SongCheck? {
        val ask = hearSongCheck(context, song, lines, key, probe, untilSeconds, retry) ?: return null
        return fitSongCheck(context, song, lines, key, engine, probe, ask, retry)
    }

    /** One window as heard, or why nothing needed hearing; [fitSongCheck] judges it, under the gate. */
    private class SongAsk(
        val answer: SongHearing.Heard?,
        val language: String,
        val untilSeconds: Double,
    )

    /**
     * Listens to one window for a song check, off the gate. Null means nothing
     * could be listened to (no audio, or a 429 held for later), so the caller
     * must not record the attempt as spent. A window with too few lines to fit
     * asks nobody and comes back without an answer.
     */
    private suspend fun hearSongCheck(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
        key: Int,
        probe: NextSyncProbe?,
        untilSeconds: Double,
        retry: Boolean,
    ): SongAsk? {
        probe?.beginPass()
        val indices = SongCheckWindow.indices(lines.map { it.timeMs }, (untilSeconds * 1000).toLong())
            .filter { lines[it].text.isNotBlank() }
        if (indices.size < MIN_AI_LINES) return SongAsk(null, "", untilSeconds)
        val texts = indices.map { lines[it].text }
        val sheetLanguage = com.music.bitchord.data.lyrics.Deepgram.languageFor(texts)
        // A mixed sheet's one retry listens in its other script; still one request, still 2 per lyrics text.
        val retryLanguage = if (retry) com.music.bitchord.data.lyrics.Deepgram.retryLanguageFor(texts, sheetLanguage) else null
        val language = retryLanguage ?: sheetLanguage
        val mixed = com.music.bitchord.data.lyrics.Deepgram.mixedPercent(texts, sheetLanguage)
        if (mixed > 0) TrackLog.d(TAG, "song-sync: lang=$sheetLanguage sheet-mixed ratio=$mixed%", song.videoId)
        if (retryLanguage != null) TrackLog.d(TAG, "song-sync: lang retry ($sheetLanguage -> $retryLanguage)", song.videoId)
        val answer = listenForSong(context, song, key, untilSeconds, language, probe) ?: return null
        return SongAsk(answer, language, untilSeconds)
    }

    /** Fits the lines to what [ask] heard. Runs under the gate: this and the apply are what it is for. */
    private suspend fun fitSongCheck(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
        key: Int,
        engine: String,
        probe: NextSyncProbe?,
        ask: SongAsk,
        retry: Boolean,
    ): SongCheck {
        val answer = ask.answer
        // How far the words reach, by what the server read: a cut stream may
        // stop short of the window, and a kept transcript may run past it.
        val windowMs = answer?.audioMs ?: (ask.untilSeconds * 1000).toLong()
        val listenClock = answer?.clock ?: Clock.Unknown
        val language = ask.language
        val indices = SongCheckWindow.indices(lines.map { it.timeMs }, windowMs)
            .filter { lines[it].text.isNotBlank() }
        val seconds = (windowMs / 1000.0).roundToInt()
        if (answer == null || indices.size < MIN_AI_LINES) {
            songLog(song.videoId, "no-words", null, null, seconds)
            probe?.failure = NextPrepPolicy.SyncFailure.TOO_FEW_LINES
            return SongCheck("no-words", null, emptyList(), null, null, seconds, null, 0, probe?.failure, listenClock)
        }
        val texts = indices.map { lines[it].text }
        val heard = answer.words ?: run {
            songLog(song.videoId, "no-answer", null, null, seconds, words = 0, http = answer.httpCode)
            probe?.failure = when {
                answer.httpCode == 429 -> NextPrepPolicy.SyncFailure.RATE_LIMIT
                answer.httpCode == null -> NextPrepPolicy.SyncFailure.ERROR
                answer.httpCode !in 200..299 -> NextPrepPolicy.SyncFailure.ERROR
                else -> NextPrepPolicy.SyncFailure.TOO_FEW_LINES
            }
            recordSongAttempt(context, song.videoId, key, engine, answer.httpCode, probe, retry)
            return SongCheck("no-answer", null, emptyList(), null, null, seconds, answer.httpCode, 0, probe?.failure, listenClock)
        }
        if (heard.size < MIN_AI_LINES) {
            songLog(song.videoId, "few-words", null, null, seconds, words = heard.size, http = answer.httpCode)
            probe?.failure = NextPrepPolicy.SyncFailure.TOO_FEW_LINES
            recordSongAttempt(context, song.videoId, key, engine, answer.httpCode, probe, retry)
            return SongCheck("few-words", null, heard, null, null, seconds, answer.httpCode, heard.size, probe?.failure, listenClock)
        }
        val placement = LyricsWordAlign.placeLinesDetailed(texts, heard)
        val starts = placement.starts
        val spellable = com.music.bitchord.data.lyrics.Deepgram.spellableLines(texts, language)
        // Why a match is what it is: lines in the window, lines placed, lines the language could spell.
        val detail = " lines=${texts.size} placed=${starts.count { it != null }} spellable=$spellable lang=$language"
        val fitted = fitHeard(song.videoId, lines, indices, placement, spellable) { fit ->
            LyricsAiGuard.agreementAllows(fit.matchPercent) &&
                LyricsAiGuard.songShiftAllows(shownShiftMs(lines, fit), fit.matchPercent)
        } ?: run {
            songLog(song.videoId, "no-fit", null, null, seconds, words = heard.size, http = answer.httpCode, detail = detail)
            recordSongAttempt(context, song.videoId, key, engine, answer.httpCode, probe, retry)
            return SongCheck("no-fit", null, heard, null, null, seconds, answer.httpCode, heard.size, clock = listenClock)
        }
        val match = fitted.matchPercent
        val shift = shownShiftMs(lines, fitted)
        when {
            !LyricsAiGuard.agreementAllows(match) -> {
                songLog(song.videoId, "refused-match", match, shift, seconds, words = heard.size, http = answer.httpCode, detail = detail)
                recordSongAttempt(context, song.videoId, key, engine, answer.httpCode, probe, retry)
                return SongCheck("refused-match", null, heard, match, shift, seconds, answer.httpCode, heard.size, clock = listenClock)
            }
            !LyricsAiGuard.songShiftAllows(shift, match) -> {
                songLog(song.videoId, "refused-shift", match, shift, seconds, words = heard.size, http = answer.httpCode, detail = detail)
                recordSongAttempt(context, song.videoId, key, engine, answer.httpCode, probe, retry)
                return SongCheck("refused-shift", null, heard, match, shift, seconds, answer.httpCode, heard.size, clock = listenClock)
            }
        }
        recordSongAttempt(context, song.videoId, key, engine, answer.httpCode, probe, retry)
        return SongCheck("applied", fitted, heard, match, shift, seconds, answer.httpCode, heard.size, clock = listenClock, detail = detail)
    }

    /**
     * Records the engine as spent only for an answer the engine actually
     * produced (a fit, a refusal, or an applied correction). A next-prep
     * engine-stage failure stays unrecorded so the next playback retries it;
     * a rate limit keeps its own [markRateLimited] backoff instead.
     */
    private suspend fun recordSongAttempt(
        context: Context,
        videoId: String,
        key: Int,
        engine: String,
        httpCode: Int?,
        probe: NextSyncProbe?,
        retry: Boolean,
    ) {
        if (!AiTimingPolicy.songReplySpends(httpCode)) return
        // The probe is only read here: a failure has already been set above
        // when the answer carried no words or too few of them.
        if (!NextPrepPolicy.shouldStoreSyncResult(probe?.failure)) return
        noteAttempt(context, videoId, key, engine, retry)
    }

    /** What [runSongCheck] made of one lyric sheet; [result] is the song-sync log token. */
    private class SongCheck(
        val result: String,
        val fitted: Adjustment.Mapped?,
        val heard: List<LyricsWordAlign.Heard>,
        val match: Int?,
        val shift: Long?,
        val seconds: Int,
        val http: Int?,
        val words: Int?,
        /**
         * Set for an engine-stage failure (no audio, rate limit, too few
         * words). Null means the engine ran and answered, even if it had
         * nothing to correct. Kept apart from [result] because the old
         * `no-answer` token could mean either.
         */
        val failure: NextPrepPolicy.SyncFailure? = null,
        /** The clock of the copy this check actually listened to. */
        val clock: Clock = Clock.Unknown,
        /** The line counts behind [match], for the `applied` log line. */
        val detail: String = "",
    )

    /**
     * Whisper for a song check, with the 429 rules applied. Null means there was
     * no sound, or the song was rate-limited and marked for later.
     */
    private suspend fun listenForSong(
        context: Context,
        song: Song,
        key: Int,
        untilSeconds: Double,
        language: String,
        probe: NextSyncProbe? = null,
    ): SongHearing.Heard? {
        val videoId = song.videoId
        val quiet = songQuietUntilMs - System.currentTimeMillis()
        if (quiet > 0) {
            if (!SongSyncRetry.canWaitInline(quiet)) {
                markRateLimited(context, videoId, key, quiet)
                rateLimitedLog(videoId, quiet)
                probe?.failure = NextPrepPolicy.SyncFailure.RATE_LIMIT
                return null
            }
            delay(quiet)
        }
        val answer = hearThrough429(
            context,
            song,
            untilSeconds,
            minSeconds = if (untilSeconds < VideoAudio.SONG_CHECK_SECONDS) SONG_FIRST_LEAST_SECONDS else untilSeconds,
            language,
        ) ?: run {
            songLog(videoId, "no-audio", null, null, 0)
            probe?.failure = NextPrepPolicy.SyncFailure.NO_AUDIO
            return null
        }
        if (answer.httpCode == 429) {
            markRateLimited(context, videoId, key, songQuietUntilMs - System.currentTimeMillis())
            probe?.failure = NextPrepPolicy.SyncFailure.RATE_LIMIT
            return null
        }
        return answer
    }

    /** [SongHearing.hear] under [SongSyncRetry]. A 429 back means the retries ran out, [songQuietUntilMs] holding the wait. */
    private suspend fun hearThrough429(
        context: Context,
        song: Song,
        untilSeconds: Double,
        minSeconds: Double,
        language: String,
    ): SongHearing.Heard? {
        var attempt = 0
        while (true) {
            // Straight off the file, or the streaming copy as it stands.
            val answer = SongHearing.hear(context, song, untilSeconds, language, minSeconds) ?: return null
            if (answer.httpCode != 429) {
                songQuietUntilMs = 0L
                return answer
            }
            val wait = SongSyncRetry.retryDelayMs(answer.retryAfterMs, attempt)
            songQuietUntilMs = System.currentTimeMillis() + wait
            rateLimitedLog(song.videoId, wait)
            if (!SongSyncRetry.shouldRetry(answer.httpCode, attempt) ||
                !SongSyncRetry.canWaitInline(wait)
            ) {
                return answer
            }
            delay(wait)
            attempt++
        }
    }

    /** Keeps a rate-limited song until its server-stated wait has passed; next playback retries. */
    private suspend fun markRateLimited(context: Context, videoId: String, key: Int, delayMs: Long) {
        val retryAt = System.currentTimeMillis() + delayMs.coerceAtLeast(0L)
        store(context).update(videoId) { it.copy(songRetryKey = key, songRetryAtMs = retryAt) }
    }

    /** The one line the phone log needs for a 429, without words or keys. */
    private fun rateLimitedLog(videoId: String, retryAfterMs: Long) {
        TrackLog.d(TAG, "song-sync: rate-limited retryAfter=${retryAfterMs}ms", videoId)
    }

    /** One line about a song check, without the words, the search or any key. */
    private fun songLog(
        videoId: String,
        result: String,
        match: Int?,
        shift: Long?,
        seconds: Int,
        words: Int? = null,
        http: Int? = null,
        detail: String = "",
    ) {
        TrackLog.d(
            TAG,
            "song-sync: $videoId result=$result match=${match ?: 0}% shift=${shift ?: 0} secs=$seconds" +
                " words=${words ?: 0}" + (http?.let { " http=$it" } ?: "") +
                " sheet=${AiTimingPolicy.songSheetEffect(result)}" + detail,
            videoId,
        )
    }

    /** Records that [engine] was spent on these lyrics for [videoId]; once per text. */
    private suspend fun noteAttempt(context: Context, videoId: String, key: Int, engine: String, retry: Boolean) {
        store(context).update(videoId) { entry ->
            val sameLyrics = entry.aiAttemptedKey == key
            entry.copy(
                aiAttemptedKey = key,
                aiAttemptedEngines = AiTimingPolicy.songSpent(
                    if (sameLyrics) entry.aiAttemptedEngines else emptyList(),
                    engine,
                    retry,
                ),
                songRetryKey = null,
                songRetryAtMs = null,
            )
        }
    }

    /** Forgets the AI answer for [videoId] (and a correction made on top of it); the measurement applies again. */
    suspend fun clearAi(context: Context, videoId: String) {
        store(context).update(videoId) {
            it.copy(
                clockVersion = CLOCK_VERSION,
                aiTextKey = null,
                ai = null,
                aiShiftMs = null,
                aiStarts = null,
                aiClock = null,
                heardWords = emptyList(),
                heardClock = null,
                manualMs = null,
                manualClock = null,
                aiPreferred = false,
            )
        }
        _changes.tryEmit(videoId)
    }

    /**
     * Forgets the song check's answer and the engines it spent for [videoId],
     * leaving the listener's own correction alone.
     *
     * Called when a downloaded file judged to hold the video's sound is
     * replaced by the song version: the answer was measured against the old
     * recording, and the same lyrics must be checked again against the new
     * one. The text key already names file vs stream, but a replaced file can
     * keep the same path, so the record itself has to be dropped.
     */
    suspend fun resetSongCheck(context: Context, videoId: String) = songSyncGate.withLock {
        // Hold the same gate as recheckDownloaded: a check already listening to
        // the old bytes must not write its answer or override after this reset,
        // which would put the replaced file back on the old recording's sync.
        // The file these lyrics came from is gone or has different bytes. An
        // automatic override still holds its old sheet and sync, so it has to
        // go with the answer; a listener's own override is left alone.
        bumpSongSyncGeneration(videoId)
        SongHearing.forgetFile(context, videoId)
        LyricsOverrideStore.removeRecheck(context, videoId)
        store(context).update(videoId) {
            it.copy(
                clockVersion = CLOCK_VERSION,
                aiTextKey = null,
                ai = null,
                aiShiftMs = null,
                aiStarts = null,
                aiClock = null,
                aiFailedKey = null,
                heardWords = emptyList(),
                heardClock = null,
                heardCheckKey = null,
                heardCheckWords = emptyList(),
                heardCheckClock = null,
                aiPreferred = false,
                aiEngine = null,
                aiAttemptedKey = null,
                aiAttemptedEngines = emptyList(),
                whisperKey = null,
                songRetryKey = null,
                songRetryAtMs = null,
                // The replacement has not been checked with today's rules. A
                // version left behind made recheckDownloaded answer NONE and
                // the new file kept the old timing for ever.
                syncCheckVersion = 0,
                // The chroma measurement was made from the old bytes too. Its
                // answer must not move the new recording's lyrics.
                autoChecked = false,
                autoShiftMs = null,
                autoVersion = 0,
                auto = null,
                autoClock = null,
                autoOriginal = null,
                autoComplete = false,
                autoNoChange = false,
                autoRecordPartial = false,
                autoVideoBytes = 0L,
                autoVideoSeconds = 0.0,
                autoNote = null,
                searchVersion = 0,
                biasSampled = false,
                // The refine transcript was heard from the old recording. Its
                // text key names only the file, so without this the new file
                // would reuse the old words and the old line correction.
                refineMs = null,
                refineSlopePerMin = 0.0,
                refineLines = 0,
                refineMad = null,
                refineClock = null,
                refineHeardKey = null,
                refineHeardWords = emptyList(),
                refineHeardClock = null,
                refineHeardSeconds = 0.0,
                refineHeardExtended = false,
            )
        }
        recheckTokens.remove(videoId)
        _changes.tryEmit(videoId)
    }

    // ---- Downloaded files' stored sync ------------------------------------------------

    /** The last re-check state per video, for the `lyrics-sync:` line only. */
    private val recheckTokens = ConcurrentHashMap<String, String>()

    /**
     * What the `lyrics-sync:` line says about the stored sync of a downloaded
     * file: whether a sync is kept for these lyrics, and why it is (or was)
     * being re-checked on this play.
     */
    suspend fun syncDiagnostics(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        audioSource: String?,
        downloaded: Boolean,
        providerUnknown: Boolean,
    ): Pair<Boolean, String> {
        val entry = store(context).get(videoId)
        val key = textKey(lines, audioSource)
        // A downloaded video may still carry an answer under the file key an
        // older song check wrote; both keys name the same recording.
        val fileKey = if (audioSource == null) {
            textKey(lines, audioSourceKey(videoId, localFile = true))
        } else {
            null
        }
        fun keyed(savedKey: Int?): Boolean = savedKey != null &&
            (entry?.aiTextKey == savedKey) &&
            (entry?.ai != null || entry?.aiShiftMs != null || entry?.aiStarts != null)
        val match = entry?.let { e ->
            e.ai?.match?.takeIf { e.aiTextKey == key || e.aiTextKey == fileKey }
        }
        // A stored answer is either the AI/song-check one keyed to these
        // lyrics, or a chroma measurement. The measurement is not keyed to a
        // lyric sheet (it times the recording), so requiring aiTextKey here
        // made every measured video report stored=n even when its next-prep
        // measurement had just been saved.
        val stored = keyed(key) || keyed(fileKey) || hasAutoAnswer(entry)
        val recheck = if (!downloaded) {
            DownloadedSyncCheck.Recheck.NONE.token
        } else {
            recheckTokens[videoId] ?: DownloadedSyncCheck.recheckReason(
                entry?.syncCheckVersion,
                providerUnknown = providerUnknown,
                matchPercent = match,
            ).token
        }
        return stored to recheck
    }

    /**
     * The record-timed check a downloaded file always gets, whatever its row's
     * video flag says: the file's own audio is what is being checked.
     */
    internal fun downloadRecheckSongCheckWanted(
        sungLines: Int,
        title: String,
        artist: String,
        texts: List<String>,
    ): Boolean = LyricsAiGuard.songTimingWanted(
        isVideo = false,
        timed = true,
        sungLines = sungLines,
        title = title,
        artist = artist,
        texts = texts,
    )

    /**
     * Re-checks a downloaded file's stored lyrics and sync once, when they
     * were never checked with the current rules. The file's own audio is used
     * exactly as the streaming song check uses its copy; a poor or unknown
     * stored sheet is compared with a fresh provider lookup, and the answer
     * that agrees more wins. A fresh sheet is kept as this song's override and
     * under its own text key, so both the screen and later plays use the same
     * lyrics and sync. The file itself is never rewritten. The file's own lines
     * stay under the existing key when they win.
     *
     * One check at a time, and none for a version already recorded: this is
     * called on playback only, never polled, and a repeat play is a no-op.
     */
    suspend fun recheckDownloaded(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
        providerUnknown: Boolean,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        alternatives: List<Pair<String, String>>,
        artistKnown: Boolean,
        artistAliases: List<String>,
    ): LyricsRepository.Result? {
        // Captured before any suspension: a reset that runs while this check
        // waits for the gate changes it, and then this answer names old bytes.
        val startedGeneration = songSyncGenerationOf(song.videoId)
        // Off by default: neither the check nor the override it writes may
        // change a downloaded file's lyrics on this build.
        if (!SyncSafetyPolicy.recheckAllowed(AppSettings.syncSafetySwitches)) {
            TrackLog.d(TAG, "lyrics-sync: download re-check skipped (switch off)", song.videoId)
            return null
        }
        val localUri = song.localUri?.takeIf { it.isNotBlank() } ?: run {
            TrackLog.d(TAG, "lyrics-sync: download re-check skipped (no local file)", song.videoId)
            return null
        }
        if (lines.none { it.timeMs > 0 }) {
            TrackLog.d(TAG, "lyrics-sync: download re-check skipped (untimed lines)", song.videoId)
            return null
        }
        if (!com.music.bitchord.data.lyrics.Deepgram.usable) {
            TrackLog.d(TAG, "lyrics-sync: download re-check skipped (deepgram unavailable)", song.videoId)
            return null
        }
        val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
        // This check listens to the file's own audio, so it is the record-timed
        // song check even when the row is a video/cover. Its answer is stored
        // under the key the screen reads for that row.
        val videoRow = isVideoRow(song)
        if (!downloadRecheckSongCheckWanted(
                sungLines = sung.size,
                title = title,
                artist = artist,
                texts = sung.map { it.value.text },
            )
        ) {
            TrackLog.d(
                TAG,
                "lyrics-sync: download re-check skipped by guard video=$videoRow " +
                    "sung=${sung.size}",
                song.videoId,
            )
            return null
        }
        val audioSource = if (videoRow) {
            null
        } else {
            audioSourceKey(song.videoId, localFile = true)
        }
        val key = textKey(lines, audioSource)
        val before = store(context).get(song.videoId)
        val beforeMatch = before?.let { e -> e.ai?.match?.takeIf { e.aiTextKey == key } }
        val reason = DownloadedSyncCheck.recheckReason(
            before?.syncCheckVersion,
            providerUnknown = providerUnknown,
            matchPercent = beforeMatch,
        )
        if (reason == DownloadedSyncCheck.Recheck.NONE) {
            TrackLog.d(TAG, "lyrics-sync: download re-check skipped (already checked)", song.videoId)
            return null
        }
        recheckTokens[song.videoId] = reason.token
        // Heard before the gate, so a decode or a request here holds up no other
        // song's answer; the check under the gate reads it back. A failure is left
        // for that check to meet and report.
        hearSongCheck(context, song, lines, key, null, VideoAudio.SONG_CHECK_SECONDS, retry = false)
        return songSyncGate.withLock {
            // A reset may have replaced this file while the check waited here.
            // The version it cleared made it look unverified, but the captured
            // song/lines still name the old recording: writing this answer or
            // its override would put the new file back on the old sync.
            if (songSyncGenerationOf(song.videoId) != startedGeneration) {
                TrackLog.d(TAG, "lyrics-sync: download re-check dropped (file replaced)", song.videoId)
                recheckTokens.remove(song.videoId)
                return@withLock null
            }
            // Another flight may have finished this file while this one waited.
            val entry = store(context).get(song.videoId)
            if (!DownloadedSyncCheck.isUnverified(entry?.syncCheckVersion)) {
                recheckTokens[song.videoId] = DownloadedSyncCheck.Recheck.DONE.token
                return@withLock null
            }
            val existingMatch = entry?.let { e -> e.ai?.match?.takeIf { e.aiTextKey == key } }
            // The file's own lyrics first: they are what is shown, so an
            // answer fitted to them needs no second interpretation. No audio
            // or a 429 leaves the version unset so the next play may try again.
            val own = runSongCheck(context, song, lines, key, ENGINE_DEEPGRAM_WHISPER) ?: return@withLock null
            var best = own
            var bestFresh: LyricsRepository.Result? = null
            // A fresh sheet is worth a request when the stored answer is
            // unknown or poor — including a first check that could not fit
            // these words at all.
            val knownMatch = existingMatch ?: own.match
            val fetchFresh = DownloadedSyncCheck.shouldFetchNewLyrics(providerUnknown, knownMatch) ||
                (existingMatch == null && own.fitted == null)
            if (fetchFresh) {
                val fresh = fetchDownloadedLyrics(
                    song, title, artist, durationMs, album, alternatives, artistKnown, artistAliases,
                )
                if (fresh != null && fresh.lines != lines) {
                    val other = runSongCheck(
                        context,
                        song,
                        fresh.lines,
                        textKey(fresh.lines, audioSource),
                        ENGINE_DEEPGRAM_WHISPER,
                    )
                    if (other != null && (other.match ?: 0) > (best.match ?: 0)) {
                        best = other
                        bestFresh = fresh
                    }
                }
            }
            val result = best
            val chosen = result.fitted
            val checkClock = result.clock
            val freshKey = bestFresh?.let { textKey(it.lines, audioSource) } ?: key
            val decision = DownloadedSyncCheck.decide(
                storedVersion = entry?.syncCheckVersion,
                existingMatchPercent = existingMatch,
                existingKey = key,
                ownMatchPercent = own.match,
                freshMatchPercent = if (bestFresh != null) result.match else null,
                freshKey = freshKey,
                // A no-answer did not reach an engine, so the version stays
                // unset and the next play retries. No-words/no-fit did run.
                ownCheckAnswered = result !== own || own.result != "no-answer",
                // A refusal carries a match percent with no fitted sync. It
                // has not verified the file, so it must not be recorded as an
                // answer or make the old sync look replaceable.
                ownApplied = own.fitted != null,
            )
            if (decision.replace && decision.winner == DownloadedSyncCheck.Winner.FRESH) {
                // The fresh sheet's fit belongs to its own lines. Both have to
                // land together: the override saved for this video, and the
                // sync saved under the fresh text's key. The audio file itself
                // is never touched — it may be the one playing. A failed write
                // records nothing, so the next playback tries again.
                val fresh = bestFresh
                val embeddable = fresh?.let {
                    LyricsTag.embeddable(it.source, song.videoId, it.lines)
                }
                val written = chosen != null && fresh != null && embeddable != null &&
                    LyricsOverrideStore.write(
                        context = context,
                        videoId = song.videoId,
                        lines = fresh.lines,
                        source = fresh.source,
                        kind = LyricsOverrideFile.Kind.RECHECK,
                    )
                if (!written) {
                    recheckTokens[song.videoId] = reason.token
                    return@withLock null
                }
            }
            store(context).update(song.videoId) {
                val keep = if (decision.replace && chosen != null) {
                    it.copy(
                        clockVersion = CLOCK_VERSION,
                        aiTextKey = decision.key ?: key,
                        ai = savedOf(chosen),
                        aiShiftMs = null,
                        aiStarts = null,
                        aiFailedKey = null,
                        heardWords = result.heard.take(MAX_HEARD_WORDS)
                            .map { word -> SavedWord(t = word.text, s = word.startMs, e = word.endMs) },
                        aiClock = checkClock.saved(),
                        heardClock = checkClock.saved(),
                        heardCheckKey = null,
                        heardCheckWords = emptyList(),
                        heardCheckClock = null,
                        aiPreferred = false,
                        biasSampled = false,
                        aiEngine = ENGINE_DEEPGRAM_WHISPER,
                        songRetryKey = null,
                        songRetryAtMs = null,
                    )
                } else {
                    it
                }
                if (decision.recordVersion) {
                    keep.copy(syncCheckVersion = DownloadedSyncCheck.SYNC_CHECK_VERSION)
                } else {
                    keep
                }
            }
            if (decision.replace && chosen != null) {
                songLog(
                    song.videoId,
                    "applied",
                    result.match,
                    result.shift,
                    result.seconds,
                    words = result.words,
                    http = result.http,
                )
            }
            recheckTokens[song.videoId] = DownloadedSyncCheck.Recheck.DONE.token
            _changes.tryEmit(song.videoId)
            if (decision.replace && decision.winner == DownloadedSyncCheck.Winner.FRESH) {
                bestFresh
            } else {
                null
            }
        }
    }

    /** A fresh timed sheet for a downloaded file, from the user's chosen sources only. */
    private suspend fun fetchDownloadedLyrics(
        song: Song,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        alternatives: List<Pair<String, String>>,
        artistKnown: Boolean,
        artistAliases: List<String>,
    ): LyricsRepository.Result? {
        val sources = if (AppSettings.syncedLyrics.value) AppSettings.lyricsSources.value else emptySet()
        if (sources.isEmpty()) return null
        return runCatching {
            LyricsRepository.lyrics(
                videoId = song.videoId,
                title = title,
                artist = artist,
                durationMs = durationMs,
                album = album,
                sources = sources,
                order = AppSettings.lyricsSourceOrder.value,
                prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                isVideo = isVideoRow(song),
                alternatives = alternatives,
                artistKnown = artistKnown,
                artistAliases = artistAliases,
            )?.takeIf { found ->
                found.lines.count { it.text.isNotBlank() } >= MIN_AI_LINES &&
                    found.lines.any { it.timeMs > 0 }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            TrackLog.w(TAG, "download re-check lookup failed: ${it.message}", song.videoId)
        }.getOrNull()
    }

    /**
     * The settings button: forgets every stored sync-check version, so each
     * downloaded file is checked once more the next time it is played. The
     * stored lyrics and sync themselves are left alone until a better answer
     * is actually found; nothing is re-processed in the background.
     */
    suspend fun resetDownloadedSync(context: Context) {
        recheckTokens.clear()
        store(context).clearSyncCheckVersions()
        TrackLog.i("BitChord", "lyrics-sync: downloaded sync versions cleared")
    }

    private const val MIN_AI_LINES = 3

    /** A video is heard as far as its sound has landed, up to this. */
    private const val AI_HEAR_SECONDS = 420.0

    /** ...and not before this much has: a first verse to fit an offset to. */
    private const val AI_HEAR_LEAST_SECONDS = 40.0

    /** A song's first pass goes with this much when the window is slow to land. */
    private const val SONG_FIRST_LEAST_SECONDS = 24.0

/** Placed lines from which their own agreement, not the sheet's length, judges a fit. */
private const val FIRM_FIT_LINES = 15

    /** A transcript longer than this is cut: enough for any song, bounded on disk. */
    private const val MAX_HEARD_WORDS = 4_000

    // ---- How late Gemini runs ----------------------------------------------------------

    /**
     * Gemini places a line where it hears it start, and hears it late — about
     * half a second to a second, the same way song after song. Where a video
     * has both its answer and a measurement (right to a tenth of a second),
     * the two are compared: the median gap over the song is one sample, and
     * the median of the last [BIAS_SAMPLES] samples is taken off every AI
     * answer from then on. Nothing is taken off until [BIAS_MIN_SAMPLES] songs
     * agree, and never more than [BIAS_MAX_MS].
     */
    private suspend fun sampleBias(
        context: Context,
        videoId: String,
        lines: List<LyricLine>? = null,
        linesKey: Int? = null,
    ) {
        val entry = store(context).get(videoId) ?: return
        if (entry.biasSampled || entry.autoVersion != AUTO_VERSION) return
        val measured = entry.auto?.toAdjustment(byAi = false) ?: return
        val ai = entry.ai?.toAdjustment(byAi = true) ?: return
        // Only a measurement that clearly held: most of the song agreeing.
        if ((measured.matchPercent ?: 0) < 60 || (ai.matchPercent ?: 0) < 50) return
        // The words must belong to the lines being scored: after a lyrics-source
        // change aiTextKey names another transcript, and comparing it with these
        // starts would record or skip a bias sample for the wrong reason.
        val heard = linesKey?.let { savedHeardWords(entry, it) }.orEmpty()
        val measuredScore = if (lines != null && heard.isNotEmpty()) {
            BaseChoicePolicy.score(
                choiceStarts(lines, measured),
                BaseChoicePolicy.heardStarts(lines.map { it.text }, heard),
            )
        } else {
            null
        }
        // A measurement that disagrees with the words actually heard is not
        // the reference Gemini's lateness is measured against: qzXa kept a
        // measured +1.77 s that disagreed with the transcript and taught the
        // bias -1.72 s. Only a measurement on time with the words may sample.
        if (measuredScore == null || !BaseChoicePolicy.latenessSampleAllowed(measuredScore)) {
            TrackLog.d(TAG, "lateness sample skipped: measured disagrees with heard words", videoId)
            return
        }
        val gaps = (BIAS_FROM_MS..BIAS_TO_MS step BIAS_STEP_MS).map { t -> ai.map.map(t) - measured.map.map(t) }.sorted()
        if (gaps.isEmpty()) return
        val median = gaps[gaps.size / 2]
        val spread = gaps[gaps.size * 3 / 4] - gaps[gaps.size / 4]
        store(context).update(videoId) { it.copy(biasSampled = true) }
        // A different take, a cut the straight AI line can't follow: no lesson in it.
        if (abs(median) > BIAS_SAMPLE_LIMIT_MS || spread > BIAS_SPREAD_MS) return
        val engine = entry.aiEngine ?: ENGINE_GEMINI
        val prefs = context.applicationContext.getSharedPreferences(BIAS_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val kept = prefs.getString(biasKey(engine), null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            val next = (kept + median).takeLast(BIAS_SAMPLES)
            prefs.edit().putString(biasKey(engine), next.joinToString(",")).apply()
            cachedBias.remove(engine)
        }
        TrackLog.d(TAG, "$engine lateness sample ${median} ms (spread ${spread} ms)", videoId)
    }

    /** Each engine runs late its own way: learned apart. */
    private fun biasKey(engine: String) = if (engine == ENGINE_GEMINI) BIAS_KEY else "${BIAS_KEY}_${engine.lowercase()}"

    private val cachedBias = ConcurrentHashMap<String, Long>()

    /** What to take off [engine]'s answers, ms; 0 until enough songs have taught it. */
    private fun aiBias(context: Context, engine: String): Long {
        // Learned but not applied. Scored on the phone (10-26, Whisper judge),
        // every AI-timed line came out earlier with the learned ~200 ms taken
        // off than without it: FRIENDS -0.40 s vs -0.19, When We Were Young
        // -0.21 vs 0.00, Dear -0.23 vs -0.03. One song's measured gap ran the
        // other way (-205 ms), so a single global lateness cannot fix a song.
        // Samples are still collected; the log keeps bias=0 until it is proved.
        if (!AI_BIAS_APPLIED) return 0L
        cachedBias[engine]?.let { return it }
        val samples = context.applicationContext.getSharedPreferences(BIAS_PREFS, Context.MODE_PRIVATE)
            .getString(biasKey(engine), null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.sorted()
        val bias = if (samples.size < BIAS_MIN_SAMPLES) 0L else samples[samples.size / 2].coerceIn(-BIAS_MAX_MS, BIAS_MAX_MS)
        cachedBias[engine] = bias
        return bias
    }

    const val ENGINE_DEEPGRAM = AiTimingPolicy.DEEPGRAM
    const val ENGINE_WHISPER = AiTimingPolicy.WHISPER
    const val ENGINE_GEMINI = AiTimingPolicy.GEMINI
    const val ENGINE_DEEPGRAM_WHISPER = AiTimingPolicy.DEEPGRAM_WHISPER

    /**
     * Whether the free measurement has already timed [videoId] (or found it
     * needs nothing) — then an AI request would only spend an upload.
     */
    suspend fun measuredTiming(context: Context, videoId: String): Boolean =
        hasAutoAnswer(store(context).get(videoId))

    /**
     * One video in [CALIBRATION_EVERY] still gets an AI answer alongside its
     * measurement — the pairs [aiBias] learns each engine's lateness from —
     * where every other one asks AI only once the measurement has nothing.
     * Picked by id, so the same videos are the samples every time.
     *
     * This is only the id rule; [claimCalibrationSample] is what must be
     * called before asking, so a video is sampled once ever rather than once
     * per playback or lyrics delivery.
     */
    fun calibrationSample(videoId: String): Boolean = Math.floorMod(videoId.hashCode(), CALIBRATION_EVERY) == 0

    /**
     * Claims [videoId]'s one calibration sample, remembered in the kept entry
     * like every other timing fact. True for the caller that got it, false
     * once it has been taken — by this session or an earlier one. Without
     * this a calibration video asked Gemini, Deepgram and Whisper again on
     * every lyrics delivery and screen open, and the saved pair (when the
     * measurement had nothing to compare) was never marked at all.
     */
    suspend fun claimCalibrationSample(context: Context, videoId: String): Boolean {
        if (!calibrationSample(videoId)) {
            TrackLog.d(TAG, "lyrics-sync: calibration skipped (not sampled)", videoId)
            return false
        }
        if (store(context).get(videoId)?.calibrationSampled == true) {
            TrackLog.d(TAG, "lyrics-sync: calibration skipped (already sampled)", videoId)
            return false
        }
        var claimed = false
        store(context).update(videoId) { entry ->
            if (entry.calibrationSampled) {
                entry
            } else {
                claimed = true
                entry.copy(calibrationSampled = true)
            }
        }
        return claimed
    }

    private const val CALIBRATION_EVERY = 8

    /**
     * Whether an automatic request still has an engine worth spending on
     * these lyrics — the one question every automatic caller should ask.
     * Callers used to combine [hasAi], the kept "already failed" key and
     * "wants Whisper" themselves; those answers disagreed with each other and
     * with what the cache actually holds, so an accepted 100% answer could
     * still be sent to Whisper a minute later. [AiTimingPolicy] now reads that
     * key through [knowledge], which is why there is no separate accessor.
     */
    suspend fun wantsAi(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        audioSource: String? = null,
    ): Boolean {
        val gaveUp = measurementGaveUp(context, videoId)
        // Independent of gaveUp: a measurement that gave up and then got an
        // accepted answer the heard words contradict is exactly the case this
        // check exists for.
        val unsettled = timingUnsettled(context, videoId, lines, audioSource)
        return AiTimingPolicy.shouldAskAgain(
            knowledge(store(context).get(videoId)),
            textKey(lines, audioSource),
            manual = false,
            measurementGaveUp = gaveUp || unsettled,
            unsettled = unsettled,
        )
    }

    private const val BIAS_PREFS = "lyrics_timing"
    private const val BIAS_KEY = "ai_bias_samples"
    private const val BIAS_SAMPLES = 40
    private const val AI_BIAS_APPLIED = false
    private const val BIAS_MIN_SAMPLES = 3
    private const val BIAS_MAX_MS = 1_500L
    private const val BIAS_SAMPLE_LIMIT_MS = 3_000L
    private const val BIAS_SPREAD_MS = 1_200L
    private const val BIAS_FROM_MS = 20_000L
    private const val BIAS_TO_MS = 150_000L
    private const val BIAS_STEP_MS = 5_000L

    /**
     * Gemini's per-line starts as one straight line — see [TimingFit]. Lines
     * it said aren't sung (a short version) are left out of the fit; it needs
     * at least a quarter of the lines, and more of its answers on the line
     * than off it.
     */
    internal fun fitAi(
        lines: List<LyricLine>,
        indices: List<Int>,
        starts: List<Long?>,
        /** Lines the transcript could spell at all (`Deepgram.spellableLines`); the agreement's base. */
        spellable: Int = indices.size,
    ): Adjustment.Mapped? {
        val points = indices.zip(starts).mapNotNull { (index, start) -> start?.let { lines[index].timeMs to it } }
        if (points.size < MIN_AI_LINES || points.size * 4 < indices.size) return null
        val line = TimingFit.fit(points, minPoints = MIN_AI_LINES) ?: return null
        // Too few spellable lines is no base to judge on: fall back to all of them.
        val spelled = spellable.takeIf { it >= MIN_AI_LINES }?.coerceAtMost(indices.size) ?: indices.size
        // A line the transcript never reached does not disagree. When many lines
        // were placed and they nearly all lie on one line within INLIER_MS, the
        // answer is about this recording however much singing it missed (11-06:
        // jDrP5Pb2f_o 26 of 26 placed lines, X1xQhbffaN8 23, refused at 68 % of the
        // sheet with the words 0.5 s late for the whole song).
        val base = if (line.answered >= FIRM_FIT_LINES && line.inliers * 10 >= line.answered * 9) {
            minOf(spelled, line.answered)
        } else {
            spelled
        }
        val match = (line.inliers * 100.0 / base).roundToInt().coerceIn(0, 100)
        return Adjustment.Mapped(line.map, byAi = true, rate = line.rate, matchPercent = match)
    }

    /**
     * What a word-stamped transcript says about these lines: the straight line
     * when it passes [mayApply] and no real structure overrules it, otherwise the
     * stretches of [TimingPieces] when those pass. When neither passes, the
     * straight line goes back so the caller refuses it exactly as it always has.
     * Gemini's answers stay on [fitAi]: they are not matched letters.
     */
    internal fun fitHeard(
        videoId: String?,
        lines: List<LyricLine>,
        indices: List<Int>,
        placement: LyricsWordAlign.Placement,
        spellable: Int = indices.size,
        mayApply: (Adjustment.Mapped) -> Boolean,
    ): Adjustment.Mapped? {
        val straight = fitAi(lines, indices, placement.starts, spellable)
        val outcome = TimingPieces.fit(
            indices.map { lines[it].timeMs },
            indices.map { lines[it].text },
            placement,
            spellable,
        )
        val found = outcome.result
        val pieces = found?.let { Adjustment.Mapped(it.map, byAi = true, rate = it.rate, matchPercent = it.agreePercent) }
        val straightHolds = straight != null && mayApply(straight)
        val chosen = when {
            found == null || pieces == null -> straight
            straightHolds && !TimingPieces.overrules(straight!!.map, found) -> straight
            mayApply(pieces) -> pieces
            else -> straight
        }
        TrackLog.d(
            TAG,
            "sync pieces: " + (
                found?.let { "sizes=${it.sizes} jumps=${it.jumpsMs} rate=${"%.3f".format(java.util.Locale.ROOT, it.rate)} agree=${it.agreePercent}" }
                    ?: "refused=${outcome.refused}"
                ) + " line=${straight?.matchPercent} used=${if (chosen === pieces && pieces != null) "pieces" else "line"}",
            videoId,
        )
        return chosen
    }

    // ---- By hand ----------------------------------------------------------------------

    /** The listener's own correction for [videoId], ms; positive shows the lyrics later. */
    suspend fun manual(context: Context, videoId: String): Long = store(context).get(videoId)?.manualMs ?: 0L

    /**
     * The words a transcript heard in [videoId], whichever kept list covers
     * the most of the song. Used to check that a provider's lyrics are the
     * words actually sung — see [com.music.bitchord.data.lyrics.LyricsMismatch].
     */
    suspend fun heardWordsFor(context: Context, videoId: String): List<LyricsWordAlign.Heard> {
        val entry = store(context).get(videoId) ?: return emptyList()
        val saved = listOf(entry.heardWords, entry.refineHeardWords, entry.heardCheckWords)
            .maxByOrNull { it.size }
            .orEmpty()
        return saved.map { LyricsWordAlign.Heard(it.t, it.s, it.e) }
    }

    /** Keeps [ms] as the correction for [videoId]; 0 removes it. */
    suspend fun setManual(context: Context, videoId: String, ms: Long) {
        val value = ms.coerceIn(-MAX_MANUAL_MS, MAX_MANUAL_MS)
        store(context).update(videoId) {
            it.copy(
                clockVersion = CLOCK_VERSION,
                manualMs = value.takeIf { v -> v != 0L },
                manualClock = playingClock(videoId).saved(),
            )
        }
        _changes.tryEmit(videoId)
    }

    /** Ties an AI answer to the words it was made for: other lyrics, other answer. */
    private fun textKey(lines: List<LyricLine>, audioSource: String? = null): Int {
        val words = lines.joinToString("\n") { it.text }.hashCode()
        return audioSource?.let { 31 * it.hashCode() + words } ?: words
    }

    /**
     * Which sound a song check is listening to.
     *
     * A downloaded file and the song streamed from the network can be two
     * different recordings under the same row id — that is the whole reason
     * the length verdict exists. A timing answer made against one must not be
     * applied to the other, so the check's key names the source as well as the
     * words. Videos keep their old key: their own file is the video.
     */
    fun audioSourceKey(videoId: String, localFile: Boolean): String =
        "$videoId|${if (localFile) "file" else "stream"}"

    /**
     * Whether the video/foreign-file timing pass may run at all.
     *
     * Every term is a reason not to: the lines are not adjustable, the caller
     * did not ask for measurement, it is not the moment to measure, the AI
     * button is taking its answer back ([cancelAi] false), or the playing
     * stream is a substitute with a different clock. The last two were added
     * after the pass could still spend an engine on a video whose AI answer
     * the listener had just removed, or on a substitute the comment beside
     * the pass says must not be checked.
     */
    internal fun videoTimingMayRun(
        adjustable: Boolean,
        measure: Boolean,
        measureNow: Boolean,
        cancelAi: Boolean,
        substituted: Boolean,
    ): Boolean = adjustable && measure && measureNow && cancelAi && !substituted

    /** The kept entry as the pure ask-again rule sees it; a missing entry is an empty one. */
    private fun knowledge(entry: Entry?): AiTimingKnowledge = entry?.let {
        AiTimingKnowledge(
            answerKey = it.aiTextKey,
            answerEngine = it.aiEngine,
            answerMatch = it.ai?.match,
            failedKey = it.aiFailedKey,
            attemptedKey = it.aiAttemptedKey,
            attemptedEngines = it.aiAttemptedEngines.toSet(),
            whisperKey = it.whisperKey,
            rejectedKey = it.heardCheckKey?.takeIf { _ -> it.heardCheckWords.isNotEmpty() },
        )
    } ?: AiTimingKnowledge()

    // ---- Kept on disk --------------------------------------------------------

    /**
     * One word as the transcript heard it, kept compactly: the entry file holds
     * thousands of videos, and a word list is the bulkiest thing a timing entry
     * ever carries. Times are on the video's own clock, as heard.
     */
    @Serializable
    internal data class SavedWord(val t: String, val s: Long, val e: Long)

    /** A [LyricsTimeMap] with what was said about it. */
    @Serializable
    internal data class SavedMap(
        val knots: List<Long>,
        val headRate: Double,
        val tailRate: Double,
        val rate: Double,
        val match: Int? = null,
    ) {
        fun toAdjustment(byAi: Boolean): Adjustment.Mapped? =
            LyricsTimeMap.unflatten(knots, headRate, tailRate)?.let { Adjustment.Mapped(it, byAi, rate, match) }
    }

    private fun savedOf(result: ChromaAlignment.Result) = SavedMap(
        knots = result.map.flatten(),
        headRate = result.map.headRate,
        tailRate = result.map.tailRate,
        rate = result.rate,
        match = result.matchPercent,
    )

    private fun savedOf(adjustment: Adjustment.Mapped) = SavedMap(
        knots = adjustment.map.flatten(),
        headRate = adjustment.map.headRate,
        tailRate = adjustment.map.tailRate,
        rate = adjustment.rate,
        match = adjustment.matchPercent,
    )

    @Serializable
    internal data class Entry(
        /** The clock format this entry was written with; 0 is a record from before clocks were kept. */
        val clockVersion: Int = 0,
        // ko-21/22: the loudness measurement (superseded, see AUTO_VERSION)
        // and per-line AI answers.
        val autoChecked: Boolean = false,
        val autoShiftMs: Long? = null,
        val aiTextKey: Int? = null,
        val aiShiftMs: Long? = null,
        val aiStarts: List<Long>? = null,
        // ko-23
        val autoVersion: Int = 0,
        val auto: SavedMap? = null,
        /** The audio clock [auto] was measured on; null on entries saved before clocks. */
        val autoClock: String? = null,
        /** The recording [auto] was measured against; null on entries saved before this was kept. */
        val autoOriginal: String? = null,
        /** The whole video was measured, or there was nothing to measure it against. */
        val autoComplete: Boolean = false,
        /** Measured, and the video keeps the record's time: nothing to move. */
        val autoNoChange: Boolean = false,
        /** Measured against only the opening of the record so far. */
        val autoRecordPartial: Boolean = false,
        /** How much of the video (bytes, seconds) the last measurement read. */
        val autoVideoBytes: Long = 0L,
        val autoVideoSeconds: Double = 0.0,
        val ai: SavedMap? = null,
        /** The audio clock [ai] was measured on; null on entries saved before clocks. */
        val aiClock: String? = null,
        /** The automatic AI fallback got nothing for the lyrics with this key. */
        val aiFailedKey: Int? = null,
        val manualMs: Long? = null,
        /** The audio clock the listener's nudge was made on; null on entries saved before clocks. */
        val manualClock: String? = null,
        /** See [note]. */
        val autoNote: String? = null,
        /** Which way of searching for the original the measurement used; see [searchOutdated]. */
        val searchVersion: Int = 0,
        /** The AI answer was asked for by hand, so it beats the measurement — see [baseOf]. */
        val aiPreferred: Boolean = false,
        /** This video's AI answer and measurement have been compared for [aiBias]. */
        val biasSampled: Boolean = false,
        /**
         * Who gave the AI answer: [ENGINE_DEEPGRAM], [ENGINE_WHISPER],
         * [ENGINE_GEMINI], or [ENGINE_DEEPGRAM_WHISPER] for a song check
         * (null: Gemini, before ko-35).
         */
        val aiEngine: String? = null,
        /** The lyrics (text key) Whisper was last asked about, answered or not: asked once. */
        val whisperKey: Int? = null,
        /** The words behind the AI answer, on the video's clock; see [timing]. */
        val heardWords: List<SavedWord> = emptyList(),
        /** The audio clock [heardWords] was heard on; null on entries saved before clocks. */
        val heardClock: String? = null,
        /**
         * Words heard while checking a sheet they did not fit. They are not a
         * timing answer and are never applied; they let a later lyrics lookup
         * see that the shown sheet is not what this video sings.
         */
        val heardCheckKey: Int? = null,
        val heardCheckWords: List<SavedWord> = emptyList(),
        /** The audio clock [heardCheckWords] was heard on; null on entries saved before clocks. */
        val heardCheckClock: String? = null,
        /**
         * The line-level refine: the offset [RefinePolicy] found, the drift
         * per minute when the residuals followed a straight line, how many
         * lines agreed and their spread. [refineMs] is null when it did not
         * apply. The lines themselves are re-derived from the words on every
         * [timing], so a changed base is followed without a stale move.
         */
        val refineMs: Long? = null,
        val refineSlopePerMin: Double = 0.0,
        val refineLines: Int = 0,
        val refineMad: Long? = null,
        /**
         * The move was measured across the whole song ([RefinePolicy.Model.wide]),
         * so it may reach the wide cap. Null on entries saved before the model
         * was kept: a constant past the narrow cap was the wide window's move.
         */
        val refineWide: Boolean? = null,
        /** The audio clock the refine result was computed on; null on entries saved before clocks. */
        val refineClock: String? = null,
        /** The lyrics (text key) the refine transcript belongs to, and its words on the video's clock. */
        val refineHeardKey: Int? = null,
        val refineHeardWords: List<SavedWord> = emptyList(),
        /** The audio clock [refineHeardWords] was heard on; null on entries saved before clocks. */
        val refineHeardClock: String? = null,
        /** How much of the song [refineHeardWords] covers, in seconds; 0 on entries saved before this was kept. */
        val refineHeardSeconds: Double = 0.0,
        /** The once-only longer listen has been made (or the song ends before it could help); see [RefineExtendPolicy]. */
        val refineHeardExtended: Boolean = false,
        /**
         * The lyrics the last automatic request was for, and the engines it
         * has spent on them; see [AiTimingPolicy]. A song check's key also
         * names the audio source, so a file-to-stream swap is a new request.
         */
        val aiAttemptedKey: Int? = null,
        val aiAttemptedEngines: List<String> = emptyList(),
        /**
         * A 429 did not spend the engine; the song is only held until
         * [songRetryAtMs], then the next playback asks again. The key is the
         * lyrics text the hold was made for.
         */
        val songRetryKey: Int? = null,
        val songRetryAtMs: Long? = null,
        /**
         * The [DownloadedSyncCheck.SYNC_CHECK_VERSION] a downloaded file's
         * stored sync was last checked with; 0 means never. Only this version
         * is cleared by the settings button, so each file is checked once more
         * on its next play rather than all at once.
         */
        val syncCheckVersion: Int = 0,
        /** This video's once-ever calibration sample has been claimed; see [claimCalibrationSample]. */
        val calibrationSampled: Boolean = false,
    )

    @Serializable
    private data class Saved(
        val entries: Map<String, Entry> = emptyMap(),
        /** The clock-tag format this file was written with; 0 is a file from before clocks. */
        val version: Int = 0,
    )

    private class Store(private val file: File?) {
        private val mutex = Mutex()
        private var entries: LinkedHashMap<String, Entry>? = null

        private suspend fun loaded(): LinkedHashMap<String, Entry> {
            entries?.let { return it }
            val read = file?.let {
                withContext(Dispatchers.IO) {
                    runCatching { json.decodeFromString(Saved.serializer(), it.readText()).entries }.getOrNull()
                }
            }
            return LinkedHashMap(read.orEmpty()).also { entries = it }
        }

        suspend fun get(videoId: String): Entry? = mutex.withLock { loaded()[videoId] }

        suspend fun update(videoId: String, change: (Entry) -> Entry) {
            mutex.withLock {
                val map = loaded()
                map[videoId] = change(map.remove(videoId) ?: Entry())
                while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
                dirty = true
            }
            scheduleSave()
        }

        /**
         * [update], but only when the entry still equals [expected].
         *
         * A refine reads the entry, computes away from the lock and writes
         * back later; another path (a newer refine, an AI answer, a hand
         * correction) may have saved this video meanwhile. The comparison and
         * the write share [update]'s lock, so an older result cannot clear or
         * overwrite the newer correction. False means nothing was written.
         */
        suspend fun updateIfUnchanged(
            videoId: String,
            expected: Entry,
            change: (Entry) -> Entry,
        ): Boolean {
            var saved = false
            mutex.withLock {
                val map = loaded()
                val current = map[videoId] ?: Entry()
                if (current != expected) return@withLock
                // Reinsert at the end like [update], so the entry just written
                // cannot be the oldest and be evicted by the loop below in the
                // same call (an oversized restored backup).
                map.remove(videoId)
                map[videoId] = change(current)
                while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
                dirty = true
                saved = true
            }
            if (saved) scheduleSave()
            return saved
        }

        /** Forgets every file's sync-check version, leaving the stored sync itself in place. */
        suspend fun clearSyncCheckVersions() {
            mutex.withLock {
                val map = loaded()
                var changed = false
                for ((videoId, entry) in map) {
                    if (entry.syncCheckVersion != 0) {
                        map[videoId] = entry.copy(syncCheckVersion = 0)
                        changed = true
                    }
                }
                if (changed) dirty = true
            }
            if (dirty) scheduleSave()
        }

        @Volatile private var dirty = false
        private val saving = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Replaces the store's file with a backup's copy, or removes it.
         *
         * Runs under the same [mutex] as [update] and [scheduleSave] so a
         * debounced save cannot rename its `.tmp` over the restored file, and
         * resets the in-memory copy so the next read sees the backup instead of
         * the pre-import entries.
         */
        suspend fun restore(text: String?): Boolean = mutex.withLock {
            val file = file ?: return@withLock false
            runCatching {
                if (text == null) {
                    file.delete()
                } else {
                    // Read before replacing so a malformed section cannot cost
                    // the device the timing memory it already had.
                    json.decodeFromString(Saved.serializer(), text)
                    file.parentFile?.mkdirs()
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.writeText(text)
                    if (!temporary.renameTo(file)) {
                        temporary.copyTo(file, overwrite = true)
                        temporary.delete()
                    }
                }
                true
            }.getOrDefault(false).also { ok ->
                if (ok) {
                    entries = null
                    dirty = false
                }
            }
        }

        /**
         * Word lists stay in memory and in this one file only for the songs
         * played last. Whole-song transcripts at every song ever played would
         * run to tens of megabytes rewritten on each save; [SongHearing] keeps
         * them per song, and a play of an older one reads them back from there
         * for free ([ensureRefineHeard]). Its answer and maps stay.
         */
        private fun stripOldWords(map: LinkedHashMap<String, Entry>) {
            fun Entry.hasWords() =
                heardWords.isNotEmpty() || heardCheckWords.isNotEmpty() || refineHeardWords.isNotEmpty()
            var over = map.values.count { it.hasWords() } - WORD_LISTS_KEPT
            if (over <= 0) return
            for (item in map.entries) {
                if (over <= 0) break
                val entry = item.value
                if (!entry.hasWords()) continue
                item.setValue(
                    entry.copy(
                        heardWords = emptyList(),
                        heardCheckKey = null,
                        heardCheckWords = emptyList(),
                        heardCheckClock = null,
                        refineHeardKey = null,
                        refineHeardWords = emptyList(),
                        refineHeardClock = null,
                        refineHeardSeconds = 0.0,
                        refineHeardExtended = false,
                    ),
                )
                over--
            }
        }

        /**
         * Written a moment later, off the main thread, once for however many
         * changes came in meanwhile: the file holds every video's maps and
         * can run to megabytes, and tapping the sync buttons changes it ten
         * times a second.
         */
        private fun scheduleSave() {
            if (file == null) return
            if (!saving.compareAndSet(false, true)) return
            saveScope.launch {
                try {
                    delay(SAVE_DELAY_MS)
                    mutex.withLock {
                        dirty = false
                        stripOldWords(loaded())
                        val snapshot = Saved(LinkedHashMap(loaded()), version = CLOCK_VERSION)
                        // The write stays under the lock as well: a restore
                        // holds it while it replaces the file, so whichever
                        // wins the lock is the final content. Writing outside
                        // it let a snapshot captured before an import rename
                        // its old text over the restored file.
                        runCatching {
                            val target = file
                            val text = json.encodeToString(Saved.serializer(), snapshot)
                            val temporary = File(target.parentFile, "${target.name}.tmp")
                            temporary.writeText(text)
                            if (!temporary.renameTo(target)) {
                                temporary.copyTo(target, overwrite = true)
                                temporary.delete()
                            }
                        }
                    }
                } finally {
                    saving.set(false)
                    if (dirty) scheduleSave()
                }
            }
        }
    }

    /** The whole timing memory as text, for a device-migration backup. */
    internal suspend fun exportBackupText(context: Context): String? = withContext(Dispatchers.IO) {
        val directory = (context.applicationContext ?: context).filesDir ?: return@withContext null
        val file = File(directory, "lyrics_timing_v1.json")
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
    }

    /** Replaces the timing memory with a backup's copy; null removes it. */
    internal suspend fun restoreBackupText(context: Context, text: String?): Boolean =
        withContext(Dispatchers.IO) {
            // Through the store so a debounced save in flight is serialised
            // with the replacement instead of racing its own `.tmp` rename.
            store(context).restore(text)
        }

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val SAVE_DELAY_MS = 800L
    private const val WORD_LISTS_KEPT = 200

    private const val MAX_ENTRIES = 3_000
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var store: Store? = null

    private fun store(context: Context): Store {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val file = (context.applicationContext ?: context).filesDir
                ?.let { File(it, "lyrics_timing_v1.json") }
                // Not cached: a directory can appear later, and this store could
                // never save. The next call builds a real one.
                ?: return Store(null)
            return Store(file).also { store = it }
        }
    }
}
