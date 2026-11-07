package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * SyncLRC — one public aggregator asked for the karaoke (word) sheet of the
 * named recording.
 *
 * The service fans out to LRCLIB, Deezer and the Apple-timing hosts and caches
 * what it finds; the app already asks those hosts one by one. What this adds is
 * coverage: 3 of the 21 songs no source here had a word sheet for came back
 * word-timed from this one, plus six more in the Korean sample (measured
 * 2026-11-07).
 *
 * It answers one row, so the row is matched against the recording with
 * [RecordingMatch] like any other name search. Only a karaoke answer is taken:
 * a "synced" answer is a line sheet, which the line-only sources already cover.
 *
 * ### The queue
 *
 * The public instance allows 30 requests a minute. The app races every source
 * at once and a skip runs another lookup, so calls go through [spaced]: one at
 * a time, [MIN_INTERVAL_MS] apart. The wait is one delay, not a timer — a
 * cancelled lookup leaves the queue with it.
 */
object SyncLrc {

    private const val BASE = "https://api.synclrc.dev/lyrics"
    private const val KARAOKE = "karaoke"

    /** 2.1 s apart is 28.6 a minute, inside the documented 30. */
    internal const val MIN_INTERVAL_MS = 2_100L

    private val queue = Mutex()
    private var lastAtMs = 0L

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val url = BASE.toHttpUrl().newBuilder()
                .addQueryParameter("track", title)
                .addQueryParameter("artist", artist)
                .apply {
                    val seconds = durationMs / 1000
                    if (seconds > 0) addQueryParameter("duration", seconds.toString())
                }
                .addQueryParameter("type", KARAOKE)
                .addQueryParameter("format", "lrc")
                .build()

            val body = spaced { lyricsGet(url.toString()) } ?: return@withContext null
            linesOf(body, title, artist, durationMs)
        }

    /** The answer matched to the recording; null when it is not a word sheet of it. */
    internal fun linesOf(body: String, title: String, artist: String, durationMs: Long): List<LyricLine>? {
        val row = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull() ?: return null
        val lyrics = row.lyrics?.takeIf { it.isNotBlank() } ?: return null
        if (row.type != null && !row.type.equals(KARAOKE, ignoreCase = true)) return null
        val candidate = RecordingMatch.Candidate(
            value = row,
            title = row.track,
            artist = row.artist,
            seconds = row.duration,
        )
        if (RecordingMatch.best(listOf(candidate), title, artist, durationMs) == null) return null
        return EnhancedLrc.parse(lyrics).takeIf { it.isNotEmpty() }
    }

    /** How long the next call must wait; a pure seam for the test. */
    internal fun waitMs(nowMs: Long, lastAtMs: Long): Long =
        (MIN_INTERVAL_MS - (nowMs - lastAtMs)).coerceAtLeast(0L)

    private suspend fun <T> spaced(block: suspend () -> T): T = queue.withLock {
        val wait = waitMs(System.currentTimeMillis(), lastAtMs)
        if (wait > 0) delay(wait)
        lastAtMs = System.currentTimeMillis()
        block()
    }

    @Serializable
    data class Response(
        val id: String? = null,
        val track: String? = null,
        val artist: String? = null,
        val album: String? = null,
        /** Seconds, fractional. */
        val duration: Double? = null,
        val instrumental: Boolean? = null,
        val lyrics: String? = null,
        /** `karaoke`, `synced` or `plain`. */
        val type: String? = null,
        val format: String? = null,
    )
}