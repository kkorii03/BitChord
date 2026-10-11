package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.lyrics.ChromaAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTimeMap
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.lyrics.TimingFit
import com.music.bitchord.data.lyrics.artistForLyricsSearch
import com.music.bitchord.data.lyrics.forLyricsSearch
import com.music.bitchord.data.lyrics.timedBy
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

        /** Each line to its own start — an AI answer kept by ko-21/22. */
        data class Retimed(val startsMs: List<Long>) : Adjustment {
            override val byAi: Boolean get() = true
        }

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

    /** Everything applied to one video's lyrics: [base], then [manualMs] on top. */
    data class Timing(val base: Adjustment?, val manualMs: Long) {
        val isEmpty: Boolean get() = base == null && manualMs == 0L
    }

    /** Sources whose timings already belong to the video they were fetched for. */
    fun timedToVideo(source: LyricsSource?): Boolean = source == LyricsSource.YOUTUBE_TRANSCRIPT ||
        source == LyricsSource.YOUTUBE_MUSIC || source == LyricsSource.SIMP_MUSIC

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
     * only the correction — for lyrics that are already the video's own.
     */
    suspend fun timing(context: Context, videoId: String, lines: List<LyricLine>, baseAllowed: Boolean): Timing? {
        val entry = store(context).get(videoId) ?: return null
        val base = if (baseAllowed) baseOf(entry, lines) else null
        return Timing(base, entry.manualMs ?: 0L).takeUnless { it.isEmpty }
    }

    private fun baseOf(entry: Entry, lines: List<LyricLine>): Adjustment? {
        if (entry.aiTextKey != null && entry.aiTextKey == textKey(lines)) {
            entry.ai?.toAdjustment(byAi = true)?.let { return it }
            entry.aiShiftMs?.let { return Adjustment.Shift(it, byAi = true) }
            entry.aiStarts?.takeIf { it.size == lines.size }?.let { return Adjustment.Retimed(it) }
        }
        if (entry.autoVersion == AUTO_VERSION) return entry.auto?.toAdjustment(byAi = false)
        return null
    }

    /** [lines] as [timing] puts them; words, syllables and backing vocals move with their line. */
    fun apply(lines: List<LyricLine>, timing: Timing): List<LyricLine> = when (val base = timing.base) {
        null -> lines.timedBy(null, timing.manualMs)
        is Adjustment.Shift -> lines.timedBy(LyricsTimeMap.shift(base.ms), timing.manualMs)
        is Adjustment.Mapped -> lines.timedBy(base.map, timing.manualMs)
        is Adjustment.Retimed -> lines.mapIndexed { index, line ->
            val start = base.startsMs.getOrNull(index) ?: line.timeMs
            listOf(line).timedBy(LyricsTimeMap.shift(start - line.timeMs), timing.manualMs).single()
        }
    }

    /** How far [adjustment] moves the first sung line of [lines] — the figure worth showing. */
    fun shownShiftMs(lines: List<LyricLine>, adjustment: Adjustment): Long {
        val first = lines.firstOrNull { it.text.isNotBlank() } ?: lines.firstOrNull() ?: return 0L
        return when (adjustment) {
            is Adjustment.Shift -> adjustment.ms
            is Adjustment.Mapped -> adjustment.map.map(first.timeMs) - first.timeMs
            is Adjustment.Retimed -> (adjustment.startsMs.getOrNull(lines.indexOf(first)) ?: first.timeMs) - first.timeMs
        }
    }

    // ---- Automatic measurement ------------------------------------------------------

    /** Whether the measurement has said all it is going to for [videoId]. */
    suspend fun measured(context: Context, videoId: String): Boolean =
        store(context).get(videoId)?.let { it.autoVersion == AUTO_VERSION && it.autoComplete } == true

    /**
     * Whether measuring [videoId] now could tell more than last time: it never
     * has been, or only on an opening and much more of the video has reached
     * the disk since.
     */
    suspend fun worthMeasuring(context: Context, videoId: String): Boolean {
        val entry = store(context).get(videoId)
        if (entry == null || entry.autoVersion != AUTO_VERSION) return true
        if (entry.autoComplete) return false
        val copy = VersionAudioAligner.renditionToMeasure(videoId) ?: return false
        return copy.isComplete || copy.cachedPrefix >= entry.autoVideoBytes + maxOf(REMEASURE_GROWTH_BYTES, entry.autoVideoBytes / 3)
    }

    /**
     * True when the measurement is done with [videoId] and found nothing: no
     * original recording, or a video that doesn't line up with it — the case
     * the automatic AI fallback is for. A refusal on only the opening of a
     * video counts once that opening is long enough to have shown the song.
     */
    suspend fun measurementGaveUp(context: Context, videoId: String): Boolean {
        val entry = store(context).get(videoId) ?: return false
        if (entry.autoVersion != AUTO_VERSION || entry.auto != null || entry.autoNoChange) return false
        return entry.autoComplete || entry.autoVideoSeconds >= GIVE_UP_AFTER_SECONDS
    }

    private val measuring = ConcurrentHashMap<String, Mutex>()

    /**
     * Measures how [video] runs against the original recording named [title] /
     * [artist], on as much of the video as is on disk, and keeps the answer.
     * The lyrics screen, the lock screen and the next-song preparation may all
     * ask at once; they share one measurement.
     */
    suspend fun measure(context: Context, video: Song, title: String, artist: String) {
        val lock = measuring.getOrPut(video.videoId) { Mutex() }
        lock.withLock {
            if (!worthMeasuring(context, video.videoId)) return
            val outcome = try {
                measureFresh(context, video, title, artist)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                TrackLog.w(TAG, "measurement failed: ${e.message}", video.videoId)
                return
            }
            // Only a finished measurement is remembered: a dropped connection or
            // a decoder that wouldn't open is worth trying again, a refusal is
            // not (until more of the video is there to look at).
            if (outcome !is Measured.Done) return
            store(context).update(video.videoId) {
                it.copy(
                    autoVersion = AUTO_VERSION,
                    auto = outcome.result?.let(::savedOf),
                    autoComplete = outcome.complete,
                    autoNoChange = outcome.noChange,
                    autoVideoBytes = outcome.videoBytes,
                    autoVideoSeconds = outcome.videoSeconds,
                )
            }
            _changes.tryEmit(video.videoId)
        }
    }

    private sealed interface Measured {
        data class Done(
            val result: ChromaAlignment.Result?,
            val complete: Boolean,
            val videoBytes: Long = 0L,
            val videoSeconds: Double = 0.0,
            /** Measured, and the video already keeps the record's time. */
            val noChange: Boolean = false,
        ) : Measured

        data object Retry : Measured
    }

    private suspend fun measureFresh(context: Context, video: Song, title: String, artist: String): Measured {
        val original = findRecording(title, artist) ?: run {
            TrackLog.d(TAG, "no original recording found for '$title' / '$artist'", video.videoId)
            return Measured.Done(null, complete = true)
        }
        // The video is the record itself: nothing to move.
        if (original.videoId == video.videoId) return Measured.Done(null, complete = true, noChange = true)

        // The record, whole; the video, as much as has arrived (it is usually
        // the track playing, and the player is the one fetching it).
        val ready = withTimeoutOrNull(RECORD_TIMEOUT_MS + 5_000L) {
            coroutineScope {
                val record = async {
                    original.localUri != null || AudioCache.awaitAnalysisTrack(
                        Uri.parse(AutomixAnalysisSource.opusUri(original.videoId)), RECORD_TIMEOUT_MS,
                    )
                }
                val opening = async { awaitVideoOpening(video) }
                record.await() to opening.await()
            }
        } ?: return Measured.Retry
        if (!ready.first || ready.second == null) return Measured.Retry
        val copy = ready.second!!
        val complete = copy.isComplete || video.localUri != null

        val job = currentCoroutineContext()[Job]
        var recordFeatures: ChromaAlignment.Features? = null
        var videoFeatures: ChromaAlignment.Features? = null
        val decodeMs = measureTimeMillis {
            coroutineScope {
                val r = async(Dispatchers.IO) { features(context, original, MAX_SECONDS, job) }
                val v = async(Dispatchers.IO) { features(context, video, MAX_SECONDS, job) }
                recordFeatures = r.await()
                videoFeatures = v.await()
            }
        }
        val record = recordFeatures ?: return Measured.Retry
        val clip = videoFeatures ?: return Measured.Retry
        var result: ChromaAlignment.Result? = null
        val alignMs = measureTimeMillis {
            result = withContext(Dispatchers.Default) { ChromaAlignment.align(record, clip, videoComplete = complete) }
        }
        TrackLog.d(
            TAG,
            "chroma ${original.videoId} -> ${video.videoId}: ${ChromaAlignment.lastVerdict} " +
                "(record ${record.durationSeconds.roundToInt()} s, video ${clip.durationSeconds.roundToInt()} s" +
                (if (complete) "" else " so far") + ", decode ${decodeMs} ms, align ${alignMs} ms)",
            video.videoId,
        )
        val same = result?.let { it.linear && it.rate == 1.0 && abs(it.map.map(0L)) < MIN_SHIFT_MS } == true
        return Measured.Done(result.takeUnless { same }, complete, copy.cachedPrefix, clip.durationSeconds, noChange = same)
    }

    /**
     * Waits (a while) for enough of [video]'s audio to be on disk to say
     * something — about a minute of it, or all of it — and returns the copy
     * to read. Asks for nothing itself: the video is normally the one
     * playing, and the player is already fetching it.
     */
    private suspend fun awaitVideoOpening(video: Song): AudioCache.Rendition? {
        val deadline = System.currentTimeMillis() + VIDEO_TIMEOUT_MS
        while (true) {
            val copy = VersionAudioAligner.renditionToMeasure(video.videoId)
            if (copy != null && (copy.isComplete || copy.cachedPrefix >= MIN_VIDEO_BYTES)) return copy
            if (System.currentTimeMillis() >= deadline) {
                return copy?.takeIf { it.cachedPrefix >= AudioCache.MIN_ALIGNMENT_PREFIX_BYTES }
            }
            delay(500)
        }
    }

    /** [song]'s audio, as much as is on disk up to [maxSeconds], reduced to pitch profiles. */
    private fun features(context: Context, song: Song, maxSeconds: Double, job: Job?): ChromaAlignment.Features? {
        val source = VersionAudioAligner.openDataSource(context, song) ?: return null
        return try {
            var extractor: ChromaAlignment.Extractor? = null
            var rate = 0
            val start = AudioDecoder.decodeMonoStream(source, 0.0, maxSeconds, abort = { job?.isActive == false }) { chunk, sampleRate ->
                val into = extractor ?: ChromaAlignment.Extractor(sampleRate).also {
                    extractor = it
                    rate = sampleRate
                }
                // A stream that changes rate part-way is not one this can read on.
                if (sampleRate == rate) into.push(chunk)
            } ?: return null
            extractor?.finish(startSeconds = start)
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * The catalogue recording of the song, from an anonymous YouTube Music
     * search — a lookup the app makes for itself, kept out of the listener's
     * search history.
     */
    private suspend fun findRecording(title: String, artist: String): Song? = withContext(Dispatchers.IO) {
        val cleanTitle = title.forLyricsSearch()
        val cleanArtist = artist.artistForLyricsSearch()
        val response = Innertube.searchAnonymous("$cleanTitle $cleanArtist", SearchFilter.SONGS.params)
        InnertubeParser.parseSearchPage(response, includeVideos = false).rows
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }
            .take(5)
            .firstOrNull { !it.isVideo && TrackMatcher.matches(it, cleanTitle, cleanArtist) }
    }

    /** The record: whole, within this long. */
    private const val RECORD_TIMEOUT_MS = 60_000L

    /** The video: this long for an opening worth measuring to arrive. */
    private const val VIDEO_TIMEOUT_MS = 30_000L

    /** About a minute of a YouTube Opus stream. */
    private const val MIN_VIDEO_BYTES = 1_000_000L

    /** Measure again once at least this much more of the video is on disk. */
    private const val REMEASURE_GROWTH_BYTES = 600_000L

    /** Nothing past this much of either is read. */
    private const val MAX_SECONDS = 720.0

    /** A refusal on this much of a video is the measurement giving up. */
    private const val GIVE_UP_AFTER_SECONDS = 150.0

    /** Measurements kept before this version (the loudness one) are measured again. */
    private const val AUTO_VERSION = 2

    // ---- AI ----------------------------------------------------------------------------

    /** Whether an AI answer is kept for [videoId] and these [lines]. */
    suspend fun hasAi(context: Context, videoId: String, lines: List<LyricLine>): Boolean =
        store(context).get(videoId)?.let { baseOf(it, lines)?.byAi } == true

    /** Whether the automatic AI fallback already tried these [lines] for [videoId] and got nothing. */
    suspend fun aiAlreadyFailed(context: Context, videoId: String, lines: List<LyricLine>): Boolean =
        store(context).get(videoId)?.aiFailedKey == textKey(lines)

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
    ): Adjustment? {
        val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
        if (sung.size < MIN_AI_LINES) return null
        val key = textKey(lines)
        val answer = LyricsTranslation.placeLinesInVideo(
            videoId,
            sung.map { LyricsTranslation.LineToPlace(it.value.text, it.value.timeMs) },
        )
        val adjustment = answer?.let { fitAi(lines, sung.map { it.index }, it) }
        TrackLog.d(
            TAG,
            "AI timing: " + when {
                answer == null -> "no answer"
                adjustment == null -> "answers agree on no line (${answer.count { it != null }} of ${sung.size} placed)"
                else -> "rate ${"%.3f".format(java.util.Locale.ROOT, adjustment.rate)}, " +
                    "shift ${shownShiftMs(lines, adjustment)} ms, ${adjustment.matchPercent}% agree"
            },
            videoId,
        )
        if (adjustment == null) {
            if (automatic && answer != null) store(context).update(videoId) { it.copy(aiFailedKey = key) }
            return null
        }
        store(context).update(videoId) {
            it.copy(
                aiTextKey = key,
                ai = savedOf(adjustment),
                aiShiftMs = null,
                aiStarts = null,
                aiFailedKey = null,
                manualMs = null,
            )
        }
        _changes.tryEmit(videoId)
        return adjustment
    }

    /** Forgets the AI answer for [videoId] (and a correction made on top of it); the measurement applies again. */
    suspend fun clearAi(context: Context, videoId: String) {
        store(context).update(videoId) {
            it.copy(aiTextKey = null, ai = null, aiShiftMs = null, aiStarts = null, manualMs = null)
        }
        _changes.tryEmit(videoId)
    }

    private const val MIN_AI_LINES = 3

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
    private fun textKey(lines: List<LyricLine>): Int = lines.joinToString("\n") { it.text }.hashCode()

    // ---- Kept on disk --------------------------------------------------------

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
        /** How much of the video (bytes, seconds) the last measurement read. */
        val autoVideoBytes: Long = 0L,
        val autoVideoSeconds: Double = 0.0,
        val ai: SavedMap? = null,
        /** The automatic AI fallback got nothing for the lyrics with this key. */
        val aiFailedKey: Int? = null,
        val manualMs: Long? = null,
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

        suspend fun update(videoId: String, change: (Entry) -> Entry) = mutex.withLock {
            val map = loaded()
            map[videoId] = change(map.remove(videoId) ?: Entry())
            while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
            val text = json.encodeToString(Saved.serializer(), Saved(LinkedHashMap(map)))
            withContext(Dispatchers.IO) {
                runCatching {
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.writeText(text)
                    if (!temporary.renameTo(file)) {
                        temporary.copyTo(file, overwrite = true)
                        temporary.delete()
                    }
                }
            }
            Unit
        }
    }

    private const val MAX_ENTRIES = 1_000
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var store: Store? = null

    private fun store(context: Context): Store =
        store ?: synchronized(this) {
            store ?: Store(File(context.applicationContext.filesDir, "lyrics_timing_v1.json")).also { store = it }
        }
}
