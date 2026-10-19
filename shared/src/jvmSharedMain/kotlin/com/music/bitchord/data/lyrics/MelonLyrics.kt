package com.music.bitchord.data.lyrics

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/*
 * Line-synced lyrics from Melon, the third Korean service the fork supports.
 *
 * Melon answers its own web player's search and song pages without a signed
 * request, so this reads what a browser would see and nothing else. Search hits
 * are held to both the title and the artist before their lyrics are taken, the
 * same rule the VIBE and Bugs readers use: the wrong song's words are worse
 * than no lyrics at all.
 */
object MelonLyrics {

    private const val REFERER = "https://www.melon.com/"
    private const val SEARCH = "https://www.melon.com/search/song/index.htm?q="
    private const val DETAIL = "https://www.melon.com/song/detail.htm?songId="
    private const val LYRICS = "https://www.melon.com/song/lyrics.htm?songId="

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val query = URLEncoder.encode("$title $artist", "UTF-8")
            val page = lyricsGetAsBrowser("$SEARCH$query", REFERER, "text/html")
                ?: return@withContext null
            val hits = parseHits(page).take(MAX_CANDIDATES)
            if (hits.isEmpty()) return@withContext null
            val matched = coroutineScope {
                hits.map { hit ->
                    async {
                        val body = lyricsGetAsBrowser("$DETAIL${hit.id}", REFERER, "text/html")
                            ?: return@async hit.copy(seconds = null)
                        val seconds = hit.seconds ?: durationOf(body)
                        hit.copy(seconds = seconds)
                    }
                }.awaitAll()
            }
            val best = KoreanMatch.best(matched, title, artist, durationMs)
                ?: return@withContext null
            lyricsFor(DETAIL + best.id) ?: lyricsFor(LYRICS + best.id)
        }

    /** The search rows, with the title and artist Melon printed beside each id. */
    internal fun parseHits(page: String): List<KoreanMatch.Hit> =
        ROW.findAll(page).mapNotNull { row ->
            val block = row.groupValues[1]
            val id = SONG_ID.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val titleAnchor = SONG_ANCHOR.find(block) ?: return@mapNotNull null
            val title = attr(titleAnchor.value, "title")
                ?: text(titleAnchor.groupValues[1])
            val artist = ARTIST_ANCHOR.find(block)?.let { anchor ->
                attr(anchor.value, "title") ?: text(anchor.groupValues[1])
            }.orEmpty()
            if (title.isBlank()) return@mapNotNull null
            KoreanMatch.Hit(id, title, artist, DURATION.find(block)?.value?.let(KoreanMatch::seconds))
        }.distinctBy { it.id }.toList()

    /** The lyric block on a Melon song page, or null when it carries none. */
    internal fun parseLyrics(page: String): String? {
        val block = LYRIC_BLOCK.find(page)?.groupValues?.get(1) ?: return null
        val text = block
            .replace(BREAK, "\n")
            .replace(TAG, "")
            .let(::decodeEntities)
            .replace("\r\n", "\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
        if (text.isBlank() || text.contains(NO_LYRICS)) return null
        return text
    }

    private suspend fun lyricsFor(url: String): List<LyricLine>? =
        lyricsGetAsBrowser(url, REFERER, "text/html")
            ?.let(::parseLyrics)
            ?.let(KoreanMatch::plain)

    private fun durationOf(page: String): Int? =
        DURATION.findAll(page).mapNotNull { KoreanMatch.seconds(it.value) }.firstOrNull()

    private fun attr(tag: String, name: String): String? =
        Regex("\\b" + Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }

    private fun text(html: String): String =
        decodeEntities(html.replace(TAG, " ")).replace(WHITESPACE, " ").trim()

    private fun decodeEntities(raw: String): String {
        var text = raw
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        text = NUMERIC_ENTITY.replace(text) { match ->
            val value = if (match.groupValues[1].startsWith("x", ignoreCase = true)) {
                match.groupValues[1].drop(1).toIntOrNull(16)
            } else {
                match.groupValues[1].toIntOrNull()
            }
            value?.let { String(Character.toChars(it)) } ?: match.value
        }
        return text
    }

    private const val MAX_CANDIDATES = 3
    private const val NO_LYRICS = "가사가 없습니다"

    private val ROW = Regex("""<tr\b[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val SONG_ID = Regex("""goSongDetail\('(\d+)'\)""")
    private val SONG_ANCHOR = Regex(
        """<a\b[^>]*goSongDetail\('\d+'\)[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val ARTIST_ANCHOR = Regex(
        """<a\b[^>]*goArtistDetail\('[^']*'\)[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val DURATION = Regex("""\b\d{1,3}:\d{2}\b""")
    private val LYRIC_BLOCK = Regex(
        """<div\b[^>]*\bid="divLyric"[^>]*>(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val WHITESPACE = Regex("""\s+""")
    private val NUMERIC_ENTITY = Regex("""&#(x?[0-9a-fA-F]+);""")
}
