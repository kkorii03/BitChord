package com.music.bitchord.playback

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lyric translations made before anyone taps the button.
 *
 *  - [nextTrack]: while a song plays, the next one's lyrics are looked up and
 *    translated, so its translate button answers at once. The lyrics land in
 *    [LyricsRepository]'s memo, which is what makes the player show the very
 *    lines the translation was made from.
 *  - [downloaded]: a downloaded song's lyrics are read back out of its file and
 *    translated, and the answer is kept for good beside the download — so the
 *    translation is there offline, like the lyrics themselves.
 *
 * One request at a time, never on the main thread, and never allowed to fail
 * anything else: every error here just means the button does the work later.
 */
internal object TranslationAhead {
    private const val TAG = "BitChord"

    private const val LOOKUP_TIMEOUT_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    @Volatile
    private var nextJob: Job? = null

    @Volatile
    private var nextId: String? = null

    /** The language the translate button would ask for, worked out the way the player does. */
    fun targetLanguage(context: Context): String {
        val chosen = AppSettings.translationLanguage.value.trim()
        if (chosen.isNotEmpty()) return chosen
        val locale = AppCompatDelegate.getApplicationLocales().get(0)
            ?: context.resources.configuration.locales.get(0)
        return locale?.language.orEmpty().ifBlank { "en" }
    }

    /**
     * Called as a track starts with what plays after it, or null at the end of
     * the queue. Gets the next track ready so it opens with everything in
     * place: its lyrics always (they land in [LyricsRepository]'s memo, which
     * the player then answers from at once), its translation when the
     * translate button or auto-translate would ask for one, and its lyrics
     * moved onto the video's own clock.
     */
    fun nextTrack(context: Context, next: Song?) {
        if (next == null || !AppSettings.syncedLyrics.value) {
            nextJob?.cancel()
            nextId = null
            return
        }
        // Same next song as last time — a quality swap, a seek — nothing new to do.
        if (next.videoId == nextId && nextJob?.isActive == true) return
        nextJob?.cancel()
        nextId = next.videoId
        val app = context.applicationContext
        nextJob = scope.launch {
            // A download carries its own lyrics: nothing to look up or time.
            next.localUri?.let { uri ->
                val embedded = EmbeddedLyrics.forUri(app, uri)
                if (embedded != null) {
                    if (AppSettings.prefetchTranslation.value || AppSettings.autoTranslateLyrics.value) {
                        translate(next.videoId, embedded, targetLanguage(app), keep = true)
                    }
                    return@launch
                }
            }
            val found = lookUp(next) ?: return@launch
            val lines = found.lines.takeIf { lines -> lines.any { it.text.isNotBlank() } } ?: return@launch
            // The timing alongside the translation rather than after it, and
            // outside [nextJob]: this song is about to be the one playing, and
            // the next nextTrack — the moment it starts — must not throw away
            // a measurement half done for it.
            startTiming(app, next, found)
            if (AppSettings.prefetchTranslation.value || AppSettings.autoTranslateLyrics.value) {
                translate(next.videoId, lines, targetLanguage(app), keep = next.localUri != null)
            }
        }
    }

    /** Timing jobs still running: this song's, the one before (now usually playing). */
    private val timingJobs = ArrayDeque<Pair<String, Job>>()

    private fun startTiming(context: Context, song: Song, found: LyricsRepository.Result) {
        synchronized(timingJobs) {
            timingJobs.removeAll { !it.second.isActive }
            if (timingJobs.any { it.first == song.videoId }) return
            while (timingJobs.size >= MAX_TIMING_JOBS) timingJobs.removeFirst().second.cancel()
            timingJobs.addLast(song.videoId to scope.launch { prepareTiming(context, song, found) })
        }
    }

    private const val MAX_TIMING_JOBS = 2

