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

    /** Called as a track starts with what plays after it, or null at the end of the queue. */
    fun nextTrack(context: Context, next: Song?) {
        if (next == null || !AppSettings.prefetchTranslation.value || !AppSettings.syncedLyrics.value) {
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
            val lines = linesFor(app, next) ?: return@launch
            translate(next.videoId, lines, targetLanguage(app), keep = next.localUri != null)
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

    private suspend fun linesFor(context: Context, song: Song): List<LyricLine>? {
        song.localUri?.let { uri ->
            EmbeddedLyrics.forUri(context, uri)?.let { return it }
        }
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
                )
            }?.lines?.takeIf { lines -> lines.any { it.text.isNotBlank() } }
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
