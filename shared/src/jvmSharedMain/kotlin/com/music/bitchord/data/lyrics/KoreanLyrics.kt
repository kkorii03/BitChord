package com.music.bitchord.data.lyrics

import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/*
 * Line-synced lyrics from three Korean services, for the Korean catalogue the
 * others mostly miss ??indie, OST, the songs covers are made of. Each is the
 * plain, unauthenticated requests their own web players make; nothing is
 * signed or impersonated.
 *
 * Each searches by name, so each holds every hit to the title *and* the artist
 * before taking its lyrics, and picks the closest length among those that pass:
 * the wrong song's lyrics are worse than none.
 */

/** Bugs (music.bugs.co.kr): search page → track info → "seconds|line＃…" lyrics. */
object BugsLyrics {

    private const val REFERER = "https://music.bugs.co.kr/"

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode("$title $artist", "UTF-8")
        val page = lyricsGetAsBrowser("https://music.bugs.co.kr/search/track?q=$query", REFERER, "text/html")
            ?: return@withContext null
        val ids = TRACK_ROW.findAll(page).map { it.groupValues[1] }.distinct().take(MAX_CANDIDATES).toList()
        // The candidates' details all at once rather than one after another.
        val hits = coroutineScope {
            ids.map { id ->
                async {
                    val body = lyricsGetAsBrowser("https://music.bugs.co.kr/player/track/$id", REFERER)
                        ?: return@async null
                    val track = runCatching { lyricsJson.decodeFromString<TrackResponse>(body).track }.getOrNull()
                        ?: return@async null
                    KoreanMatch.Hit(id, track.track_title, track.artist_nm, KoreanMatch.seconds(track.len))
                }
            }.awaitAll().filterNotNull()
        }
        val best = KoreanMatch.best(hits, title, artist, durationMs) ?: return@withContext null
        val timed = lyricsGetAsBrowser("https://music.bugs.co.kr/player/lyrics/T/${best.id}", REFERER)
            ?.let { runCatching { lyricsJson.decodeFromString<LyricsResponse>(it).lyrics }.getOrNull() }
            ?.let(::parseTimed)
        timed?.takeIf { it.isNotEmpty() } ?: lyricsGetAsBrowser("https://music.bugs.co.kr/player/lyrics/N/${best.id}", REFERER)
            ?.let { runCatching { lyricsJson.decodeFromString<LyricsResponse>(it).lyrics }.getOrNull() }
            ?.let(KoreanMatch::plain)
    }

    /** "0.3|이 밤 그날의 반딧불을＃8.9|당신의 창 가까이 보낼게요＃…" */
    internal fun parseTimed(raw: String?): List<LyricLine>? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.split('＃', '#').mapNotNull { part ->
            val bar = part.indexOf('|')
            if (bar <= 0) return@mapNotNull null
            val seconds = part.substring(0, bar).trim().toDoubleOrNull() ?: return@mapNotNull null
            LyricLine(timeMs = (seconds * 1000).toLong(), text = part.substring(bar + 1).trim())
        }
        return lines.takeIf { it.size >= 2 }
    }

    private const val MAX_CANDIDATES = 3

    /** A result row: `<tr … trackId="3671311" … rowType="track">`. */
    private val TRACK_ROW = Regex("""<tr\b[^>]*\btrackId="(\d+)"[^>]*\browType="track"""")

    @Serializable
    private data class TrackResponse(val track: Track? = null)

    @Serializable
    @Suppress("PropertyName")
    private data class Track(
        val track_title: String = "",
        val artist_nm: String = "",
        val len: String? = null,
    )

    @Serializable
    private data class LyricsResponse(val lyrics: String? = null)
}

/** VIBE (Naver): track search → lyric with start times per line. */
object VibeLyrics {

