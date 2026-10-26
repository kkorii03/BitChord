package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.ChromaAlignment
import com.music.bitchord.data.lyrics.DownloadedSyncCheck
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsAiGuard
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTimeMap
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.lyrics.RecordingPick
import com.music.bitchord.data.lyrics.RefinePolicy
import com.music.bitchord.data.lyrics.SongCheckWindow
import com.music.bitchord.data.lyrics.SongSyncRetry
import com.music.bitchord.data.lyrics.TimingFit
import com.music.bitchord.data.lyrics.retimedBy
import com.music.bitchord.data.lyrics.timedBy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.HqMatchPolicy
import com.music.bitchord.download.LyricsTag
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.PowerState
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.SyncSafetyPolicy
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.launch
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
 *     a start and a pace ([TimingFit]) — rather than taken one by one, and
 *     refused when they agree on none. On the AI button, or automatically when
 *     the free measurement has nothing (the "가사 타이밍 자동 보정" setting).
 *  3. **By hand** — [setManual]: the listener's own correction for this one
 *     video, on top of whichever of the two is in place.
 *
 * All three are kept per video on disk, so each is paid for once.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object LyricsTiming {

    private const val TAG = "LyricsTiming"

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
    ) {
        val isEmpty: Boolean
            get() = base == null && manualMs == 0L && hq == null &&
                refineMs == 0L && refineSlopePerMin == 0.0

        /** What goes on top of [base]: the hand's correction, less Gemini's lateness. */
        val extraMs: Long get() = manualMs - if (base?.byAi == true) aiBiasMs else 0L

        /** The refine's move at [atMs], capped at ±[RefinePolicy.MAX_SHIFT_MS]. */
        fun refineAt(atMs: Long): Long {
            val move = refineMs + refineSlopePerMin / 60_000.0 * atMs
            return move.roundToLong().coerceIn(-RefinePolicy.MAX_SHIFT_MS, RefinePolicy.MAX_SHIFT_MS)
        }
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
    ): Timing? {
        // A missing entry is not "nothing to do": the stream may still be a
        // verified high-quality copy whose clock differs from YouTube's, and
        // that map applies even when no measurement or AI answer exists.
        val entry = store(context).get(videoId) ?: Entry()
        val base = if (baseAllowed) baseOf(entry, lines, measuredAllowed, isVideo, audioSource) else null
        val engine = if (base?.byAi == true) entry.aiEngine ?: ENGINE_GEMINI else null
        val bias = engine?.let { aiBias(context, it) } ?: 0L
        val key = textKey(lines, audioSource)
        val timing = Timing(
            base = base,
            manualMs = entry.manualMs ?: 0L,
            aiBiasMs = bias,
            aiEngine = engine,
            hq = hqAdjustment(context, videoId),
        )
        // The provider's own line times can stay a fifth of a second early or
        // late even when [base] has already put the record on the video's
        // clock, and a longer video can drift. One constant move or one
        // straight line, learned from the words heard, goes on top of base and
        // HQ and under the listener's own correction.
        val refineWords = refineWords(entry, key, base)
        val refineClock = onHqClock(refineWords, timing)
        val refine = if (base != null && refineClock.isNotEmpty()) {
            RefinePolicy.compute(
                apply(lines, timing.copy(manualMs = 0L, refineMs = 0L, refineSlopePerMin = 0.0)),
                refineClock,
            )
        } else {
            RefinePolicy.Result.NONE
        }
        val refined = timing.copy(
            refineMs = if (refine.applied) refine.medianMs else 0L,
            refineSlopePerMin = if (refine.applied) refine.slopePerMin else 0.0,
        )
        // The transcript is kept on the video's own clock, and a displayed line
        // is map(record) + refine + extraMs; the words are already video time,
        // so the rest is still missing. Read it here rather than bake it into
        // the kept words: a correction by hand, a refine, or a newly learned
        // engine lateness must move the heard words with their line.
        val heard = if (base?.byAi == true && entry.aiTextKey == key) {
            val hqMap = refined.hq?.map
            fun heardAt(ms: Long): Long {
                val onClock = hqMap?.map(ms) ?: ms
                return (onClock + refined.refineAt(onClock) + refined.extraMs).coerceAtLeast(0L)
            }
            entry.heardWords.map { word ->
                val start = heardAt(word.s)
                LyricsWordAlign.Heard(
                    text = word.t,
                    startMs = start,
                    endMs = maxOf(start, heardAt(word.e)),
                )
            }
        } else {
            emptyList()
        }
        return refined.copy(heardWords = heard).takeUnless { it.isEmpty }
    }

    /**
     * The words a refine may compare with: the AI answer's own transcript when
     * that is the base, otherwise the transcript kept for refine (a measured
     * song's first ninety seconds), falling back to any AI words already heard.
     * All are on the video's own clock.
     */
    private fun refineWords(
        entry: Entry,
        key: Int,
        base: Adjustment?,
    ): List<LyricsWordAlign.Heard> {
        val saved = when {
            base?.byAi == true && entry.aiTextKey == key -> entry.heardWords
            entry.refineHeardKey == key -> entry.refineHeardWords
            entry.aiTextKey == key -> entry.heardWords
            else -> emptyList()
        }
        return saved.map { LyricsWordAlign.Heard(it.t, it.s, it.e) }
    }

    /** Heard words moved onto the HQ copy's clock, where [apply] puts the lines after [Timing.hq]. */
    private fun onHqClock(words: List<LyricsWordAlign.Heard>, timing: Timing): List<LyricsWordAlign.Heard> {
        val map = timing.hq?.map ?: return words
        return words.map { word ->
            val start = map.map(word.startMs)
            LyricsWordAlign.Heard(word.text, start, maxOf(start, map.map(word.endMs)))
        }
    }

    private fun baseOf(
        entry: Entry,
        lines: List<LyricLine>,
        measuredAllowed: Boolean = true,
        isVideo: Boolean = false,
        audioSource: String? = null,
    ): Adjustment? {
        val measured = entry.auto?.takeIf { measuredAllowed && entry.autoVersion == AUTO_VERSION }
        // Asked for by hand, over a measurement the listener saw was off: Gemini's.
        if (entry.aiPreferred) aiOf(entry, lines, isVideo, audioSource)?.let { return it }
        // Otherwise the measurement first: when it lines the two up it is right
        // to a tenth of a second, which Gemini's answers never are.
        measured?.toAdjustment(byAi = false)?.let { return it }
        // A measurement that found the video already on the record's clock is
        // an answer too: no shift. Falling through to the AI answer here moved
        // lyrics the record had just shown were in place.
        if (measuredAllowed && entry.autoVersion == AUTO_VERSION && entry.autoNoChange) {
            return Adjustment.Shift(0L, byAi = false)
        }
        return aiOf(entry, lines, isVideo, audioSource)
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
        val baseMap = when (val base = timing.base) {
            null -> null
            is Adjustment.Shift -> LyricsTimeMap.shift(base.ms)
            is Adjustment.Mapped -> base.map
        }
        val hq = timing.hq?.map
        // The HQ map is the second half of the clock: YouTube song version →
        // the copy playing. The refine is a move after both — a constant, or
        // an offset and a drift — capped at ±[RefinePolicy.MAX_SHIFT_MS] even
        // where the transcript only covered the opening. A manual correction
        // belongs on top of all of them, in the clock the listener is hearing.
        val mapped = if (hq == null) {
            lines.timedBy(baseMap)
        } else {
            lines.timedBy(baseMap).timedBy(hq)
        }
        return mapped.retimedBy { ms ->
            (ms + timing.refineAt(ms) + timing.extraMs).coerceAtLeast(0L)
        }
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
    ) {
        val base = timing.base ?: return
        val entry = store(context).get(videoId) ?: Entry()
        val key = textKey(lines, audioSource)
        val words = refineWords(entry, key, base)
        if (words.isEmpty()) return
        val result = RefinePolicy.compute(
            apply(lines, timing.copy(manualMs = 0L, refineMs = 0L, refineSlopePerMin = 0.0)),
            onHqClock(words, timing),
        )
        val appliedMs = if (result.applied) result.medianMs else null
        val appliedSlope = if (result.applied) result.slopePerMin else 0.0
        if (entry.refineMs == appliedMs && entry.refineSlopePerMin == appliedSlope &&
            entry.refineLines == result.lines && entry.refineMad == result.madMs
        ) {
            return
        }
        store(context).update(videoId) {
            it.copy(
                refineMs = appliedMs,
                refineSlopePerMin = appliedSlope,
                refineLines = result.lines,
                refineMad = result.madMs,
            )
        }
        val baseLabel = if (base.byAi) "ai" else "measured"
        TrackLog.i(
            "BitChord",
            "lyrics-refine: $videoId lines=${result.lines} median=${result.medianMs}ms " +
                "mad=${result.madMs}ms slope=${result.slopePerMin.roundToLong()} " +
                "model=${if (result.applied && result.slopePerMin != 0.0) "linear" else "const"} " +
                "applied=${if (result.applied) "y" else "n"} base=$baseLabel",
            videoId,
        )
    }

    /** Whether the words a refine needs are already kept for these lyrics and this sound. */
    suspend fun refineHeardKnown(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        audioSource: String? = null,
    ): Boolean {
        val entry = store(context).get(videoId) ?: return false
        val key = textKey(lines, audioSource)
        if (entry.refineHeardKey == key) return true
        return entry.aiTextKey == key && entry.heardWords.isNotEmpty()
    }

    /**
     * A measured song's first ninety seconds, heard once, so [RefinePolicy] has
     * words to compare with lines the measurement already put on the video's
     * clock. Downloaded files are read from the file; a stream from the
     * opening already on disk. Kept per lyrics and sound and reused, so a
     * replay pays nothing. Null audio or a refusal is not marked, and the next
     * playback may try again — there is no timer here.
     */
    suspend fun ensureRefineHeard(
        context: Context,
        videoId: String,
        song: Song,
        lines: List<LyricLine>,
        timing: Timing,
        audioSource: String? = null,
    ): Boolean {
        timing.base ?: return false
        // An AI base usually came with the words that placed it, and then
        // refineHeardKnown below answers true. Answers saved before words were
        // kept have none (10-27: qQNbfxDLxq0, base=ai, stayed -0.24 s with no
        // refine at all), so they are heard once like a measured song.
        if (!AppSettings.heavyWorkAllowed || !AppSettings.aiTimingAhead.value) return false
        if (!com.music.bitchord.data.lyrics.Deepgram.usable) return false
        if (refineHeardKnown(context, videoId, lines, audioSource)) return false
        val key = textKey(lines, audioSource)
        val lock = refining.getOrPut(videoId) { Mutex() }
        return lock.withLock {
            if (refineHeardKnown(context, videoId, lines, audioSource)) return@withLock false
            val audio = VideoAudio.songWav(context, song, VideoAudio.SONG_CHECK_SECONDS) ?: return@withLock false
            val language = com.music.bitchord.data.lyrics.Deepgram.languageFor(
                lines.filter { it.text.isNotBlank() }.map { it.text },
            )
            val answer = com.music.bitchord.data.lyrics.Deepgram.listen(
                audio,
                language,
                com.music.bitchord.data.lyrics.Deepgram.MODEL_WHISPER,
            )
            val words = answer.words ?: return@withLock false
            store(context).update(videoId) {
                it.copy(
                    refineHeardKey = key,
                    refineHeardWords = words.take(MAX_HEARD_WORDS)
                        .map { word -> SavedWord(t = word.text, s = word.startMs, e = word.endMs) },
                )
            }
            refine(context, videoId, lines, timing, audioSource)
            _changes.tryEmit(videoId)
            true
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

    private val measuring = ConcurrentHashMap<String, Mutex>()

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
        // since the kept records are read from disk along the way; and in
        // [measureScope], so the caller going away doesn't abandon it.
        measureScope.async {
            lock.withLock {
                if (!worthMeasuring(context, video.videoId)) return@async
                val outcome = try {
                    measureFresh(context, video, names, videoMs, preferred) { early ->
                        // A quick answer only where there is none yet: a second pass
                        // (more of the video has arrived) must not trade an answer from
                        // the whole record for one from its opening, even for a minute.
                        val had = store(context).get(video.videoId)
                        if (had?.autoVersion != AUTO_VERSION || had.auto == null) keep(context, video.videoId, early)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    TrackLog.w(TAG, "measurement failed: ${e.message}", video.videoId)
                    return@async
                }
                // Only a finished measurement is remembered: a dropped connection or
                // a decoder that wouldn't open is worth trying again, a refusal is
                // not (until more of the video, or of the record, is there to look at).
                if (outcome !is Measured.Done) return@async
                keep(context, video.videoId, outcome)
            }
        }.await()
    }

    /**
     * A music video whose song version was found only after its first sound
     * has already started. The recording is not swapped — that rule stands —
     * but its lyrics can now be measured against the right release instead of
     * being left unaligned.
     */
    suspend fun measureLateVersion(context: Context, video: Song, original: Song, videoMs: Long) {
        if (original.videoId.isBlank() || original.videoId == video.videoId) return
        if (!worthMeasuring(context, video.videoId)) return
        TrackLog.i(
            "BitChord",
            "lyrics-sync: ${video.videoId} late version ${original.videoId} -> measuring",
            video.videoId,
        )
        measure(
            context,
            video,
            listOf(video.title to video.artist),
            videoMs,
            preferredOriginal = original,
        )
    }

    private suspend fun keep(context: Context, videoId: String, done: Measured.Done) {
        store(context).update(videoId) {
            it.copy(
                autoVersion = AUTO_VERSION,
                auto = autoAfter(
                    previous = it.auto,
                    previousCurrent = it.autoVersion == AUTO_VERSION,
                    fresh = done.result?.let(::savedOf),
                    noChange = done.noChange,
                ),
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
        sampleBias(context, videoId)
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

    private sealed interface Measured {
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
        ) : Measured

        data object Retry : Measured
    }

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
            return Measured.Done(null, complete = true, noChange = true, note = "비디오가 원곡과 같음")
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
        val copy = awaitVideoOpening(video) ?: return Measured.Retry
        mark = lap("비디오 대기", mark)
        val complete = copy.isComplete || video.localUri != null
        val job = currentCoroutineContext()[Job]
        // The video's opening first: decoding all of it before any answer was the
        // slowest step (two minutes on a slow device). The rest is read on in the
        // background — from where the opening stopped — for the whole-record pass.
        val opening = withContext(analysis) { videoFeatures(context, video, copy.key, job, VIDEO_OPENING_SECONDS) }
            ?: return Measured.Retry
        lap("비디오 앞부분 분석", mark)
        val openingIsAll = opening.durationSeconds < VIDEO_OPENING_SECONDS - 5
        val earlyNote = "비디오 앞 ${opening.durationSeconds.roundToInt()}초"

        // Every candidate at once; the first to line up on its opening is kept
        // and shown straight away, while all of them go on to the whole record.
        val earlyGate = Mutex()
        var earlySent = false
        val (tried, clip) = coroutineScope {
            val full = async(analysis) { if (openingIsAll) opening else videoFeatures(context, video, copy.key, job) ?: opening }
            candidates.map { original ->
                async {
                    tryRecording(context, original, video, opening, openingIsAll && complete, full, complete, job) { firstResult, firstNote ->
                        earlyGate.withLock {
                            if (earlySent) return@withLock
                            earlySent = true
                            CoverOriginals.remember(video.videoId, original)
                            early(
                                Measured.Done(
                                    firstResult, complete = false, videoBytes = copy.cachedPrefix,
                                    videoSeconds = opening.durationSeconds, recordPartial = true,
                                    note = "$firstNote\n$times · $earlyNote (원곡 앞부분으로 먼저 맞춤)",
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
            ?: return Measured.Done(null, complete, copy.cachedPrefix, clip.durationSeconds, note = note, recordPartial = recordPartial)
        val same = found.linear && found.rate == 1.0 && abs(found.map.map(0L)) < MIN_SHIFT_MS
        return Measured.Done(
            found.takeUnless { same }, complete, copy.cachedPrefix, clip.durationSeconds,
            noChange = same, note = note, recordPartial = recordPartial,
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
                withContext(analysis) { recordFeatures(context, original, job, until) }?.let { start ->
                    val (result, verdict) = aligned(start, whole = false)
                    log(verdict, start, "opening")
                    if (result != null) {
                        first(result, "$name: $verdict (원곡 앞 ${start.durationSeconds.roundToInt()}초, ${seconds(System.currentTimeMillis() - began)})")
                    }
                }
                withTimeoutOrNull(RECORD_TIMEOUT_MS + 2_000L) { AudioCache.awaitAnalysisTrack(uri, RECORD_TIMEOUT_MS) }
            }
            val downloaded = System.currentTimeMillis()
            val record = withContext(analysis) { recordFeatures(context, original, job) }
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
        val record = withContext(analysis) { recordFeatures(context, original, job) }
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
     * copy to read. Asks for nothing itself: the video is normally the one
     * playing, and the player is already fetching it.
     */
    private suspend fun awaitVideoOpening(video: Song): AudioCache.Rendition? {
        // A downloaded file is already the whole recording: there is no cache
        // rendition to wait for and [VersionAudioAligner.openDataSource] reads
        // it directly.
        if (video.localUri != null) {
            return AudioCache.Rendition(key = "local:${video.videoId}", contentLength = 0L, cachedPrefix = 0L)
        }
        val deadline = System.currentTimeMillis() + VIDEO_TIMEOUT_MS
        while (true) {
            val copy = VersionAudioAligner.renditionToMeasure(video.videoId)
            if (copy != null && (copy.isComplete || copy.cachedPrefix >= MIN_VIDEO_BYTES)) return copy
            if (System.currentTimeMillis() >= deadline) return null
            delay(250)
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
    ): ChromaAlignment.Features? =
        // One reader per recording at a time: two covers of one song measured
        // at once may both be reading its record.
        synchronized(readingLocks.getOrPut(video.videoId) { Any() }) { readFeatures(context, video, copyKey, job, untilSeconds) }

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
    ): ChromaAlignment.Features? {
        if (!PowerState.screenOn) return null
        var reading = synchronized(videoReadings) { videoReadings[video.videoId] }?.takeIf { it.copyKey == copyKey }
        val from = reading?.untilSeconds ?: 0.0
        // Already read this far (an opening pass asked for less than is there): nothing new to decode.
        if (reading != null && from >= untilSeconds) {
            return reading.extractor.finish(startSeconds = reading.startSeconds).takeIf { it.frames > 0 }
        }
        val source = VersionAudioAligner.openDataSource(context, video) ?: return null
        try {
            AudioDecoder.decodeMonoStream(
                source,
                from,
                minOf(MAX_SECONDS, untilSeconds),
                abort = { job?.isActive == false || !PowerState.screenOn },
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
     * A record's profiles read the same way as a video's: its opening first,
     * then only what came after it, rather than the opening decoded twice.
     */
    private fun recordFeatures(
        context: Context,
        original: Song,
        job: Job?,
        untilSeconds: Double = MAX_SECONDS,
    ): ChromaAlignment.Features? =
        videoFeatures(context, original, VersionAudioAligner.renditionToMeasure(original.videoId)?.key ?: "record", job, untilSeconds)

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
    private const val AUTO_VERSION = 4

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

    /** Whether the automatic AI fallback already tried these [lines] for [videoId] and got nothing. */
    suspend fun aiAlreadyFailed(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        audioSource: String? = null,
    ): Boolean =
        store(context).get(videoId)?.aiFailedKey == textKey(lines, audioSource)

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
    ): Adjustment? {
        val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
        if (sung.size < MIN_AI_LINES) return null
        val key = textKey(lines, audioSource)
        // One decision for every automatic caller, made from what is kept —
        // the screen, the next-song preparation and the calibration sample
        // used to each guard on their own and each miss a case.
        val entry = store(context).get(videoId)
        // Whisper is slow and paid for by the minute, so an automatic request
        // gets it only once the measurement has given up — while the free
        // measurement may still time the video, the quick engines answer ahead.
        val engines = if (automatic) {
            AiTimingPolicy.nextEngines(
                knowledge(entry),
                key,
                manual = false,
                measurementGaveUp = measurementGaveUp(context, videoId),
            )
        } else {
            AiTimingPolicy.ALL_ENGINES
        }
        // Paid for and agreed, or already spent on these lyrics: reuse it
        // without decoding the audio again or paying an engine twice.
        if (engines.isEmpty()) return entry?.let { aiOf(it, lines, isVideo, audioSource) }
        // One request per video, lyrics and kind, whoever asks — the button,
        // the song starting, the next-song preparation. The engines are no
        // longer part of the key: two callers that read the cache a moment
        // apart could ask for different sets and both decode the audio. A
        // manual tap is kept apart so it can still force a fresh answer.
        val flight = if (automatic) "$videoId:$key" else "$videoId:$key:manual"
        val fresh = aiScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            askAi(context, videoId, lines, sung, key, engines, title, artist, isVideo)
        }
        val running = aiInFlight.putIfAbsent(flight, fresh) ?: fresh
        if (running === fresh) {
            fresh.invokeOnCompletion { aiInFlight.remove(flight, fresh) }
            fresh.start()
        }
        val adjustment = running.await() ?: return null
        if (!automatic) {
            // Asked for by hand: it now wins over the measurement, and a
            // correction made on top of the old timing no longer fits it.
            store(context).update(videoId) { it.copy(aiPreferred = true, manualMs = null) }
            _changes.tryEmit(videoId)
        }
        return adjustment
    }

    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aiInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Adjustment.Mapped?>>()

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
    ): Adjustment.Mapped? {
        val started = System.currentTimeMillis()
        // The sound the phone already has, when it has all of it; the YouTube
        // address (Gemini fetching and watching the video) otherwise.
        val audio = runCatching { VideoAudio.wav(context, videoId) }.getOrNull()
        val texts = sung.map { it.value.text }
        // Deepgram first: every word it hears comes with its time to the
        // hundredth of a second, in a few seconds. It hears singing badly,
        // but the lyrics only need some of it heard right to be pinned.
        // Then Whisper through the same key: a minute or two, but it hears the
        // singing that nova-3 mostly can't (measured on real covers: nova-3
        // placed 3 of 25 lines, Whisper 22, within a tenth of a second).
        var engine = ENGINE_DEEPGRAM
        var adjustment: Adjustment.Mapped? = null
        // The words behind the answer that is kept, so the panel can use their
        // real per-word times instead of guessing them from the line's slot.
        var acceptedHeard: List<LyricsWordAlign.Heard>? = null
        // Kept before the call, not after: a request that fails, times out or
        // is cancelled has still been paid for, and must not be made again for
        // these lyrics. [AiTimingPolicy] reads this to pick the next engine.
        suspend fun noteAttempt(name: String) {
            store(context).update(videoId) { entry ->
                val sameLyrics = entry.aiAttemptedKey == key
                entry.copy(
                    aiAttemptedKey = key,
                    aiAttemptedEngines = ((if (sameLyrics) entry.aiAttemptedEngines else emptyList()) + name).distinct(),
                )
            }
        }
        val deepgramWanted = LyricsAiGuard.wantsDeepgram(title, artist, texts)
        if (!deepgramWanted && audio != null) {
            TrackLog.d(TAG, "Deepgram skipped (${if (LyricsAiGuard.isInstrumentalTitle(title)) "instrumental" else "Korean"})", videoId)
        }
        if (audio != null && deepgramWanted && com.music.bitchord.data.lyrics.Deepgram.usable) {
            val language = com.music.bitchord.data.lyrics.Deepgram.languageFor(texts)
            for ((model, name) in listOf(
                com.music.bitchord.data.lyrics.Deepgram.MODEL_NOVA to ENGINE_DEEPGRAM,
                com.music.bitchord.data.lyrics.Deepgram.MODEL_WHISPER to ENGINE_WHISPER,
            ).filter { it.second in engines }) {
                noteAttempt(name)
                if (name == ENGINE_WHISPER) store(context).update(videoId) { it.copy(whisperKey = key) }
                val asked = System.currentTimeMillis()
                val heard = com.music.bitchord.data.lyrics.Deepgram.words(audio, language, model)
                val starts = heard?.let { com.music.bitchord.data.lyrics.LyricsWordAlign.placeLines(texts, it) }
                val raw = starts?.let { fitAi(lines, sung.map { s -> s.index }, it) }
                val fitted = raw?.takeIf { aiMayApply(lines, it, isVideo) }
                TrackLog.d(
                    TAG,
                    "$name timing ($language, ${(System.currentTimeMillis() - asked) / 1000}s): " + when {
                        heard == null -> "no answer"
                        raw == null -> "${heard.size} words, ${starts?.count { it != null } ?: 0} of ${sung.size} lines placed: not enough"
                        !LyricsAiGuard.agreementAllows(raw.matchPercent) ->
                            "${heard.size} words, ${raw.matchPercent}% agree: below ${LyricsAiGuard.MIN_MATCH_PERCENT}%"
                        !LyricsAiGuard.shiftAllows(isVideo, shownShiftMs(lines, raw)) ->
                            "${heard.size} words, shift ${shownShiftMs(lines, raw)} ms: too far for a video"
                        else -> "${heard.size} words, rate ${"%.3f".format(java.util.Locale.ROOT, raw.rate)}, " +
                            "shift ${shownShiftMs(lines, raw)} ms, ${raw.matchPercent}% agree"
                    },
                    videoId,
                )
                if (fitted != null) {
                    adjustment = fitted
                    engine = name
                    acceptedHeard = heard
                    break
                }
                // A key Deepgram turned away is turned away for Whisper too.
                if (!com.music.bitchord.data.lyrics.Deepgram.usable) break
            }
        }
        // Gemini when Deepgram isn't set up, had no audio to hear, or heard too little.
        var answer: List<Long?>? = null
        if (adjustment == null && ENGINE_GEMINI in engines) {
            noteAttempt(ENGINE_GEMINI)
            engine = ENGINE_GEMINI
            answer = LyricsTranslation.placeLinesInVideo(
                videoId,
                sung.map { LyricsTranslation.LineToPlace(it.value.text, it.value.timeMs) },
                audioWav = audio,
            )
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
                        !LyricsAiGuard.shiftAllows(isVideo, shownShiftMs(lines, raw)) ->
                            "shift ${shownShiftMs(lines, raw)} ms: too far for a video"
                        else -> "rate ${"%.3f".format(java.util.Locale.ROOT, raw.rate)}, " +
                            "shift ${shownShiftMs(lines, raw)} ms, ${raw.matchPercent}% agree"
                    },
                videoId,
            )
        }
        if (adjustment == null) {
            if (answer != null) store(context).update(videoId) { it.copy(aiFailedKey = key) }
            return null
        }
        store(context).update(videoId) {
            it.copy(
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
                // A new answer to the same lyrics keeps whether it was wanted
                // over the measurement; it's [placeWithAi] that says it is.
                aiPreferred = it.aiPreferred && it.aiTextKey == key,
                biasSampled = false,
                aiEngine = engine,
            )
        }
        sampleBias(context, videoId)
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
     * not spent as an answer: it is retried at most twice, then held until its
     * `Retry-After` has passed so the next playback tries again. Returns true
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
        val entry = store(context).get(song.videoId) ?: return false
        return entry.aiTextKey == key && entry.ai != null
    }

    suspend fun verifySong(
        context: Context,
        song: Song,
        lines: List<LyricLine>,
    ): Boolean {
        val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
        val timed = lines.any { it.timeMs > 0 }
        if (!LyricsAiGuard.songTimingWanted(
                isVideo = song.isVideo,
                timed = timed,
                sungLines = sung.size,
                title = song.title,
                artist = song.artist,
                texts = sung.map { it.value.text },
            )
        ) return false
        val key = textKey(
            lines,
            audioSourceKey(song.videoId, song.localUri != null),
        )
        val entry = store(context).get(song.videoId)
        // A 429 is not a failed answer: the engine was never really asked, so
        // it is not recorded as spent. The song waits instead until the
        // server's own delay has passed, and the next playback (an event, not
        // a timer) tries again.
        val retryAt = entry?.songRetryAtMs
        if (entry?.songRetryKey == key && retryAt != null && retryAt > System.currentTimeMillis()) return false
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
        if (engine !in engines || !com.music.bitchord.data.lyrics.Deepgram.usable) return false
        // Whisper takes a minute or two, and a screen job is cancelled the
        // moment the track changes: run the request on the app-lifetime scope
        // so it finishes and keeps its answer. One request per video and
        // lyrics text, whoever asks. A cancelled caller stops waiting only.
        val flight = "song:${song.videoId}:$key"
        val fresh = aiScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            verifySongRequest(context.applicationContext, song, lines, key, engine)
        }
        val running = songInFlight.putIfAbsent(flight, fresh) ?: fresh
        if (running === fresh) {
            fresh.invokeOnCompletion { songInFlight.remove(flight, fresh) }
            fresh.start()
        }
        return running.await()
    }

    private val songInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Boolean>>()

    /**
     * The one song-sync request allowed at a time. A 429 is an account-wide
     * statement, so serialising the checks keeps a queue of skipped songs from
     * re-tripping the limiter while another check is in flight. Nothing polls:
     * waiters are suspended on this mutex, and a wait longer than
     * [SongSyncRetry.MAX_INLINE_WAIT_MS] is deferred to the next playback.
     */
    private val songSyncGate = Mutex()

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
    ): Boolean = songSyncGate.withLock {
        // Checked again under the gate: another song's 429 may have marked
        // this one while its flight was waiting here.
        val pending = store(context).get(song.videoId)
        val retryAt = pending?.songRetryAtMs
        if (pending?.songRetryKey == key && retryAt != null && retryAt > System.currentTimeMillis()) {
            return@withLock false
        }
        val check = runSongCheck(context, song, lines, key, engine) ?: return@withLock false
        if (check.result == "no-words") return@withLock false
        val fitted = check.fitted ?: return@withLock true
        store(context).update(song.videoId) {
            it.copy(
                aiTextKey = key,
                ai = savedOf(fitted),
                aiShiftMs = null,
                aiStarts = null,
                aiFailedKey = null,
                heardWords = check.heard.take(MAX_HEARD_WORDS)
                    .map { word -> SavedWord(t = word.text, s = word.startMs, e = word.endMs) },
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
        )
        true
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
    ): SongCheck? {
        // Straight off the file, or the streaming copy's opening; null when
        // there is nothing to listen to yet.
        val audio = VideoAudio.songWav(context, song) ?: run {
            songLog(song.videoId, "no-audio", null, null, 0)
            return null
        }
        // Sync-test mode only: the ninety seconds the check already decoded,
        // kept for the phone's scoring run. Off, this is a no-op.
        SyncTestMode.saveWav(context, song.videoId, audio)
        val windowMs = SongCheckWindow.wavMs(audio.size)
        val indices = SongCheckWindow.indices(lines.map { it.timeMs }, windowMs)
            .filter { lines[it].text.isNotBlank() }
        val seconds = (windowMs / 1000.0).roundToInt()
        if (indices.size < MIN_AI_LINES) {
            songLog(song.videoId, "no-words", null, null, seconds)
            return SongCheck("no-words", null, emptyList(), null, null, seconds, null, 0)
        }
        val texts = indices.map { lines[it].text }
        val language = com.music.bitchord.data.lyrics.Deepgram.languageFor(texts)
        val answer = listenForSong(context, song.videoId, key, audio, language) ?: return null
        // Spent once Deepgram actually answered — success or refusal. A
        // cancellation or a request that never reached it is not recorded,
        // so the next playback may try again; nothing is recorded before the
        // answer either, which used to leave a song never checked again when
        // its screen job was cancelled mid-request. A 429 never gets here:
        // [listenForSong] either retried it or deferred the song.
        if (answer.httpCode != null) noteAttempt(context, song.videoId, key, engine)
        val heard = answer.words ?: run {
            songLog(song.videoId, "no-answer", null, null, seconds, words = 0, http = answer.httpCode)
            return SongCheck("no-answer", null, emptyList(), null, null, seconds, answer.httpCode, 0)
        }
        if (heard.size < MIN_AI_LINES) {
            songLog(song.videoId, "few-words", null, null, seconds, words = heard.size, http = answer.httpCode)
            return SongCheck("few-words", null, heard, null, null, seconds, answer.httpCode, heard.size)
        }
        val starts = LyricsWordAlign.placeLines(texts, heard)
        val fitted = fitAi(lines, indices, starts) ?: run {
            songLog(song.videoId, "no-fit", null, null, seconds, words = heard.size, http = answer.httpCode)
            return SongCheck("no-fit", null, heard, null, null, seconds, answer.httpCode, heard.size)
        }
        val match = fitted.matchPercent
        val shift = shownShiftMs(lines, fitted)
        when {
            !LyricsAiGuard.agreementAllows(match) -> {
                songLog(song.videoId, "refused-match", match, shift, seconds, words = heard.size, http = answer.httpCode)
                return SongCheck("refused-match", null, heard, match, shift, seconds, answer.httpCode, heard.size)
            }
            !LyricsAiGuard.songShiftAllows(shift) -> {
                songLog(song.videoId, "refused-shift", match, shift, seconds, words = heard.size, http = answer.httpCode)
                return SongCheck("refused-shift", null, heard, match, shift, seconds, answer.httpCode, heard.size)
            }
        }
        return SongCheck("applied", fitted, heard, match, shift, seconds, answer.httpCode, heard.size)
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
    )

    /**
     * Whisper with the 429 rules applied: at most two retries, `Retry-After`
     * believed (exponential otherwise), and a long wait deferred rather than
     * held. Null means the song was rate-limited and marked for later.
     */
    private suspend fun listenForSong(
        context: Context,
        videoId: String,
        key: Int,
        audio: ByteArray,
        language: String,
    ): com.music.bitchord.data.lyrics.Deepgram.Answer? {
        val quiet = songQuietUntilMs - System.currentTimeMillis()
        if (quiet > 0) {
            if (!SongSyncRetry.canWaitInline(quiet)) {
                markRateLimited(context, videoId, key, quiet)
                rateLimitedLog(videoId, quiet)
                return null
            }
            delay(quiet)
        }
        var attempt = 0
        while (true) {
            val answer = com.music.bitchord.data.lyrics.Deepgram.listen(
                audio,
                language,
                com.music.bitchord.data.lyrics.Deepgram.MODEL_WHISPER,
            )
            if (answer.httpCode != 429) {
                songQuietUntilMs = 0L
                return answer
            }
            val wait = SongSyncRetry.retryDelayMs(answer.retryAfterMs, attempt)
            songQuietUntilMs = System.currentTimeMillis() + wait
            rateLimitedLog(videoId, wait)
            if (!SongSyncRetry.shouldRetry(answer.httpCode, attempt) ||
                !SongSyncRetry.canWaitInline(wait)
            ) {
                markRateLimited(context, videoId, key, wait)
                return null
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
    ) {
        TrackLog.d(
            TAG,
            "song-sync: $videoId result=$result match=${match ?: 0}% shift=${shift ?: 0} secs=$seconds" +
                " words=${words ?: 0}" + (http?.let { " http=$it" } ?: ""),
            videoId,
        )
    }

    /** Records that [engine] was spent on these lyrics for [videoId]; once per text. */
    private suspend fun noteAttempt(context: Context, videoId: String, key: Int, engine: String) {
        store(context).update(videoId) { entry ->
            val sameLyrics = entry.aiAttemptedKey == key
            entry.copy(
                aiAttemptedKey = key,
                aiAttemptedEngines = ((if (sameLyrics) entry.aiAttemptedEngines else emptyList()) + engine).distinct(),
                songRetryKey = null,
                songRetryAtMs = null,
            )
        }
    }

    /** Forgets the AI answer for [videoId] (and a correction made on top of it); the measurement applies again. */
    suspend fun clearAi(context: Context, videoId: String) {
        store(context).update(videoId) {
            it.copy(
                aiTextKey = null,
                ai = null,
                aiShiftMs = null,
                aiStarts = null,
                heardWords = emptyList(),
                manualMs = null,
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
    suspend fun resetSongCheck(context: Context, videoId: String) {
        // The file these lyrics came from is gone or has different bytes. An
        // automatic override still holds its old sheet and sync, so it has to
        // go with the answer; a listener's own override is left alone.
        LyricsOverrideStore.removeRecheck(context, videoId)
        store(context).update(videoId) {
            it.copy(
                aiTextKey = null,
                ai = null,
                aiShiftMs = null,
                aiStarts = null,
                aiFailedKey = null,
                heardWords = emptyList(),
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
        val match = entry?.let { e -> e.ai?.match?.takeIf { e.aiTextKey == key } }
        val stored = entry?.aiTextKey == key &&
            (entry.ai != null || entry.aiShiftMs != null || entry.aiStarts != null)
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
        // Off by default: neither the check nor the override it writes may
        // change a downloaded file's lyrics on this build.
        if (!SyncSafetyPolicy.recheckAllowed(AppSettings.syncSafetySwitches)) return null
        val localUri = song.localUri?.takeIf { it.isNotBlank() } ?: return null
        if (lines.none { it.timeMs > 0 }) return null
        if (!com.music.bitchord.data.lyrics.Deepgram.usable) return null
        val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
        if (!LyricsAiGuard.songTimingWanted(
                isVideo = song.isVideo,
                timed = true,
                sungLines = sung.size,
                title = title,
                artist = artist,
                texts = sung.map { it.value.text },
            )
        ) return null
        val audioSource = audioSourceKey(song.videoId, localFile = true)
        val key = textKey(lines, audioSource)
        val before = store(context).get(song.videoId)
        val beforeMatch = before?.let { e -> e.ai?.match?.takeIf { e.aiTextKey == key } }
        val reason = DownloadedSyncCheck.recheckReason(
            before?.syncCheckVersion,
            providerUnknown = providerUnknown,
            matchPercent = beforeMatch,
        )
        if (reason == DownloadedSyncCheck.Recheck.NONE) return null
        recheckTokens[song.videoId] = reason.token
        return songSyncGate.withLock {
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
            val freshKey = bestFresh?.let { textKey(it.lines, audioSource) } ?: key
            val decision = DownloadedSyncCheck.decide(
                storedVersion = entry?.syncCheckVersion,
                existingMatchPercent = existingMatch,
                existingKey = key,
                ownMatchPercent = own.match,
                freshMatchPercent = if (bestFresh != null) result.match else null,
                freshKey = freshKey,
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
                        aiTextKey = decision.key ?: key,
                        ai = savedOf(chosen),
                        aiShiftMs = null,
                        aiStarts = null,
                        aiFailedKey = null,
                        heardWords = result.heard.take(MAX_HEARD_WORDS)
                            .map { word -> SavedWord(t = word.text, s = word.startMs, e = word.endMs) },
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
                isVideo = song.isVideo,
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
    private suspend fun sampleBias(context: Context, videoId: String) {
        val entry = store(context).get(videoId) ?: return
        if (entry.biasSampled || entry.autoVersion != AUTO_VERSION) return
        val measured = entry.auto?.toAdjustment(byAi = false) ?: return
        val ai = entry.ai?.toAdjustment(byAi = true) ?: return
        // Only a measurement that clearly held: most of the song agreeing.
        if ((measured.matchPercent ?: 0) < 60 || (ai.matchPercent ?: 0) < 50) return
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
        store(context).get(videoId)?.let { it.autoVersion == AUTO_VERSION && (it.auto != null || it.autoNoChange) } == true

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
        if (!calibrationSample(videoId)) return false
        if (store(context).get(videoId)?.calibrationSampled == true) return false
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
     * Callers used to combine [hasAi], [aiAlreadyFailed] and "wants Whisper"
     * themselves; those answers disagreed with each other and with what the
     * cache actually holds, so an accepted 100% answer could still be sent to
     * Whisper a minute later.
     */
    suspend fun wantsAi(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        audioSource: String? = null,
    ): Boolean =
        AiTimingPolicy.shouldAskAgain(
            knowledge(store(context).get(videoId)),
            textKey(lines, audioSource),
            manual = false,
            measurementGaveUp = measurementGaveUp(context, videoId),
        )

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
    internal fun fitAi(lines: List<LyricLine>, indices: List<Int>, starts: List<Long?>): Adjustment.Mapped? {
        val points = indices.zip(starts).mapNotNull { (index, start) -> start?.let { lines[index].timeMs to it } }
        if (points.size < MIN_AI_LINES || points.size * 4 < indices.size) return null
        val line = TimingFit.fit(points, minPoints = MIN_AI_LINES) ?: return null
        val match = (line.inliers * 100.0 / indices.size).roundToInt().coerceIn(0, 100)
        return Adjustment.Mapped(line.map, byAi = true, rate = line.rate, matchPercent = match)
    }

    // ---- By hand ----------------------------------------------------------------------

    /** The listener's own correction for [videoId], ms; positive shows the lyrics later. */
    suspend fun manual(context: Context, videoId: String): Long = store(context).get(videoId)?.manualMs ?: 0L

    /** Keeps [ms] as the correction for [videoId]; 0 removes it. */
    suspend fun setManual(context: Context, videoId: String, ms: Long) {
        val value = ms.coerceIn(-MAX_MANUAL_MS, MAX_MANUAL_MS)
        store(context).update(videoId) { it.copy(manualMs = value.takeIf { v -> v != 0L }) }
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
        )
    } ?: AiTimingKnowledge()

    // ---- Kept on disk --------------------------------------------------------

    /**
     * One word as the transcript heard it, kept compactly: the entry file holds
     * thousands of videos, and a word list is the bulkiest thing a timing entry
     * ever carries. Times are on the video's own clock, as heard.
     */
    @Serializable
    private data class SavedWord(val t: String, val s: Long, val e: Long)

    /** A [LyricsTimeMap] with what was said about it. */
    @Serializable
    private data class SavedMap(
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
    private data class Entry(
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
        /** The automatic AI fallback got nothing for the lyrics with this key. */
        val aiFailedKey: Int? = null,
        val manualMs: Long? = null,
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
        /** The lyrics (text key) the refine transcript belongs to, and its words on the video's clock. */
        val refineHeardKey: Int? = null,
        val refineHeardWords: List<SavedWord> = emptyList(),
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
    private data class Saved(val entries: Map<String, Entry> = emptyMap())

    private class Store(private val file: File) {
        private val mutex = Mutex()
        private var entries: LinkedHashMap<String, Entry>? = null

        private suspend fun loaded(): LinkedHashMap<String, Entry> {
            entries?.let { return it }
            val read = withContext(Dispatchers.IO) {
                runCatching { json.decodeFromString(Saved.serializer(), file.readText()).entries }.getOrNull()
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
         * Written a moment later, off the main thread, once for however many
         * changes came in meanwhile: the file holds every video's maps and
         * can run to megabytes, and tapping the sync buttons changes it ten
         * times a second.
         */
        private fun scheduleSave() {
            if (!saving.compareAndSet(false, true)) return
            saveScope.launch {
                try {
                    delay(SAVE_DELAY_MS)
                    val snapshot = mutex.withLock {
                        dirty = false
                        Saved(LinkedHashMap(loaded()))
                    }
                    runCatching {
                        val text = json.encodeToString(Saved.serializer(), snapshot)
                        val temporary = File(file.parentFile, "${file.name}.tmp")
                        temporary.writeText(text)
                        if (!temporary.renameTo(file)) {
                            temporary.copyTo(file, overwrite = true)
                            temporary.delete()
                        }
                    }
                } finally {
                    saving.set(false)
                    if (dirty) scheduleSave()
                }
            }
        }
    }

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val SAVE_DELAY_MS = 800L

    private const val MAX_ENTRIES = 3_000
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var store: Store? = null

    private fun store(context: Context): Store =
        store ?: synchronized(this) {
            store ?: Store(File(context.applicationContext.filesDir, "lyrics_timing_v1.json")).also { store = it }
        }
}
