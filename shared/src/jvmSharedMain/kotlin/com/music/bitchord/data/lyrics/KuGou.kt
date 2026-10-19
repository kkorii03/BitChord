package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Base64
import kotlin.math.abs
import kotlin.math.min

/**
 * Line-synced lyrics from KuGou's public mobile/lyrics endpoints — a Chinese
 * catalogue, but one that also carries a great many English and Hindi tracks
 * that the other four sources simply don't have.
 *
 * Three unauthenticated calls, chained: search the song to get its audio
 * fingerprint (`hash`), search lyrics candidates against that hash, then
 * download the winning candidate. A search by keyword alone (skipping the
 * hash) is kept as the fallback that catches everything the first two miss.
 */
object KuGou {

    private const val DURATION_TOLERANCE_SECONDS = 8

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val keyword = keyword(title, artist, album)
        val seconds = (durationMs / 1000).toInt()
        val wantsInstrumental = INSTRUMENTAL_TAG.containsMatchIn(title)

        // A candidate whose file turns out to be the "no words" placeholder is
        // a miss, not an answer: the next one is tried instead.
        fun fetch(candidate: Candidate): List<LyricLine>? {
            val lrc = download(candidate.id, candidate.accesskey) ?: return null
            return LrcLib.parseLrc(lrc).takeIf { it.isNotEmpty() && !isInstrumentalPlaceholder(it) }
        }

        searchSongs(keyword, seconds, wantsInstrumental, title).orEmpty()
            .take(MAX_HASHES)
            .firstNotNullOfOrNull { hash -> searchLyrics(hash = hash)?.firstOrNull()?.let(::fetch) }
            ?.let { return@withContext it }