    private const val REFERER = "https://vibe.naver.com/"
    private const val API = "https://apis.naver.com/vibeWeb/musicapiweb"

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode("$title $artist", "UTF-8")
        val body = lyricsGetAsBrowser("$API/v4/search/track?query=$query&start=1&display=5&sort=RELEVANCE", REFERER)
            ?: return@withContext null
        val tracks = runCatching { lyricsJson.decodeFromString<SearchResponse>(body).response.result.tracks }
            .getOrNull().orEmpty()
            .filter { it.hasLyric || it.hasSyncLyric }
        val hits = tracks.map { track ->
            KoreanMatch.Hit(
                id = track.trackId.toString(),
                title = track.trackTitle,
                artist = track.artists.joinToString(", ") { it.artistName },
                seconds = KoreanMatch.seconds(track.playTime),
            )
        }
        val best = KoreanMatch.best(hits, title, artist, durationMs) ?: return@withContext null
        val lyricBody = lyricsGetAsBrowser("$API/vibe/v4/lyric/${best.id}", REFERER) ?: return@withContext null
        val lyric = runCatching { lyricsJson.decodeFromString<LyricResponse>(lyricBody).response.result.lyric }
            .getOrNull() ?: return@withContext null
        synced(lyric.syncLyric) ?: lyric.normalLyric?.text
            // A plain text with no line breaks is one paragraph, not lyrics to show line by line.
            ?.takeIf { it.contains('\n') }
            ?.let(KoreanMatch::plain)
    }

    private fun synced(sync: SyncLyric?): List<LyricLine>? {
        val starts = sync?.startTimeIndex ?: return null
        val texts = sync.contents.firstOrNull()?.text ?: return null
        if (starts.size != texts.size || starts.size < 2) return null
        return starts.zip(texts).map { (seconds, text) ->
            LyricLine(timeMs = (seconds * 1000).toLong(), text = text.trim())
        }
    }

    @Serializable private data class SearchResponse(val response: SearchBody = SearchBody())
    @Serializable private data class SearchBody(val result: SearchResult = SearchResult())
    @Serializable private data class SearchResult(val tracks: List<Track> = emptyList())

    @Serializable
    private data class Track(
        val trackId: Long = 0,
        val trackTitle: String = "",
        val artists: List<Artist> = emptyList(),
        val playTime: String? = null,
        val hasLyric: Boolean = false,
        val hasSyncLyric: Boolean = false,
    )

    @Serializable private data class Artist(val artistName: String = "")

    @Serializable private data class LyricResponse(val response: LyricBody = LyricBody())
    @Serializable private data class LyricBody(val result: LyricResult = LyricResult())
    @Serializable private data class LyricResult(val lyric: Lyric? = null)

    @Serializable
    private data class Lyric(
        val normalLyric: NormalLyric? = null,
        val syncLyric: SyncLyric? = null,
    )

    @Serializable private data class NormalLyric(val text: String? = null)

    @Serializable
    private data class SyncLyric(
        val startTimeIndex: List<Double> = emptyList(),
        val contents: List<SyncContent> = emptyList(),
    )

    @Serializable private data class SyncContent(val text: List<String> = emptyList())
}

/** The matching both Korean providers share. */
internal object KoreanMatch {

    data class Hit(val id: String, val title: String, val artist: String, val seconds: Int?)

    /**
     * A length further off than this is a different take (or a cover of a
     * different length) — still the right words, so it is allowed, but only
     * when nothing closer passed.
     */
    private const val MAX_DRIFT_SECONDS = 60

    /**
     * The hit whose title and artist both name the song asked for, closest in
     * length first. Null when none does.
     */
    fun best(hits: List<Hit>, title: String, artist: String, durationMs: Long): Hit? {
        val wanted = (durationMs / 1000).toInt()
        return hits
            .filter { sameTitle(it.title, title) && sameArtist(it.artist, artist) }
            .filter { wanted <= 0 || it.seconds == null || abs(it.seconds - wanted) <= MAX_DRIFT_SECONDS }
            .minByOrNull { if (wanted <= 0 || it.seconds == null) Int.MAX_VALUE / 2 else abs(it.seconds - wanted) }
    }

    /** "회전목마 (Vocal 손주희)" is "회전목마"; a bracket never makes or breaks the match. */
    fun sameTitle(got: String, wanted: String): Boolean {
        val a = key(got)
        val b = key(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (a.length >= 2 && b.length >= 2 && (a.startsWith(b) || b.startsWith(a)))
    }

    /**
     * "에피톤 프로젝트(Epitone Project)" answers to either name, and a list of
     * credits answers to any one of them.
     */
    fun sameArtist(got: String, wanted: String): Boolean {
        val wantedNames = names(wanted)
        if (wantedNames.isEmpty()) return true
        val gotNames = names(got)
        return gotNames.any { g -> wantedNames.any { w -> g == w || (minOf(g.length, w.length) >= 2 && (g.contains(w) || w.contains(g))) } }
    }

    private fun names(credit: String): List<String> {
        val parts = mutableListOf<String>()
        BRACKET.findAll(credit).forEach { parts += it.groupValues[1] }
        parts += BRACKET.replace(credit, " ")
        return parts.flatMap { it.split(',', '&', '/', '·', '、') }
            .flatMap { it.split(Regex("""(?i)\s(?:feat\.?|ft\.?|with|x)\s""")) }
            .map(::key)
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun key(text: String): String =
        BRACKET.replace(text, " ").lowercase().filter { it.isLetterOrDigit() }

    private val BRACKET = Regex("""[(\[（【]([^)\]）】]*)[)\]）】]""")

    /** "05:13" → 313. */
    fun seconds(text: String?): Int? {
        val parts = text?.trim()?.split(':')?.takeIf { it.size in 2..3 } ?: return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers.fold(0) { total, part -> total * 60 + part }.takeIf { it > 0 }
    }

    /** Plain lyrics, one line each — the "no timings" shape the player already knows. */
    fun plain(text: String?): List<LyricLine>? =
        text?.replace("\r\n", "\n")?.lines()?.map { LyricLine(timeMs = 0L, text = it.trim()) }
            ?.dropWhile { it.text.isEmpty() }?.dropLastWhile { it.text.isEmpty() }
            ?.takeIf { lines -> lines.count { it.text.isNotEmpty() } >= 2 }
}