    /**
     * Times the next video's lyrics to it — see
     * [com.music.bitchord.playback.smart.LyricsTiming] — so they are right
     * from its first line: the measurement, and with "가사 타이밍 자동 보정"
     * on, Gemini when the measurement has nothing. Only for a video whose
     * lyrics are the record's. On any connection: the original it fetches is
     * a few megabytes, and the alternative is lyrics out of time for the
     * first half-minute of every cover.
     */
    private suspend fun prepareTiming(context: Context, song: Song, found: LyricsRepository.Result) {
        val query = com.music.bitchord.data.cover.CoverTitles.lyricsQuery(song)
        if (!query.isVideo || song.localUri != null) return
        if (found.lines.none { it.timeMs > 0 }) return
        val timing = com.music.bitchord.playback.smart.LyricsTiming
        if (timing.timedToVideo(found.source)) return
        val names = buildList {
            if (query.artistKnown) add(query.title to query.artist)
            found.searchedAs?.takeIf { query.artistKnown || it.second != query.artist }?.let(::add)
            if (!query.artistKnown) add(query.title to "")
            add(query.title to query.artist)
            addAll(query.alternatives)
        }.distinct()
        try {
            // Gemini at the same time as the measurement, not after it gives up:
            // the next song then starts with an answer either way.
            val gemini = com.music.bitchord.data.lyrics.LyricsTranslation
            val ahead = if (AppSettings.aiTimingAhead.value && gemini.geminiKeyUsable &&
                !timing.hasAi(context, song.videoId, found.lines) &&
                !timing.aiAlreadyFailed(context, song.videoId, found.lines)
            ) {
                scope.launch {
                    runCatching { timing.placeWithAi(context, song.videoId, found.lines, automatic = true) }
                        .onFailure { if (it is CancellationException) throw it }
                }
            } else {
                null
            }
            if (timing.worthMeasuring(context, song.videoId)) {
                // Its audio coming down now, not when it starts playing, so
                // the measurement has something to read before then.
                com.music.bitchord.playback.AudioCache.requestAnalysisHead(
                    android.net.Uri.parse(com.music.bitchord.playback.smart.AutomixAnalysisSource.opusUri(song.videoId)),
                )
                timing.measure(context, song.copy(isVideo = true), names, song.durationMillis())
            }
            ahead?.join()
            if (AppSettings.autoLyricsTiming.value &&
                com.music.bitchord.data.lyrics.LyricsTranslation.geminiKeyUsable &&
                timing.measurementGaveUp(context, song.videoId) &&
                !timing.hasAi(context, song.videoId, found.lines) &&
                !timing.aiAlreadyFailed(context, song.videoId, found.lines)
            ) {
                timing.placeWithAi(context, song.videoId, found.lines, automatic = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.d(TAG, "no timing ahead for ${song.videoId}: ${e.message}")
        }
    }

    /** Called once a download is saved at [uri]. */
    fun downloaded(context: Context, song: Song, uri: String) {
        if (!AppSettings.keepDownloadTranslation.value) return
        val app = context.applicationContext
        scope.launch {
            val lines = EmbeddedLyrics.forUri(app, uri) ?: return@launch
            translate(song.videoId, lines, targetLanguage(app), keep = true)
        }
    }

    private suspend fun lookUp(song: Song): LyricsRepository.Result? {
        val sources = AppSettings.lyricsSources.value
        val durationMs = song.durationMillis()
        // The player matches with a known length; a lookup without one may
        // settle on different lines, and those would not be the ones it shows.
        if (sources.isEmpty() || durationMs <= 0L) return null
        // The same search the player makes — a cover as its song.
        val coverQuery = com.music.bitchord.data.cover.CoverTitles.lyricsQuery(song)
        return try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                LyricsRepository.lyrics(
                    videoId = song.videoId,
                    title = coverQuery.title,
                    artist = coverQuery.artist,
                    durationMs = durationMs,
                    album = song.albumName,
                    sources = sources,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                    isVideo = coverQuery.isVideo,
                    alternatives = coverQuery.alternatives,
                    artistKnown = coverQuery.artistKnown,
                    transcribe = song.localUri == null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.d(TAG, "no lyrics ahead for ${song.videoId}: ${e.message}")
            null
        }
    }

    private suspend fun translate(trackId: String, lines: List<LyricLine>, target: String, keep: Boolean) {
        try {
            gate.withLock {
                withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                    LyricsTranslation.translate(trackId, lines, target, keep = keep)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.d(TAG, "no translation ahead for $trackId: ${e.message}")
        }
    }
}
