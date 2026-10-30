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
 * A cover stays in the list because it is music, but reads as
 * `Song (cover)` with the singer (or the uploader when the title names none)
 * as its artist, so a fan upload cannot pass for the original recording.
 */
internal object YouTubeDisplayCleaner {

    data class Cleaned(
        val title: String,
        val artist: String,
        /** The work a soundtrack upload named, as an album subtitle ("Joker OST"). */
        val album: String? = null,
    )

    /**
     * A YouTube feed row as it should read: an ATV catalogue track already
     * names its song and artist, while an OMV/UGC upload is packaging that
     * [clean] has to rewrite.
     */
    fun cleanUpload(song: Song, knownArtists: Set<String> = emptySet()): Song {
        if (song.albumId != null) return song
        val credit = song.artist.trim()
        return clean(song, if (credit.isEmpty()) knownArtists else knownArtists + credit)
    }

    /** A song with its display metadata cleaned; every other field is preserved. */
    fun clean(song: Song, knownArtists: Set<String> = emptySet()): Song {
        // A cover is music and stays in the list, but its upload title is
        // packaging: "아이묭 - Marigold / cover by 오화요" reads as the song
        // and the singer, not as a video title. When the credit can be read
        // the singer is the artist; otherwise the uploader is all we know.
        CoverTitles.read(song)?.let { cover -> return cleanCover(song, cover) }
        if (isCover(song.title)) return cleanCoverTitle(song)
        val cleaned = clean(song.title, song.artist, knownArtists)
        val album = cleaned.album?.takeIf { it.isNotBlank() } ?: song.albumName
        if (cleaned.title == song.title && cleaned.artist == song.artist && album == song.albumName) {
            return song
        }
        return song.copy(title = cleaned.title, artist = cleaned.artist, albumName = album)
    }

    /**
     * A soundtrack row as it should read: the title and performer the upload
     * printed, with the work it named kept as an album subtitle. A credit the
     * title did not state falls back to a real channel; a channel that is only
     * "Original Soundtrack" (or any other score label) is not a performer and
     * is left blank rather than shown as the artist.
     */
    private fun cleanSoundtrack(
        identity: TrackMatcher.AudioSwitchIdentity,
        channel: String,
        fallbackTitle: String,
    ): Cleaned {
        val performer = identity.artist.trim()
        val channelArtist = cleanChannel(channel)
        val artist = when {
            performer.isNotBlank() && !TrackMatcher.isSoundtrackCredit(performer) ->
                collapseAliases(performer)
            channelArtist.isNotBlank() && !TrackMatcher.isSoundtrackCredit(channelArtist) ->
                channelArtist
            else -> ""
        }
        val work = identity.work?.trim()
            ?.takeIf { it.isNotBlank() && !TrackMatcher.isSoundtrackCredit(it) }
        return Cleaned(
            title = identity.title.trim().ifBlank { fallbackTitle },
            artist = artist,
            album = work?.let { "$it OST" },
        )
    }

    private fun cleanCover(song: Song, cover: CoverTitles.Cover): Song {
        val title = cover.title.trim().ifBlank { song.title.trim() }
        val performer = cover.singer.trim()
            .ifBlank { cleanChannel(song.artist).ifBlank { song.artist.trim() } }
            .takeUnless { TrackMatcher.isSoundtrackCredit(it) }
            .orEmpty()
        return song.copy(
            title = if (title.endsWith(COVER_SUFFIX, ignoreCase = true)) title else "$title$COVER_SUFFIX",
            artist = performer,
            originalTitle = song.originalTitle ?: song.title,
            originalArtist = song.originalArtist ?: song.artist,
            coverSinger = song.coverSinger ?: performer.takeIf { it.isNotBlank() },
            coverOriginalArtist = song.coverOriginalArtist ?: cover.originalArtist,
        )
    }

