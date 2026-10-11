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
        val lines = LyricsTranslation.transcribeVideo(videoId, durationMs)
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

    private fun silent(): Set<String> = prefs()?.getStringSet(KEY_SILENT, null).orEmpty()

    @Synchronized
    private fun rememberSilent(videoId: String) {
        val prefs = prefs() ?: return
        val next = LinkedHashSet(prefs.getStringSet(KEY_SILENT, null).orEmpty())
        next += videoId
        while (next.size > MAX_SILENT) next.remove(next.first())
        prefs.edit().putStringSet(KEY_SILENT, next).apply()
    }
}