        searchLyrics(keyword = keyword, seconds = seconds).orEmpty()
            // The keyword search matches on length as much as on name, so for a
            // title KuGou doesn't know it hands back whatever else runs that
            // long — usually an instrumental. Keep only a candidate whose own
            // name has something in common with the title asked for.
            .filter {
                it.song.isNotBlank() && titlesOverlap(title, it.song) &&
                    (wantsInstrumental || !INSTRUMENTAL_TAG.containsMatchIn(it.song))
            }
            .take(MAX_HASHES)
            .firstNotNullOfOrNull(::fetch)
    }

    /** How many search hits are worth downloading before giving up. */
    private const val MAX_HASHES = 3

    /**
     * KuGou's stand-in for a song with no words: a single timed line such as
     * "纯音乐，请欣赏" ("instrumental, please enjoy") or "此歌曲为没有填词的纯音乐".
     * That is "no lyrics", not lyrics.
     */
    internal fun isInstrumentalPlaceholder(lines: List<LyricLine>): Boolean {
        val words = lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        return words.size <= 3 && words.all { PLACEHOLDER.containsMatchIn(it) }
    }

    /**
     * Whether two titles share enough of their letters to plausibly name the
     * same song: at least [MIN_OVERLAP] of the shorter one's distinct letters
     * appear in the other. Deliberately loose — it only has to tell "the song
     * asked for" from "something else that happens to be as long".
     */
    internal fun titlesOverlap(wanted: String, got: String): Boolean {
        val a = letters(wanted)
        val b = letters(got)
        if (a.isEmpty() || b.isEmpty()) return false
        val shorter = if (a.size <= b.size) a else b
        val longer = if (shorter === a) b else a
        val shared = shorter.count { it in longer }
        return shared >= shorter.size * MIN_OVERLAP
    }

    private fun letters(text: String): Set<Int> =
        text.stripParenthetical().lowercase().codePoints()
            .filter { Character.isLetterOrDigit(it) }
            .toArray().toSet()

    private const val MIN_OVERLAP = 0.4

    private val PLACEHOLDER = Regex("""纯音乐|純音樂|没有填词|請欣賞|请欣赏|instrumental""", RegexOption.IGNORE_CASE)

    /** A song-list row that is the backing track or instrumental of something. */
    private val INSTRUMENTAL_TAG = Regex(
        """伴奏|纯音乐|純音樂|(?i:\binst\.?\b|instrumental|off\s*vocal|karaoke|\bmr\b)|반주""",
    )

    /**
     * Song hashes worth trying, restricted to cuts within
     * [DURATION_TOLERANCE_SECONDS] of the track being played — otherwise the
     * first result for a common title is as likely to be a cover or a remix
     * as the right recording — and ordered closest match first.
     */
    private fun searchSongs(keyword: Keyword, seconds: Int, wantsInstrumental: Boolean, title: String): List<String>? {
        val url = "https://mobileservice.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("version", "9108")
            .addQueryParameter("plat", "0")
            .addQueryParameter("pagesize", "8")
            .addQueryParameter("showtype", "0")
            .addQueryParameter("keyword", keyword.query)
            .build()
        val body = lyricsGet(url.toString()) ?: return null
        val response = runCatching { lyricsJson.decodeFromString<SearchSongResponse>(body) }.getOrNull()
        return response?.data?.info.orEmpty()
            .filter { seconds <= 0 || abs(it.duration - seconds) <= DURATION_TOLERANCE_SECONDS }
            // A backing track shares the hash search's name and length; its
            // "lyrics" are the instrumental placeholder.
            .filter { wantsInstrumental || !INSTRUMENTAL_TAG.containsMatchIn(it.songname + " " + it.filename) }
            // A song search for a name KuGou doesn't know still answers, with
            // whatever it has of that length — "동거" came back as a Chinese
            // song. The row has to carry the title asked for.
            .filter { titlesOverlap(title, it.songname.ifBlank { it.filename }) }
            .sortedBy { abs(it.duration - seconds) }
            .map { it.hash }
    }

    private fun searchLyrics(hash: String? = null, keyword: Keyword? = null, seconds: Int = -1): List<Candidate>? {
        val builder = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "pc")
        when {
            hash != null -> builder.addQueryParameter("hash", hash)
            keyword != null -> {
                builder.addQueryParameter("keyword", keyword.query)
                if (seconds > 0) builder.addQueryParameter("duration", (seconds * 1000).toString())
            }
            else -> return null
        }
        val body = lyricsGet(builder.build().toString()) ?: return null
        val response = runCatching { lyricsJson.decodeFromString<SearchLyricsResponse>(body) }.getOrNull()
        return response?.candidates
    }

    private fun download(id: String, accessKey: String): String? {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .addQueryParameter("client", "pc")
            .addQueryParameter("ver", "1")
            .addQueryParameter("id", id)
            .addQueryParameter("accesskey", accessKey)
            .build()
        val body = lyricsGet(url.toString()) ?: return null
        val response = runCatching { lyricsJson.decodeFromString<DownloadResponse>(body) }.getOrNull()
            ?: return null
        val decoded = runCatching {
            Base64.getDecoder().decode(response.content).toString(Charsets.UTF_8)
        }.getOrNull() ?: return null
        return decoded.stripCredits().takeUnless { it.isInstrumentalNotice() }
    }

    private fun keyword(title: String, artist: String, album: String?) = Keyword(
        buildString {
            append(title.stripParenthetical())
            append(" - ")
            append(artist.stripParenthetical())
            if (!album.isNullOrBlank()) {
                append(' ')
                append(album)
            }
        },
    )

    private fun String.stripParenthetical(): String =
        replace(Regex("""[(（].*?[)）]"""), "").trim().ifBlank { this }

    /**
     * KuGou's lyric files open and close with uncredited lines — songwriter,
     * composer, arranger — that carry a real timestamp and would otherwise be
     * sung as the first and last lines of the song. Cut the same way the
     * source client does: from either end, up to the first/last line matching
     * "label: value", and only within the first and last 30 lines so a legit
     * lyric that happens to contain a colon deep in the song is left alone.
     */
    fun String.stripCredits(): String {
        val lines = lineSequence().filter { STAMPED.matches(it) }.toList()
        if (lines.isEmpty()) return ""
        val headLimit = min(30, lines.lastIndex)
        val headCut = (headLimit downTo 0).firstOrNull { CREDIT.matches(lines[it]) }?.let { it + 1 } ?: 0
        val body = lines.drop(headCut)
        val tailLimit = min(30, body.lastIndex)
        val tailCut = (0..tailLimit).firstOrNull { CREDIT.matches(body[body.lastIndex - it]) }?.let { it + 1 } ?: 0
        return body.dropLast(tailCut).joinToString("\n")
    }

    /**
     * For a song it has no words for, KuGou still hands back a "lyric" — a
     * single stamped "纯音乐，请欣赏" ("pure music, please enjoy") or a longer
     * variant of it. Treat a file made of nothing but that notice as no lyrics
     * at all, so the next source gets a turn instead of the notice being sung.
     */
    fun String.isInstrumentalNotice(): Boolean {
        val lines = lineSequence().map { it.replace(STAMP, "").trim() }.filter { it.isNotEmpty() }.toList()
        return lines.isNotEmpty() && lines.all { INSTRUMENTAL.containsMatchIn(it) }
    }

    private val STAMP = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")
    private val INSTRUMENTAL = Regex("""纯音乐.*欣赏""")
    private val STAMPED = Regex("""\[\d{2}:\d{2}\.\d{2,3}].*""")
    private val CREDIT = Regex(""".+][^\[]+[:：].+""")

    private class Keyword(val query: String)

    @Serializable
    private data class SearchSongResponse(val data: Data? = null) {
        @Serializable
        data class Data(val info: List<Info> = emptyList())

        @Serializable
        data class Info(
            val hash: String,
            val duration: Int = -1,
            val songname: String = "",
            val filename: String = "",
        )
    }

    @Serializable
    private data class SearchLyricsResponse(val candidates: List<Candidate> = emptyList())

    @Serializable
    private data class Candidate(
        val id: String,
        val accesskey: String,
        /** The song this candidate was filed under. */
        val song: String = "",
        val singer: String = "",
    )

    @Serializable
    private data class DownloadResponse(val content: String = "")
}
