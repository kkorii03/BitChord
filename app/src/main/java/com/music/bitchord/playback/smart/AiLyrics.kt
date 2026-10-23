package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.settings.AppSettings
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * "가사 못 찾으면 AI 받아쓰기": when no lyrics source has anything for a
 * track, Gemini listens to its video and writes down what is sung, timed by
 * line — see [LyricsTranslation.transcribeVideo]. What it hears is kept with
 * the rest of the lyrics ([LyricsRepository]'s disk cache), so a video is
 * listened to once.
 *
 * Asked at most once per video: a video with nothing sung in it is
 * remembered for good, one Gemini didn't answer for until the app restarts
 * (a refusal for a well-known song's words comes back the same every time).
 */
object AiLyrics {

    private const val TAG = "AiLyrics"
    private const val PREFS = "ai_lyrics"
    private const val KEY_SILENT = "silent"
    private const val MAX_SILENT = 500

    /**
     * "Heard nothing sung" is remembered for a month: long enough that the
     * same song isn't paid for every day, short enough that a video which
     * gains a vocal later is tried again. Stored as "videoId|heardAt".
     */
    private const val SILENT_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L

    /** Longer than this is a stream or a compilation, not a song. */
    private const val MAX_DURATION_MS = 15L * 60 * 1000

    private var context: Context? = null
    private val failedThisSession = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun init(context: Context) {
        this.context = context.applicationContext
        LyricsRepository.transcriber = ::transcribe
    }

    private suspend fun transcribe(videoId: String, durationMs: Long): List<LyricLine>? {
        if (!AppSettings.aiLyrics.value || !LyricsTranslation.geminiKeyUsable) return null
        if (durationMs > MAX_DURATION_MS || videoId in failedThisSession || videoId in silent()) return null
        TrackLog.d(TAG, "no lyrics anywhere; asking Gemini to write them down", videoId)
        // The panel is told from here, not from the player's own lookup: the
        // lookup came back empty long ago and only this knows the listening
        // has actually started (it is skipped when off or already refused).
        LyricsRepository.markTranscribing(videoId, true)
        val lines = try {
            // The sound already on the phone when it is all there: heard in
            // seconds, where Gemini fetching the video itself takes a minute.
            val audio = context?.let { runCatching { VideoAudio.wav(it, videoId) }.getOrNull() }
            LyricsTranslation.transcribeVideo(videoId, durationMs, audio)
        } finally {
            LyricsRepository.markTranscribing(videoId, false)
        }
        when {
            lines == null -> {
                failedThisSession += videoId
                TrackLog.d(TAG, "Gemini gave no usable lyrics", videoId)
            }
            lines.isEmpty() -> {
                rememberSilent(videoId)
                TrackLog.d(TAG, "Gemini heard nothing sung", videoId)
            }
            else -> TrackLog.d(TAG, "Gemini wrote down ${lines.size} lines", videoId)
        }
        return lines
    }

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The videos that were silent within the last month. */
    private fun silent(): Set<String> {
        val now = System.currentTimeMillis()
        return prefs()?.getStringSet(KEY_SILENT, null).orEmpty().mapNotNull { entry ->
            val split = entry.lastIndexOf('|')
            // Entries written before the month limit had no time: keep them
            // rather than listen again straight away. They are a small legacy
            // set and age out as new silent videos replace them.
            if (split < 0) entry
            else {
                val at = entry.substring(split + 1).toLongOrNull() ?: 0L
                entry.substring(0, split).takeIf { now - at < SILENT_RETENTION_MS }
            }
        }.toSet()
    }

    @Synchronized
    private fun rememberSilent(videoId: String) {
        val prefs = prefs() ?: return
        val kept = prefs.getStringSet(KEY_SILENT, null).orEmpty().filter { entry ->
            val split = entry.lastIndexOf('|')
            if (split < 0) entry != videoId
            else entry.substring(0, split) != videoId
        }
        val next = LinkedHashSet(kept)
        next += "$videoId|${System.currentTimeMillis()}"
        while (next.size > MAX_SILENT) next.remove(next.first())
        prefs.edit().putStringSet(KEY_SILENT, next).apply()
    }
}
