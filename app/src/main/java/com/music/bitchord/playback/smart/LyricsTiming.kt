package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.lyrics.artistForLyricsSearch
import com.music.bitchord.data.lyrics.forLyricsSearch
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * runs on its own clock — a longer intro, a stream cut that starts mid-chat —
 * so the right words light up at the wrong moment. Two ways to fix that, in
 * order of cost:
 *
 *  1. **Automatic, free** — [measure]: fetch the opening of the original
 *     recording and slide its loudness curve against the video's
 *     ([VersionAudioAligner], the same measurement the audio/video switch
 *     uses). Covers sung over the original backing track line up well; a
 *     re-arranged one does not, and then nothing is applied.
 *  2. **On request, Gemini** — [placeWithAi]: Gemini watches the YouTube video
 *     and says when each line starts. Its answer is checked: if the lines all
 *     moved by about the same amount, that one shift is applied (the record's
 *     own fine timing is better than Gemini's to-the-second guesses); if not —
 *     a different tempo, a cut — each line takes Gemini's time.
 *
 * Both answers are kept per video on disk, so each is paid for once.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object LyricsTiming {

    /** How the lines were moved. */
    sealed interface Adjustment {
        /** Every line by the same amount — what [measure] finds. */
        data class Shift(val ms: Long, val byAi: Boolean) : Adjustment
        /** Each line to its own start — Gemini's, for a video on a different pace. */
        data class Retimed(val startsMs: List<Long>) : Adjustment
    }

    /** Sources whose timings already belong to the video they were fetched for. */
    fun timedToVideo(source: LyricsSource?): Boolean = source == LyricsSource.YOUTUBE_TRANSCRIPT ||
        source == LyricsSource.YOUTUBE_MUSIC || source == LyricsSource.SIMP_MUSIC

    /** Fires the video id whose timing just changed, for screens showing it. */
    val changes: SharedFlow<String> get() = _changes
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Below this a shift is noise in the measurement, not an offset. */
    private const val MIN_SHIFT_MS = 300L

    /** What is known for [videoId] and these [lines], AI first. */
    suspend fun known(context: Context, videoId: String, lines: List<LyricLine>): Adjustment? {
        val entry = store(context).get(videoId) ?: return null
        entry.aiShiftMs?.takeIf { entry.aiTextKey == textKey(lines) }?.let { return Adjustment.Shift(it, byAi = true) }
        entry.aiStarts?.takeIf { entry.aiTextKey == textKey(lines) && it.size == lines.size }
            ?.let { return Adjustment.Retimed(it) }
        return entry.autoShiftMs?.takeIf { abs(it) >= MIN_SHIFT_MS }?.let { Adjustment.Shift(it, byAi = false) }
    }

    /** Whether [measure] has already run for [videoId] (successfully or not). */
    suspend fun measured(context: Context, videoId: String): Boolean = store(context).get(videoId)?.autoChecked == true

    /** Whether an AI answer is kept for [videoId]. */
    suspend fun hasAi(context: Context, videoId: String): Boolean =
        store(context).get(videoId)?.let { it.aiShiftMs != null || it.aiStarts != null } == true

    /**
     * Measures how far [video] runs behind (positive) or ahead of the original
     * recording named [title] / [artist]. Null when the original can't be found
     * or the two don't line up convincingly; either way the answer is kept.
     */
    suspend fun measure(context: Context, video: Song, title: String, artist: String): Long? {
        val store = store(context)
        store.get(video.videoId)?.takeIf { it.autoChecked }?.let { return it.autoShiftMs }
        val shift = try {
            measureFresh(context, video, title, artist)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        // Only a finished measurement is remembered: a dropped connection is
        // worth trying again next time, a weak correlation is not.
        if (shift !is Measured.Done) return null
        store.update(video.videoId) { it.copy(autoChecked = true, autoShiftMs = shift.ms) }
        _changes.tryEmit(video.videoId)
        return shift.ms
    }

    private sealed interface Measured {
        data class Done(val ms: Long?) : Measured
        data object Retry : Measured
    }

    private suspend fun measureFresh(context: Context, video: Song, title: String, artist: String): Measured {
        val original = findRecording(title, artist) ?: return Measured.Done(null)
        if (original.videoId == video.videoId) return Measured.Done(0L)
        val heads = withTimeoutOrNull(HEAD_TIMEOUT_MS) {
            coroutineScope {
                val a = async { awaitHead(original) }
                val b = async { awaitHead(video) }
                a.await() to b.await()
            }
        } ?: return Measured.Retry
        if (!heads.first && !heads.second) return Measured.Retry
        val offset = withTimeoutOrNull(MEASURE_TIMEOUT_MS) {
            VersionAudioAligner.findOffsetMs(context, sourceSong = original, targetSong = video)
        }
        return Measured.Done(offset)
    }

    private suspend fun awaitHead(song: Song): Boolean =
        song.localUri != null ||
            AudioCache.awaitAnalysisHead(Uri.parse(AutomixAnalysisSource.opusUri(song.videoId)), HEAD_TIMEOUT_MS)

    private const val HEAD_TIMEOUT_MS = 25_000L
    private const val MEASURE_TIMEOUT_MS = 20_000L

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

    /**
     * Asks Gemini where each of [lines] falls in the YouTube video [videoId]
     * and keeps the answer. Null when Gemini had nothing usable.
     */
    suspend fun placeWithAi(context: Context, videoId: String, lines: List<LyricLine>): Adjustment? {
        val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
        if (sung.size < 2) return null
        val answer = LyricsTranslation.placeLinesInVideo(
            videoId,
            sung.map { LyricsTranslation.LineToPlace(it.value.text, it.value.timeMs) },
        ) ?: return null
        val adjustment = fit(lines, sung.map { it.index }, answer) ?: return null
        val key = textKey(lines)
        store(context).update(videoId) {
            when (adjustment) {
                is Adjustment.Shift -> it.copy(aiTextKey = key, aiShiftMs = adjustment.ms, aiStarts = null)
                is Adjustment.Retimed -> it.copy(aiTextKey = key, aiShiftMs = null, aiStarts = adjustment.startsMs)
            }
        }
        _changes.tryEmit(videoId)
        return adjustment
    }

    /** Forgets the AI answer for [videoId]; the automatic one, if any, applies again. */
    suspend fun clearAi(context: Context, videoId: String) {
        store(context).update(videoId) { it.copy(aiTextKey = null, aiShiftMs = null, aiStarts = null) }
        _changes.tryEmit(videoId)
    }

    /** How far apart "the same shift" may be, line to line, in Gemini's answer. */
    private const val CONSISTENT_WITHIN_MS = 1_500L

    /**
     * Gemini's per-line starts turned into an [Adjustment]. When most lines
     * moved by about the same amount, that is one shift (their median); when
     * they did not, every line gets a start of its own — Gemini's where it
     * gave one, and where it didn't, the shift of its nearest answered
     * neighbours — never earlier than the line before.
     */
    internal fun fit(lines: List<LyricLine>, indices: List<Int>, starts: List<Long?>): Adjustment? {
        val deltas = indices.zip(starts).mapNotNull { (index, start) ->
            start?.let { index to (it - lines[index].timeMs) }
        }
        if (deltas.size < 2 || deltas.size * 2 < indices.size) return null
        val median = deltas.map { it.second }.sorted()[deltas.size / 2]
        val close = deltas.filter { abs(it.second - median) <= CONSISTENT_WITHIN_MS }
        if (close.size * 10 >= deltas.size * 7) {
            val shift = close.map { it.second }.average().toLong()
            return Adjustment.Shift(shift, byAi = true)
        }
        // Different pace: each line on its own clock.
        val known = deltas.toMap()
        val result = ArrayList<Long>(lines.size)
        var previous = 0L
        lines.forEachIndexed { index, line ->
            val delta = known[index] ?: run {
                val before = known.keys.filter { it < index }.maxOrNull()
                val after = known.keys.filter { it > index }.minOrNull()
                when {
                    before != null && after != null -> {
                        val t = (index - before).toDouble() / (after - before)
                        (known.getValue(before) + t * (known.getValue(after) - known.getValue(before))).toLong()
                    }
                    before != null -> known.getValue(before)
                    after != null -> known.getValue(after)
                    else -> median
                }
            }
            val start = maxOf(previous, line.timeMs + delta, 0L)
            result += start
            previous = start
        }
        return Adjustment.Retimed(result)
    }

    /** [lines] with [adjustment] applied; words, syllables and backing vocals move with their line. */
    fun apply(lines: List<LyricLine>, adjustment: Adjustment): List<LyricLine> = when (adjustment) {
        is Adjustment.Shift -> lines.map { it.shifted(adjustment.ms) }
        is Adjustment.Retimed -> lines.mapIndexed { index, line ->
            val start = adjustment.startsMs.getOrNull(index) ?: return@mapIndexed line
            line.shifted(start - line.timeMs)
        }
    }

    private fun LyricLine.shifted(delta: Long): LyricLine {
        if (delta == 0L) return this
        fun at(ms: Long) = (ms + delta).coerceAtLeast(0L)
        return copy(
            timeMs = at(timeMs),
            words = words.map { it.shifted(delta) },
            sungUntilMs = sungUntilMs?.let(::at),
            background = background?.shifted(delta),
        )
    }

    private fun LyricWord.shifted(delta: Long): LyricWord {
        fun at(ms: Long) = (ms + delta).coerceAtLeast(0L)
        return copy(
            startMs = at(startMs),
            endMs = at(endMs),
            syllables = syllables.map { it.copy(startMs = at(it.startMs), endMs = at(it.endMs)) },
        )
    }

    /** Ties an AI answer to the words it was made for: other lyrics, other answer. */
    private fun textKey(lines: List<LyricLine>): Int = lines.joinToString("\n") { it.text }.hashCode()

    // ---- Kept on disk --------------------------------------------------------

    @Serializable
    private data class Entry(
        val autoChecked: Boolean = false,
        val autoShiftMs: Long? = null,
        val aiTextKey: Int? = null,
        val aiShiftMs: Long? = null,
        val aiStarts: List<Long>? = null,
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
