package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * QQ Music's own karaoke timing — the word sheet BetterLyrics Portato resells,
 * fetched from QQ directly with no login and no key.
 *
 * Two requests: the search names the recording and its `songid`, then
 * `lyric_download.fcg` answers an XML whose first hex CDATA block is the
 * encrypted QRC. [QrcCipher] decrypts it and [KaraokeLrc] reads the rows. The
 * search hit is held to [RecordingMatch] like every other catalogue row, so a
 * namesake or a different edit is refused rather than timed.
 */
object QqMusic {

    private const val SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val LYRIC = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"
    private const val MAX_SEARCH_BODY = 1_000_000
    private const val MAX_LYRIC_BODY = 1_000_000

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        get: suspend (String) -> String? = { lyricsGet(it) },
        post: suspend (String, String) -> String? = { url, form -> lyricsPostForm(url, form) },
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val url = SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("w", "$title $artist".trim())
            .addQueryParameter("n", "20")
            .addQueryParameter("p", "1")
            .addQueryParameter("cr", "1")
            .addQueryParameter("g_tk", "5381")
            .addQueryParameter("t", "0")
            .build()
        val body = get(url.toString()) ?: return@withContext null
        if (body.length > MAX_SEARCH_BODY) return@withContext null
        val hits = runCatching { lyricsJson.decodeFromString<SearchResponse>(body) }.getOrNull()
            ?.data?.song?.list ?: return@withContext null
        val hit = best(hits, title, artist, durationMs) ?: return@withContext null
        val songId = hit.songId ?: return@withContext null
        val xml = post(LYRIC, "version=15&miniversion=82&lrctype=4&musicid=$songId")
            ?: return@withContext null
        if (xml.length > MAX_LYRIC_BODY) return@withContext null
        val hex = cdataHex(xml) ?: return@withContext null
        val document = QrcCipher.decryptHex(hex) ?: return@withContext null
        runCatching { KaraokeLrc.parse(document) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** The hit that is this recording, or null; see [RecordingMatch]. */
    internal fun best(hits: List<Hit>, title: String, artist: String, durationMs: Long): Hit? =
        RecordingMatch.best(candidates(hits), title, artist, durationMs)

    private fun candidates(hits: List<Hit>): List<RecordingMatch.Candidate<Hit>> =
        hits.map { hit ->
            RecordingMatch.Candidate(
                value = hit,
                title = hit.songName,
                artist = hit.singers.joinToString(", ") { it.name },
                seconds = hit.interval?.toDouble(),
                usable = hit.songId != null,
            )
        }

    /**
     * The hex sheet inside the `<content>` element. `<contentts>` is a plain
     * LRC translation and `<contentroma>` a romanisation; neither is the
     * original words, so neither is read. A track with no QRC answers `<content>`
     * with LRC, which is not hex and is refused.
     */
    internal fun cdataHex(xml: String): String? {
        val block = CONTENT_ELEMENT.find(xml)?.groupValues?.get(1) ?: return null
        val hex = (CDATA.find(block)?.groupValues?.get(1) ?: block).trim()
        return hex.takeIf { it.length >= 16 && it.length % 2 == 0 && it.all { char -> char.isHexDigit() } }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private val CDATA = Regex("""<!\[CDATA\[([^\]]*)]]>""")
    private val CONTENT_ELEMENT = Regex("""<content\b[^>]*>(.*?)</content>""", RegexOption.DOT_MATCHES_ALL)

    @Serializable
    internal data class SearchResponse(val data: SearchBody? = null)

    @Serializable
    internal data class SearchBody(val song: SongList? = null)

    @Serializable
    internal data class SongList(val list: List<Hit> = emptyList())

    @Serializable
    internal class Hit(
        @SerialName("songid") val songId: Long? = null,
        @SerialName("songname") val songName: String? = null,
        @SerialName("singer") val singers: List<Singer> = emptyList(),
        val interval: Int? = null,
    )

    @Serializable
    internal class Singer(val name: String = "")
}