    /**
     * A title the cover reader could not parse but whose own words still say
     * "cover". Drop the tag from the song's name, keep the uploader as the
     * credit, and mark the row so it never reads as the original.
     */
    private fun cleanCoverTitle(song: Song): Song {
        val alreadyMarked = song.title.trim().endsWith(COVER_SUFFIX, ignoreCase = true)
        val title = song.title.trim()
            .replace(COVER_MARKER, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .ifBlank { song.title.trim() }
        val performer = cleanChannel(song.artist).ifBlank { song.artist.trim() }
            .takeUnless { TrackMatcher.isSoundtrackCredit(it) }
            .orEmpty()
        return song.copy(
            title = if (title.endsWith(COVER_SUFFIX, ignoreCase = true)) title else "$title$COVER_SUFFIX",
            artist = performer,
            // A second pass sees the already-cleaned title; it must not replace
            // the upload's own title stored on the first pass.
            originalTitle = song.originalTitle ?: song.title.takeUnless { alreadyMarked },
            originalArtist = song.originalArtist ?: song.artist.takeUnless { alreadyMarked },
            coverSinger = song.coverSinger ?: performer.takeIf { it.isNotBlank() },
        )
    }

    fun clean(
        title: String,
        channel: String,
        knownArtists: Set<String> = emptySet(),
    ): Cleaned {
        val rawTitle = title.trim()
        val rawChannel = channel.trim()
        val labelChannel = isAgencyChannel(rawChannel)
        val channelArtist = cleanChannel(rawChannel)
        if (rawTitle.isBlank()) return Cleaned(rawTitle, channelArtist)
        var text = rawTitle
        while (true) {
            val next = VIDEO_TAG.replace(text, " ")
                .replace(TRAILING_VIDEO_TAG, " ")
                .replace(WHITESPACE, " ")
                .trim()
                .let { if (LEADING_OST.containsMatchIn(it)) LEADING_OST.replace(it, " ").trim() else it }
                .replace(WHITESPACE, " ")
                .trim()
            if (next == text) break
            text = next
        }
        // A fan upload of a soundtrack prints the work and its "OST" label
        // after the song. Read it with the same parser the version lookup
        // uses, so a playlist row and the audio switch agree on the song and
        // the performer instead of showing the uploader as the artist.
        val soundtrack = TrackMatcher.soundtrackIdentity(text, rawChannel)
        // A bare "Soundtrack"/"OST" word at the end may be part of a real song
        // name ("Our Soundtrack", "My OST"). Only a named work or a
        // dash/pipe-attached release label is packaging.
        if (soundtrack.hasSoundtrackTail && (soundtrack.work != null || soundtrack.labelPackaged)) {
            return cleanSoundtrack(soundtrack, rawChannel, rawTitle)
        }
        // A label upload often puts the credit in the title and its own name on
        // the channel: `Artist 한글명 'Song' MV`. The quoted song names where the
        // artist ends, which no dash split can see.
        if (labelChannel) {
            splitQuotedSong(text)?.let { (artist, song) ->
                val name = collapseAliases(artist)
                if (name.isNotBlank() && song.isNotBlank()) return Cleaned(song, name)
            }
        }
        val split = CoverTitles.splitTopLevelDash(text)
        if (split != null && shouldSplit(split.first, rawChannel, knownArtists)) {
            val artist = collapseAliases(split.first).ifBlank { channelArtist }
            val songTitle = split.second.trim().trim(' ', '-', '_', '|', '·', '/')
            if (artist.isNotBlank() &&
                !TrackMatcher.isSoundtrackCredit(artist) &&
                songTitle.isNotBlank()
            ) {
                return Cleaned(songTitle, artist)
            }
        }
        val fallbackArtist = channelArtist.ifBlank { if (labelChannel) "" else rawChannel }
        return Cleaned(
            text.ifBlank { rawTitle },
            // A label channel is not the artist; if the title carried no credit
            // there is nothing honest to show in its place. A soundtrack label
            // is a release name, never a performer.
            if (TrackMatcher.isSoundtrackCredit(fallbackArtist)) "" else fallbackArtist,
        )
    }

    /** `['\"“”‘’]Song['\"“”‘’]` at the end, with the credit before it. */
    private fun splitQuotedSong(text: String): Pair<String, String>? {
        val match = QUOTED_TAIL.find(text) ?: return null
        val artist = text.substring(0, match.range.first)
            .trim()
            .trimEnd('-', '–', '—', '_', ':', '|')
            .trim()
        val song = match.groupValues[1].trim()
        if (artist.isBlank() || song.isBlank()) return null
        return artist to song
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
        if (isAgencyChannel(channel)) return true
        return LABEL_MARKERS.any { text.contains(it) }
    }

    /**
     * A channel that publishes other people's songs. `Artist Official` and
     * `Artist - Topic` are artist channels, so they are not part of this.
     */
    private fun isAgencyChannel(channel: String): Boolean {
        val text = TrackMatcher.normalize(channel).trim()
        if (text.isEmpty()) return false
        if (text.endsWith("topic") || text.contains("vevo")) return false
        if (text in LABEL_CHANNELS) return true
        if (SHORT_LABEL.containsMatchIn(text)) return true
        if (AGENCY_MARKERS.any { text.contains(it) }) return true
        return GENERIC_LABEL.containsMatchIn(text)
    }

    /** `Artist - Topic` and `ArtistVEVO` are channel plumbing, not the credit. */
    private fun cleanChannel(channel: String): String {
        var name = channel.trim()
        val topic = TOPIC_SUFFIX.containsMatchIn(name)
        val vevo = VEVO_SUFFIX.containsMatchIn(name)
        name = name.replace(TOPIC_SUFFIX, "")
        name = name.replace(VEVO_SUFFIX, "")
        // A `- Topic` or VEVO channel is named after the artist; only a real
        // label/agency name is dropped. A soundtrack release label is not a
        // performer either.
        if (!topic && !vevo && (isAgencyChannel(name) || TrackMatcher.isSoundtrackCredit(name))) {
            return ""
        }
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
        // K-pop credits are often written `EnglishName 한글명`; the bracketed
        // form is handled above, and the unbracketed one reads as the name
        // before the Hangul alias.
        val words = text.split(WHITESPACE)
        if (words.size >= 2) {
            val tail = words.last()
            val head = words.dropLast(1).joinToString(" ")
            if (HANGUL.containsMatchIn(tail) &&
                !HANGUL.containsMatchIn(head) &&
                LATIN.containsMatchIn(head)
            ) {
                text = head
            }
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

    /** Whether the title itself says "cover"; the credit is read separately. */
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

    /** An un-bracketed packaging tag at the very end of an upload title. */
    private val TRAILING_VIDEO_TAG = Regex(
        """(?i)(?:^|\s)(?:official\s+(?:(?:music|lyric|performance|special)\s+)?""" +
            """(?:video|mv|m\s*/\s*v|audio)|performance\s+video|lyric\s+video|""" +
            """special\s+video|visuali[sz]er|music\s+video|m\s*/\s*v|mv|뮤직비디오)\s*[.!]?\s*$""",
    )

    /** `Artist 'Song'`, the quoted song closing a label upload's title. */
    private val QUOTED_TAIL = Regex(
        """(?:^|\s)['"“”‘’]{1,2}([^'"“”‘’]{1,120}?)['"“”‘’]{1,2}\s*$""",
    )

    private val HANGUL = Regex("""[가-힣]""")
    private val LATIN = Regex("""[A-Za-z]""")
    private val TOPIC_SUFFIX = Regex("""(?i)\s*-\s*topic\s*$""")
    private val VEVO_SUFFIX = Regex("""(?i)\s*vevo\s*$""")

    /** A leading `[선재 업고 튀어 OST Part 3]` describes the release, not the song. */
    private val LEADING_OST = Regex(
        """^\s*[\[(（【][^)\]）】]*\bost\b[^)\]）】]*[)\]）】]\s*""",
        RegexOption.IGNORE_CASE,
    )

    private const val COVER_SUFFIX = " (cover)"

    /** "cover by", a bare cover tag, and the Korean word. */
    private val COVER_MARKER = Regex(
        """(?i)(?<![a-z])cover(?:ed)?\s*by|커버|(?<![a-z])cover(?![a-z])""",
    )

    private val LABEL_MARKERS = listOf(
        "1thek", "stone music", "smtown", "sm town", "starship",
        "hybe labels", "jyp", "yg entertainment", "kakao", "genie",
        "mnet", "kq entertainment", "cj enm", "official",
    )

    /** [LABEL_MARKERS] that name an agency rather than an artist's own channel. */
    private val AGENCY_MARKERS = LABEL_MARKERS - "official"

    /** Agency channels whose name is never the artist of the song they upload. */
    private val LABEL_CHANNELS = setOf(
        "smtown", "sm town", "hybe labels", "1thek", "1thek (원더케이)",
        "jyp entertainment", "stone music entertainment", "genie music",
        "mnet k-pop", "m2", "kbs kpop", "studio choom", "theblacklabel",
        "yg entertainment", "starshiptv", "kakao entertainment",
        "kakao m", "big hit music", "belift lab", "ador", "pledis entertainment",
        "koz entertainment", "edam entertainment", "aomg", "h1ghr music",
        "wakeone", "swing entertainment", "cube entertainment", "fnc entertainment",
        "woollim entertainment", "dsp media", "rbw", "antenna", "mystic story",
        "konnect entertainment", "yuehua entertainment", "abyss company",
        "jellyfish entertainment", "dreamus", "bugs music", "music and new",
        "warner music korea", "universal music", "sony music korea",
        "mwave", "kcon", "dingo music", "lululala music",
    )

    /** `… Entertainment`, `… Labels`, `… Records` and official music channels. */
    private val GENERIC_LABEL = Regex(
        """(?i)\b(?:entertainment|records?|labels?)\b\s*$|""" +
            """\bmusic\b\s*(?:official)?$|""" +
            """\bofficial\s+music\b\s*$|""" +
            """\bmusic\s+official\b""",
    )

    /** `YG`, `KQ` and `JYP` alone, without matching the letters inside a name. */
    private val SHORT_LABEL = Regex("""(?<![a-z])(?:yg|kq|jyp)(?![a-z])""")
}
