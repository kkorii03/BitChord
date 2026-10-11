package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.ChromaAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTimeMap
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.lyrics.RecordingPick
import com.music.bitchord.data.lyrics.TimingFit
import com.music.bitchord.data.lyrics.timedBy
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.AudioCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
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
        /** Who gave [base] when it is an AI answer — "Deepgram" or "Gemini". */
        val aiEngine: String? = null,
    ) {
        val isEmpty: Boolean get() = base == null && manualMs == 0L

        /** What goes on top of [base]: the hand's correction, less Gemini's lateness. */
        val extraMs: Long get() = manualMs - if (base?.byAi == true) aiBiasMs else 0L
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
    ): Timing? {
        val entry = store(context).get(videoId) ?: return null
        val base = if (baseAllowed) baseOf(entry, lines, measuredAllowed) else null
        val engine = if (base?.byAi == true) entry.aiEngine ?: ENGINE_GEMINI else null
        val bias = engine?.let { aiBias(context, it) } ?: 0L
        return Timing(base, entry.manualMs ?: 0L, bias, engine).takeUnless { it.isEmpty }
    }

    private fun baseOf(entry: Entry, lines: List<LyricLine>, measuredAllowed: Boolean = true): Adjustment? {
        val measured = entry.auto?.takeIf { measuredAllowed && entry.autoVersion == AUTO_VERSION }
        // Asked for by hand, over a measurement the listener saw was off: Gemini's.
        if (entry.aiPreferred) aiOf(entry, lines)?.let { return it }
        // Otherwise the measurement first: when it lines the two up it is right
        // to a tenth of a second, which Gemini's answers never are.
        measured?.toAdjustment(byAi = false)?.let { return it }
        return aiOf(entry, lines)
    }

    private fun aiOf(entry: Entry, lines: List<LyricLine>): Adjustment? {
        if (entry.aiTextKey != null && entry.aiTextKey == textKey(lines)) {
            entry.ai?.toAdjustment(byAi = true)?.let { return it }
            entry.aiShiftMs?.let { return Adjustment.Shift(it, byAi = true) }
            // A ko-21/22 per-line answer: the very one that drifted. Its
            // starts go through today's fit; when they don't make a line,
            // the measurement applies instead.
            entry.aiStarts?.takeIf { it.size == lines.size }?.let { starts ->
                val sung = lines.indices.filter { lines[it].text.isNotBlank() }
                fitAi(lines, sung, sung.map { starts[it] })?.let { return it }
            }
        }
        return null
    }

    /**
     * What the last measurement of [videoId] made of it, in figures — which
     * recording it was measured against and how the two compared — for the
     * sync drawer, so a screenshot says why a video was or wasn't timed.
     */
    suspend fun note(context: Context, videoId: String): String? = store(context).get(videoId)?.autoNote

    /** [lines] as [timing] puts them; words, syllables and backing vocals move with their line. */
    fun apply(lines: List<LyricLine>, timing: Timing): List<LyricLine> = when (val base = timing.base) {
        null -> lines.timedBy(null, timing.extraMs)
        is Adjustment.Shift -> lines.timedBy(LyricsTimeMap.shift(base.ms), timing.extraMs)
        is Adjustment.Mapped -> lines.timedBy(base.map, timing.extraMs)
    }

    /** How far [adjustment] moves the first sung line of [lines] — the figure worth showing. */
    fun shownShiftMs(lines: List<LyricLine>, adjustment: Adjustment): Long {
        val first = lines.firstOrNull { it.text.isNotBlank() } ?: lines.firstOrNull() ?: return 0L
        return when (adjustment) {
            is Adjustment.Shift -> adjustment.ms
            is Adjustment.Mapped -> adjustment.map.map(first.timeMs) - first.timeMs
        }
    }

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
    suspend fun measure(context: Context, video: Song, names: List<Pair<String, String>>, videoMs: Long) {
        val lock = measuring.getOrPut(video.videoId) { Mutex() }
        // Off the caller's thread — the lyrics screen asks from the main one —
        // since the kept records are read from disk along the way.
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!worthMeasuring(context, video.videoId)) return@withContext
                val outcome = try {
                    measureFresh(context, video, names, videoMs) { early -> keep(context, video.videoId, early) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    TrackLog.w(TAG, "measurement failed: ${e.message}", video.videoId)
                    return@withContext
                }
                // Only a finished measurement is remembered: a dropped connection or
                // a decoder that wouldn't open is worth trying again, a refusal is
                // not (until more of the video, or of the record, is there to look at).
                if (outcome !is Measured.Done) return@withContext
                keep(context, video.videoId, outcome)
            }
        }
    }

    private suspend fun keep(context: Context, videoId: String, done: Measured.Done) {
        store(context).update(videoId) {
            it.copy(
                autoVersion = AUTO_VERSION,
                auto = done.result?.let(::savedOf),
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
        // Searched once per video: later passes over the same video ask again
        // only if the first search found nothing.
        val candidates = synchronized(picks) { picks[video.videoId] }
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
            return Measured.Done(null, complete = true, noChange = true, note = "비디오가 원곡과 같음")
        }

        // The records start coming down now, while the video's opening is
        // still on its way, rather than once it has arrived.
        candidates
            .filter { it.localUri == null && kept(context, it.videoId) == null }
            .forEach { AudioCache.requestAnalysisHead(Uri.parse(AutomixAnalysisSource.opusUri(it.videoId))) }

        // The video, as much as has arrived (it is usually the track playing,
        // and the player is the one fetching it), read once for every candidate
        // and, on later passes, only from where the last one stopped.
        val copy = awaitVideoOpening(video) ?: return Measured.Retry
        mark = lap("비디오 대기", mark)
        val complete = copy.isComplete || video.localUri != null
        val job = currentCoroutineContext()[Job]
        val clip = withContext(analysis) { videoFeatures(context, video, copy.key, job) } ?: return Measured.Retry
        lap("비디오 분석", mark)
        val videoNote = "비디오 ${clip.durationSeconds.roundToInt()}초" + if (complete) "" else " (받는 중)"

        // Every candidate at once; the first to line up on its opening is kept
        // and shown straight away, while all of them go on to the whole record.
        val earlyGate = Mutex()
        var earlySent = false
        val tried = coroutineScope {
            candidates.map { original ->
                async {
                    tryRecording(context, original, video, clip, complete, job) { firstResult, firstNote ->
                        earlyGate.withLock {
                            if (earlySent) return@withLock
                            earlySent = true
                            early(
                                Measured.Done(
                                    firstResult, complete = false, videoBytes = copy.cachedPrefix,
                                    videoSeconds = clip.durationSeconds, recordPartial = true,
                                    note = "$firstNote\n$times · $videoNote (원곡 앞부분으로 먼저 맞춤)",
                                ),
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        val note = (tried.map { it.note } + "$times · $videoNote · 전체 ${seconds(System.currentTimeMillis() - began)}")
            .joinToString("\n")
        // Nothing to measure against came down: worth another go later.
        if (tried.none { it.reached }) return Measured.Retry
        val best = tried.filter { it.result != null }.minByOrNull { it.result!!.chance }
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
     * [original] against [clip]: at once if its profiles are kept, otherwise
     * on its opening as soon as that much is on disk (handing a match to
     * [first]) and then on all of it.
     */
    private suspend fun tryRecording(
        context: Context,
        original: Song,
        video: Song,
        clip: ChromaAlignment.Features,
        complete: Boolean,
        job: Job?,
        first: suspend (ChromaAlignment.Result, String) -> Unit,
    ): Tried {
        val began = System.currentTimeMillis()
        val name = "'${original.title}' / '${original.artist}'"
        suspend fun aligned(record: ChromaAlignment.Features): Pair<ChromaAlignment.Result?, String> =
            withContext(analysis) {
                val result = ChromaAlignment.align(record, clip, videoComplete = complete)
                result to ChromaAlignment.lastVerdict
            }
        fun log(verdict: String, record: ChromaAlignment.Features, stage: String) = TrackLog.d(
            TAG,
            "chroma $name (${original.videoId}) -> ${video.videoId} [$stage]: $verdict " +
                "(record ${record.durationSeconds.roundToInt()} s, video ${clip.durationSeconds.roundToInt()} s" +
                (if (complete) "" else " so far") + ")",
            video.videoId,
        )

        kept(context, original.videoId)?.let { record ->
            val (result, verdict) = aligned(record)
            log(verdict, record, "kept")
            return Tried(original, result, reached = true, partial = false, note = "$name: $verdict (저장된 원곡, ${seconds(System.currentTimeMillis() - began)})")
        }
        if (original.localUri == null) {
            val uri = Uri.parse(AutomixAnalysisSource.opusUri(original.videoId))
            val head = withTimeoutOrNull(HEAD_TIMEOUT_MS + 2_000L) { AudioCache.awaitAnalysisHead(uri, HEAD_TIMEOUT_MS) } == true
            if (!head) return Tried(original, null, reached = false, partial = false, note = "$name: 원곡을 받지 못함")
            val headAt = System.currentTimeMillis()
            if (VersionAudioAligner.renditionToMeasure(original.videoId)?.isComplete != true) {
                // The opening first: most of the time it is all the answer needs.
                withContext(analysis) { recordFeatures(context, original, job) }?.let { opening ->
                    val (result, verdict) = aligned(opening)
                    log(verdict, opening, "opening")
                    if (result != null) {
                        first(result, "$name: $verdict (원곡 앞 ${opening.durationSeconds.roundToInt()}초, ${seconds(System.currentTimeMillis() - began)})")
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
    private fun videoFeatures(context: Context, video: Song, copyKey: String, job: Job?): ChromaAlignment.Features? =
        // One reader per recording at a time: two covers of one song measured
        // at once may both be reading its record.
        synchronized(readingLocks.getOrPut(video.videoId) { Any() }) { readFeatures(context, video, copyKey, job) }

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

    private fun readFeatures(context: Context, video: Song, copyKey: String, job: Job?): ChromaAlignment.Features? {
        var reading = synchronized(videoReadings) { videoReadings[video.videoId] }?.takeIf { it.copyKey == copyKey }
        val source = VersionAudioAligner.openDataSource(context, video) ?: return null
        try {
            val from = reading?.untilSeconds ?: 0.0
            AudioDecoder.decodeMonoStream(source, from, MAX_SECONDS, abort = { job?.isActive == false }) { chunk, sampleRate, at ->
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
    private fun recordFeatures(context: Context, original: Song, job: Job?): ChromaAlignment.Features? =
        videoFeatures(context, original, VersionAudioAligner.renditionToMeasure(original.videoId)?.key ?: "record", job)

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

    /** A refusal on this much of a video is the measurement giving up. */
    private const val GIVE_UP_AFTER_SECONDS = 150.0

    /** ko-27: covers read singer-first, title-only searches for covers that don't name the original. */
    private const val SEARCH_VERSION = 2 // ko-36: a search that failed outright is no longer kept as "no original"

    /** Measurements kept before this version (the loudness one) are measured again. */
    private const val AUTO_VERSION = 3 // ko-36: bass-heavy songs were refused as "no contrast"

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
        // Whisper is slow and paid for by the minute, so an automatic request
        // gets it only once the measurement has given up — while the free
        // measurement may still time the video, the quick engines answer ahead.
        val entry = store(context).get(videoId)
        val engines = when {
            !automatic -> ALL_ENGINES
            !measurementGaveUp(context, videoId) -> AHEAD_ENGINES
            // Already answered by Gemini: Whisper alone, to do better.
            entry?.aiTextKey == key && entry.ai != null && entry.aiEngine != ENGINE_WHISPER -> listOf(ENGINE_WHISPER)
            else -> ALL_ENGINES
        }
        // One request per video, lyrics and engines, whoever asks — the
        // button, the song starting, the next-song preparation.
        val flight = "$videoId:$key:${engines.joinToString(",")}"
        val fresh = aiScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            askAi(context, videoId, lines, sung, key, engines)
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
        if (audio != null && com.music.bitchord.data.lyrics.Deepgram.usable) {
            val language = com.music.bitchord.data.lyrics.Deepgram.languageFor(texts)
            for ((model, name) in listOf(
                com.music.bitchord.data.lyrics.Deepgram.MODEL_NOVA to ENGINE_DEEPGRAM,
                com.music.bitchord.data.lyrics.Deepgram.MODEL_WHISPER to ENGINE_WHISPER,
            ).filter { it.second in engines }) {
                if (name == ENGINE_WHISPER) store(context).update(videoId) { it.copy(whisperKey = key) }
                val asked = System.currentTimeMillis()
                val heard = com.music.bitchord.data.lyrics.Deepgram.words(audio, language, model)
                val starts = heard?.let { com.music.bitchord.data.lyrics.LyricsWordAlign.placeLines(texts, it) }
                val fitted = starts?.let { fitAi(lines, sung.map { s -> s.index }, it) }
                    ?.takeIf { (it.matchPercent ?: 0) >= MIN_DEEPGRAM_MATCH }
                TrackLog.d(
                    TAG,
                    "$name timing ($language, ${(System.currentTimeMillis() - asked) / 1000}s): " + when {
                        heard == null -> "no answer"
                        fitted == null -> "${heard.size} words, ${starts?.count { it != null } ?: 0} of ${sung.size} lines placed: not enough"
                        else -> "${heard.size} words, rate ${"%.3f".format(java.util.Locale.ROOT, fitted.rate)}, " +
                            "shift ${shownShiftMs(lines, fitted)} ms, ${fitted.matchPercent}% agree"
                    },
                    videoId,
                )
                if (fitted != null) {
                    adjustment = fitted
                    engine = name
                    break
                }
                // A key Deepgram turned away is turned away for Whisper too.
                if (!com.music.bitchord.data.lyrics.Deepgram.usable) break
            }
        }
        // Gemini when Deepgram isn't set up, had no audio to hear, or heard too little.
        var answer: List<Long?>? = null
        if (adjustment == null && ENGINE_GEMINI in engines) {
            engine = ENGINE_GEMINI
            answer = LyricsTranslation.placeLinesInVideo(
                videoId,
                sung.map { LyricsTranslation.LineToPlace(it.value.text, it.value.timeMs) },
                audioWav = audio,
            )
            adjustment = answer?.let { fitAi(lines, sung.map { s -> s.index }, it) }
            TrackLog.d(
                TAG,
                "AI timing (${if (audio != null) "audio" else "video"}, ${(System.currentTimeMillis() - started) / 1000}s): " +
                    when {
                        answer == null -> "no answer"
                        adjustment == null -> "answers agree on no line (${answer.count { it != null }} of ${sung.size} placed)"
                        else -> "rate ${"%.3f".format(java.util.Locale.ROOT, adjustment.rate)}, " +
                            "shift ${shownShiftMs(lines, adjustment)} ms, ${adjustment.matchPercent}% agree"
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

    /** Forgets the AI answer for [videoId] (and a correction made on top of it); the measurement applies again. */
    suspend fun clearAi(context: Context, videoId: String) {
        store(context).update(videoId) {
            it.copy(aiTextKey = null, ai = null, aiShiftMs = null, aiStarts = null, manualMs = null, aiPreferred = false)
        }
        _changes.tryEmit(videoId)
    }

    private const val MIN_AI_LINES = 3

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
        cachedBias[engine]?.let { return it }
        val samples = context.applicationContext.getSharedPreferences(BIAS_PREFS, Context.MODE_PRIVATE)
            .getString(biasKey(engine), null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.sorted()
        val bias = if (samples.size < BIAS_MIN_SAMPLES) 0L else samples[samples.size / 2].coerceIn(-BIAS_MAX_MS, BIAS_MAX_MS)
        cachedBias[engine] = bias
        return bias
    }

    const val ENGINE_DEEPGRAM = "Deepgram"
    const val ENGINE_WHISPER = "Whisper"
    const val ENGINE_GEMINI = "Gemini"

    private val ALL_ENGINES = listOf(ENGINE_DEEPGRAM, ENGINE_WHISPER, ENGINE_GEMINI)
    private val AHEAD_ENGINES = listOf(ENGINE_DEEPGRAM, ENGINE_GEMINI)

    /**
     * Whether an automatic request could still do better for [videoId]: the
     * measurement gave up, and Whisper hasn't heard these lyrics yet while
     * what is kept (if anything) is a quicker engine's answer.
     */
    suspend fun wantsWhisper(context: Context, videoId: String, lines: List<LyricLine>): Boolean {
        if (!com.music.bitchord.data.lyrics.Deepgram.usable) return false
        val entry = store(context).get(videoId) ?: return false
        if (!measurementGaveUp(context, videoId)) return false
        val key = textKey(lines)
        if (entry.whisperKey == key) return false
        return !(entry.aiTextKey == key && entry.aiEngine == ENGINE_WHISPER)
    }

    /** Deepgram's answer is used only when this much of the song agrees with its line; Gemini otherwise. */
    private const val MIN_DEEPGRAM_MATCH = 35

    private const val BIAS_PREFS = "lyrics_timing"
    private const val BIAS_KEY = "ai_bias_samples"
    private const val BIAS_SAMPLES = 40
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
        /** Who gave the AI answer: [ENGINE_DEEPGRAM], [ENGINE_WHISPER] or [ENGINE_GEMINI] (null: Gemini, before ko-35). */
        val aiEngine: String? = null,
        /** The lyrics (text key) Whisper was last asked about, answered or not: asked once. */
        val whisperKey: Int? = null,
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
