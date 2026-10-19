package com.music.bitchord.data

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher

/**
 * Turns a youtube.com upload's title and channel into the song and artist a
 * listener expects to read.
 *
 * youtube.com rows are uploads, not catalogue tracks: a label prints
 * `[MV] IU(아이유) _ Friday(금요일에 만나요) (Feat. Jang Yi-jeong)` and names the
 * uploader `1theK (원더케이)`. The video id stays exactly as it is — only the
 * display strings are rewritten. A catalogue match (iTunes/Deezer via
 * [com.music.bitchord.data.cover.YouTubeCoverResolver]) is preferred when it
 * exists; this cleaner is the fallback for the rows it cannot confirm.
 *
 * Cover uploads are left untouched: their title carries who sings the cover
 * and their channel is the singer, so splitting them would lose a credit.
 */
internal object YouTubeDisplayCleaner {

    data class Cleaned(val title: String, val artist: String)

    /** A song with its display metadata cleaned; every other field is preserved. */
    fun clean(song: Song, knownArtists: Set<String> = emptySet()): Song {
        if (isCover(song.title)) return song
        val cleaned = clean(song.title, song.artist, knownArtists)
        if (cleaned.title == song.title && cleaned.artist == song.artist) return song
        return song.copy(title = cleaned.title, artist = cleaned.artist)
    }

    fun clean(
        title: String,
        channel: String,
        knownArtists: Set<String> = emptySet(),
    ): Cleaned {
        val rawTitle = title.trim()
        val rawChannel = channel.trim()
        val channelArtist = cleanChannel(rawChannel)
        if (rawTitle.isBlank()) return Cleaned(rawTitle, channelArtist)
        var text = rawTitle
        while (true) {
            val next = VIDEO_TAG.replace(text, " ")
                .replace(WHITESPACE, " ")
                .trim()
                .let { if (LEADING_OST.containsMatchIn(it)) LEADING_OST.replace(it, " ").trim() else it }
                .replace(WHITESPACE, " ")
                .trim()
            if (next == text) break
            text = next
        }
        val split = CoverTitles.splitTopLevelDash(text)
        if (split != null && shouldSplit(split.first, rawChannel, knownArtists)) {
            val artist = collapseAliases(split.first).ifBlank { channelArtist }
            val songTitle = split.second.trim().trim(' ', '-', '_', '|', '·', '/')
            if (artist.isNotBlank() && songTitle.isNotBlank()) {
                return Cleaned(songTitle, artist)
            }
        }
        return Cleaned(text.ifBlank { rawTitle }, channelArtist.ifBlank { rawChannel })
    }

    private fun shouldSplit(left: String, channel: String, knownArtists: Set<String>): Boolean {
        val name = collapseAliases(left)
        if (name.isBlank()) return false
        if (isLabelChannel(channel)) return true
        if (knownArtists.isEmpty()) return false
        val key = compact(name)
        return knownArtists.any { known ->
            TrackMatcher.sharesArtist(name, known) || (key.isNotEmpty() && key == compact(known))
        }
    }

    /**
     * Album and label channels put out other people's songs, so `Artist - Title`
     * is their normal shape. A personal channel only gets that reading when the
     * left side is an artist already known from the listener's history.
     */
    private fun isLabelChannel(channel: String): Boolean {
        val text = TrackMatcher.normalize(channel).trim()
        if (text.isEmpty()) return false
        if (text.endsWith("topic")) return true
        if (text.contains("vevo")) return true
        if (SHORT_LABEL.containsMatchIn(text)) return true
        return LABEL_MARKERS.any { text.contains(it) }
    }

    /** `Artist - Topic` and `ArtistVEVO` are channel plumbing, not the credit. */
    private fun cleanChannel(channel: String): String {
        var name = channel.trim()
        name = name.replace(Regex("""(?i)\s*-\s*topic\s*$"""), "")
        name = name.replace(Regex("""(?i)\s*vevo\s*$"""), "")
        return collapseAliases(name)
    }

    /**
     * `IU(아이유)` and `민니 ((여자)아이들)` read as the plain name. Only a
     * trailing bracketed group is dropped, so a leading `(여자)아이들` stays.
     */
    private fun collapseAliases(name: String): String {
        var text = name.trim()
        while (true) {
            val at = trailingGroupStart(text) ?: break
            text = text.substring(0, at).trim()
        }
        return text.trim()
    }

    private fun trailingGroupStart(text: String): Int? {
        if (text.isEmpty()) return null
        val closes = ")]）】}"
        if (text.last() !in closes) return null
        var depth = 0
        for (index in text.indices.reversed()) {
            when (text[index]) {
                ')', ']', '）', '】', '}' -> depth++
                '(', '[', '（', '【', '{' -> {
                    depth--
                    if (depth == 0) {
                        return index.takeIf { text.substring(0, it).isNotBlank() }
                    }
                }
            }
        }
        return null
    }

    private fun compact(text: String): String =
        TrackMatcher.normalize(text).filter { it.isLetterOrDigit() }

    /** A cover's own singer must keep its title and channel; see [CoverTitles]. */
    private fun isCover(title: String): Boolean = COVER_MARKER.containsMatchIn(title)

    private const val WHITESPACE_TEXT = """\s+"""
    private val WHITESPACE = Regex(WHITESPACE_TEXT)

    /** `[MV]`, `[M/V]`, `(Official Video)`, `(Lyric Video)`, `(Audio)`, … */
    private val VIDEO_TAG = Regex(
        """(?i)[\[(（【]\s*(?:mv|m\s*/\s*v|m/v|""" +
            """official(?:\s*(?:music\s*)?(?:video|mv|m/v|audio|visuali[sz]er))?|""" +
            """official\s*lyric\s*video|lyric\s*video|lyrics?|audio|visuali[sz]er|""" +
            """music\s*video)\s*[)\]）】]""",
    )

    /** A leading `[선재 업고 튀어 OST Part 3]` describes the release, not the song. */
    private val LEADING_OST = Regex(
        """^\s*[\[(（【][^)\]）】]*\bost\b[^)\]）】]*[)\]）】]\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** "cover by", a bare cover tag, and the Korean word: never rewritten. */
    private val COVER_MARKER = Regex(
        """(?i)(?<![a-z])cover(?:ed)?\s*by|커버|(?<![a-z])cover(?![a-z])""",
    )

    private val LABEL_MARKERS = listOf(
        "1thek", "stone music", "smtown", "sm town", "starship",
        "hybe labels", "jyp", "yg entertainment", "kakao", "genie",
        "mnet", "kq entertainment", "cj enm", "official",
    )

    /** `YG`, `KQ` and `JYP` alone, without matching the letters inside a name. */
    private val SHORT_LABEL = Regex("""(?<![a-z])(?:yg|kq|jyp)(?![a-z])""")
}
