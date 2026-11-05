package com.music.bitchord.data

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.withVideoThumbnailFallback
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
 * A cover stays in the list because it is music, but reads as the song it
 * covers and `원곡자 · 가수 커버` as its byline, so a fan upload cannot pass
 * for the original recording.
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
        // A catalogue match can land on a cover video too — its album id is the
        // original's. The cover still reads as the uploader's performance.
        if (song.albumId != null && !CoverTitles.isCover(song)) return song
        val credit = song.artist.trim()
        return clean(song, if (credit.isEmpty()) knownArtists else knownArtists + credit)
    }

    /** A song with its display metadata cleaned; every other field is preserved. */
    fun clean(song: Song, knownArtists: Set<String> = emptySet()): Song {
        // A byline the cleaner grew one word at a time ("오화요 커버 커버 커버
        // 커버", phone 11-04) reads as one word again. The growth itself is
        // fixed where the word is appended; this mends rows already saved.
        val fixed = song.copy(artist = singleCoverWord(song.artist))
        // A cover is music and stays in the list, but its upload title is
        // packaging: "아이묭 - Marigold / cover by 오화요" reads as the song
        // and the singer, not as a video title. When the credit can be read
        // the singer is the artist; otherwise the uploader is all we know.
        CoverTitles.read(fixed)?.let { cover -> return cleanCover(fixed, cover) }
        if (isCover(fixed.title)) return cleanCoverTitle(fixed)
        val cleaned = clean(fixed.title, fixed.artist, knownArtists)
        val album = cleaned.album?.takeIf { it.isNotBlank() } ?: fixed.albumName
        if (cleaned.title == fixed.title && cleaned.artist == fixed.artist && album == fixed.albumName) {
            return fixed
        }
        return fixed.copy(title = cleaned.title, artist = cleaned.artist, albumName = album)
    }

    /** `오화요 커버 커버` → `오화요 커버`: the word is a tag, never a repeat. */
    private fun singleCoverWord(name: String): String {
        var text = name.trim()
        var tagged = false
        while (true) {
            val next = COVER_WORD_TAIL.replace(text, "").trim()
            if (next == text || next.isEmpty()) break
            text = next
            tagged = true
        }
        return if (tagged) "$text 커버" else name
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
        val title = cover.title.trim()
            .replace(EMPTY_BRACKETS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .ifBlank { song.title.trim() }
        val uploader = cleanChannel(song.artist).ifBlank { song.artist.trim() }
        // The original's artist is never the cover's artist: when the title
        // named it as the singer, the uploader is who actually sings.
        val singer = CoverTitles.stripUploadSuffix(
            cover.singer.trim().ifBlank { uploader },
        )
            .takeUnless { CoverTitles.sameName(it, cover.originalArtist) }
            ?.ifBlank { uploader }
            ?.takeUnless { TrackMatcher.isSoundtrackCredit(it) }
            .orEmpty()
        val original = cover.originalArtist?.trim()
            ?.let { CoverTitles.stripUploadSuffix(it) }
            ?.takeUnless {
                it.isBlank() || CoverTitles.sameName(it, singer) || TrackMatcher.isSoundtrackCredit(it)
            }
        val byline = listOfNotNull(original, singer.takeIf { it.isNotBlank() }?.let { "$it 커버" })
            .joinToString(" · ")
        return song.copy(
            title = title,
            artist = byline.ifBlank { song.artist.trim() },
            originalTitle = song.originalTitle ?: song.title,
            originalArtist = song.originalArtist ?: song.artist,
            coverSinger = song.coverSinger ?: singer.takeIf { it.isNotBlank() },
            coverOriginalArtist = song.coverOriginalArtist ?: original,
        )
    }

    /**
     * A title the cover reader could not parse but whose own words still say
     * "cover". Drop the tag from the song's name, keep the uploader as the
     * credit, and mark the row so it never reads as the original.
     */
    private fun cleanCoverTitle(song: Song): Song {
        val alreadyMarked = song.title.trim().endsWith(COVER_SUFFIX, ignoreCase = true)
        // Taking "cover" out of "Song (cover)" left "Song ( )", and every pass
        // added another pair (phone 11-03: "화조도 花鳥圖 ( ) ( ) ( ) (cover)",
        // which no lyrics search could find). Empty brackets go too, which
        // also mends titles saved by that bug.
        val title = song.title.trim()
            .replace(COVER_MARKER, " ")
            .replace(EMPTY_BRACKETS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .ifBlank { song.title.trim() }
        val performer = CoverTitles.stripUploadSuffix(
            cleanChannel(song.artist).ifBlank { song.artist.trim() },
        ).takeUnless { TrackMatcher.isSoundtrackCredit(it) }.orEmpty()
        return song.copy(
            title = title,
            artist = performer.takeIf { it.isNotBlank() }?.let { "$it 커버" }.orEmpty(),
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
        // An upload title sometimes repeats its own channel after a dash
        // ("ILLIT (아일릿) 'X' - HYPE UNIVERSE", phone 11-06). The channel is
        // never part of the song's name, so that tail goes.
        text = withoutChannelTail(text, rawChannel)
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
        // artist ends, which no dash split can see. A publisher channel that
        // [LABEL_CHANNELS] does not list ("HYPE UNIVERSE", phone 11-06) prints
        // the same shape, so a title whose own credit reads as a name is
        // believed there too, and the channel is dropped.
        splitQuotedSong(text)?.let { (artist, song) ->
            val name = collapseAliases(artist)
            if (name.isNotBlank() &&
                song.isNotBlank() &&
                (labelChannel || isStatedCredit(name)) &&
                // A quoted packaging phrase ("Feat. X", "Remix") names no song.
                song.split(WHITESPACE).first().lowercase().trim('.') !in PACKAGING_CREDIT
            ) {
                if (compact(name) != compact(rawChannel)) {
                    DebugLog.d(TAG, "artist: channel dropped (publisher) -> $name")
                }
                return Cleaned(song, name)
            }
        }
        // A cover channel re-sings other people's songs, so a title that names
        // the artist itself — "아무것도 아니야 / RADWIMPS" on "너의 이름은。 커버"
        // (phone 11-06) — is credited to that artist, and the channel, which
        // says only that it covers, is dropped.
        splitCoverChannelCredit(text, rawChannel)?.let { (songTitle, artist) ->
            DebugLog.d(TAG, "artist: channel dropped (cover channel) -> $artist")
            return Cleaned(songTitle, artist)
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
        // The credit and the channel are one act in two scripts: "King Gnu -
        // 一途" uploaded by "킹 누" (phone 11-04). No name comparison sees
        // that, and the byline already names the act, so the title stops
        // repeating it. The channel stays the credit: nothing here proves the
        // two names are one act, only that the title repeats a name the row
        // already carries.
        // shortcut: a place name is told from an act only by [VENUE_WORDS],
        // so "Tokyo Dome - Song" keeps its whole title but a venue written
        // some other way ("Shinjuku Pit Inn - Song") still loses its prefix,
        // <widen when a transliteration table exists>.
        if (split != null && !labelChannel && dropsCrossScriptCredit(split.first, rawChannel)) {
            val songTitle = split.second.trim().trim(' ', '-', '_', '|', '·', '/')
            val byline = channelArtist.ifBlank { rawChannel }
            if (songTitle.isNotBlank() && !TrackMatcher.isSoundtrackCredit(byline)) {
                return Cleaned(songTitle, byline)
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

    /**
     * `Song - Uploader`, where the tail is the row's own channel name: the
     * channel is never part of the song's name.
     */
    private fun withoutChannelTail(title: String, channel: String): String {
        val tail = collapseAliases(channel)
        val cut = title.lastIndexOf(" - ")
        if (tail.isBlank() || cut <= 0) return title
        if (compact(title.substring(cut + 3)) != compact(tail)) return title
        return title.substring(0, cut).trim().ifBlank { title }
    }

    /**
     * `Song / Artist` on a cover channel: the title carries the artist, the
     * channel only says it covers. The channel is a cover channel when its own
     * name ends with the cover word ("너의 이름은。 커버", "오화요 커버"); an
     * artist's own channel ("오화요", "RADWIMPS - Topic") does not, and there
     * the channel stays the credit — an indie act's upload is its own song.
     */
    private fun splitCoverChannelCredit(text: String, channel: String): Pair<String, String>? {
        if (!COVER_WORD_TAIL.containsMatchIn(channel)) return null
        val (song, credit) = CoverTitles.splitTopLevelSlash(text) ?: return null
        val artist = collapseAliases(credit)
        if (artist.isBlank() || !isStatedCredit(artist)) return null
        if (CoverTitles.sameName(artist, channel)) return null
        val songTitle = song.trim().trim(' ', '-', '_', '|', '·', '/')
        if (songTitle.isBlank()) return null
        return songTitle to artist
    }

    /**
     * Whether the credit a title prints before its quoted song reads as an act
     * rather than packaging, so a publisher channel's row is split as well.
     */
    private fun isStatedCredit(name: String): Boolean {
        val words = name.split(WHITESPACE)
        if (words.isEmpty() || words.size > 4) return false
        if (words.any { it.lowercase().trim('.') in PACKAGING_CREDIT || isVenueWord(it) }) return false
        return !isAgencyChannel(name) && !TrackMatcher.isSoundtrackCredit(name)
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
     * A title credit that is the channel's own name written in another script,
     * so no name comparison can confirm it. Both sides short, one written in
     * Latin and the other not, and no packaging or venue word on the left —
     * "Live at Budokan - Song" and "Tokyo Dome - Song" are places, "King Gnu
     * - 一途" is a band.
     */
    private fun dropsCrossScriptCredit(left: String, channel: String): Boolean {
        val name = collapseAliases(left)
        val byline = cleanChannel(channel)
        if (name.isBlank() || byline.isBlank()) return false
        val nameWords = name.split(WHITESPACE)
        if (nameWords.size !in 2..4) return false
        if (byline.split(WHITESPACE).size > 4) return false
        if (nameWords.any { it.lowercase().trim('.') in PACKAGING_CREDIT || isVenueWord(it) }) return false
        return LATIN.containsMatchIn(name) != LATIN.containsMatchIn(byline)
    }

    /**
     * Whether a word of a left-hand credit names a venue. Hangul venue words
     * run together with what they qualify ("도쿄돔", "올림픽공원"), so there the
     * word only has to end with one.
     */
    private fun isVenueWord(word: String): Boolean {
        val text = word.lowercase().trim('.', ',', '\'', '"')
        return VENUE_WORDS.any { venue ->
            text == venue || (venue[0] in '가'..'힣' && text.endsWith(venue))
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

    /** A left-hand credit that names a place or an occasion rather than an act. */
    private val PACKAGING_CREDIT = setOf(
        "live", "concert", "tour", "official", "audio", "video", "mv",
        "remix", "version", "ver", "cover", "instrumental", "karaoke",
        "lyric", "lyrics", "edit", "full", "album", "teaser", "performance",
        "feat", "ft", "ep", "episode", "vol", "part", "preview", "trailer",
        "making", "behind",
    )

    /**
     * Where a concert was played, in the words titles print it with. A venue
     * is never an act, so a title that opens with one keeps the whole line.
     */
    private val VENUE_WORDS = setOf(
        "dome", "arena", "stadium", "hall", "theater", "theatre", "auditorium",
        "coliseum", "garden", "gardens", "forum", "amphitheater", "amphitheatre",
        "돔", "아레나", "체육관", "경기장", "공연장", "극장", "공원", "홀",
    )

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
        """(?:^|\s)['"“”‘’]{1,2}((?:[^'"“”‘’]|['’](?=\p{L})){1,120}?)['"“”‘’]{1,2}\s*$""",
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

    private const val TAG = "BitChord"

    /** "cover by", a bare cover tag, and the Korean word. */
    private val COVER_MARKER = Regex(
        """(?i)(?<![a-z])cover(?:ed)?\s*by|커버|(?<![a-z])cover(?![a-z])""",
    )

    /** A bracket pair with nothing but spaces inside, left by [COVER_MARKER]. */
    private val EMPTY_BRACKETS = Regex("""[(\[（【]\s*[)\]）】]""")

    /** The "커버"/"cover" tag a byline ends with, one repetition at a time. */
    private val COVER_WORD_TAIL = Regex("""(?i)\s*[(\[]?\s*(?:커버|(?<![a-z])cover(?:ed)?)\s*[)\]）】]?\s*$""")

    private val LABEL_MARKERS = listOf(
        "1thek", "stone music", "smtown", "sm town", "starship",
        "hybe labels", "jyp", "yg entertainment", "kakao", "genie",
        "mnet", "kq entertainment", "cj enm", "official",
    )

    /** [LABEL_MARKERS] that name an agency rather than an artist's own channel. */
    private val AGENCY_MARKERS = LABEL_MARKERS - "official"

    /** Agency channels whose name is never the artist of the song they upload. */
    private val LABEL_CHANNELS = setOf(
        "smtown", "sm town", "hybe labels", "hype universe", "1thek", "1thek (원더케이)",
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

/**
 * Display cleaning for a row that was already stored on this device.
 *
 * [YouTubeDisplayCleaner] cleans a row when it arrives; a row saved by an
 * older build keeps the upload's title and channel forever. This applies the
 * same cleaner on the way out, without rewriting the stored record, so a
 * recent play, a restored queue, a local playlist or a downloaded row reads
 * the way a fresh row does.
 *
 * A small bounded cache keeps repeated UI reads cheap: the same row is cleaned
 * once per process, and entries are evicted least-recently-used. A row that
 * already carries catalogue metadata is left alone unless its artist is a
 * soundtrack label, which must never reach a display field.
 */
object StoredSongDisplay {

    private const val MAX_CACHE = 512

    /**
     * Only the display fields the cleaner rewrites are cached. Queue identity
     * and playback fields are never part of a cached row, so the same track
     * reaching two queues keeps each queue's own entry id.
     */
    private data class Display(
        val title: String,
        val artist: String,
        val albumName: String?,
        val originalTitle: String?,
        val originalArtist: String?,
        val coverSinger: String?,
        val coverOriginalArtist: String?,
    )

    private val cache = object : LinkedHashMap<String, Display>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Display>?): Boolean =
            size > MAX_CACHE
    }

    fun clean(song: Song): Song {
        if (song.videoId.isBlank()) return song.withVideoThumbnailFallback()
        // Catalogue metadata normally means the row is already display-ready,
        // but a cover video can be matched to the original's release. Its
        // singer is still the uploader, not the catalogue artist.
        if (song.albumId != null &&
            !TrackMatcher.isSoundtrackCredit(song.artist) &&
            !CoverTitles.isCover(song)
        ) {
            return song.withVideoThumbnailFallback()
        }
        val key = keyOf(song)
        val display = synchronized(cache) { cache[key] } ?: run {
            val cleaned = YouTubeDisplayCleaner.clean(song)
            Display(
                title = cleaned.title,
                artist = cleaned.artist,
                albumName = cleaned.albumName,
                originalTitle = cleaned.originalTitle,
                originalArtist = cleaned.originalArtist,
                coverSinger = cleaned.coverSinger,
                coverOriginalArtist = cleaned.coverOriginalArtist,
            ).also { synchronized(cache) { cache[key] = it } }
        }
        return song.copy(
            title = display.title,
            artist = display.artist,
            albumName = display.albumName,
            originalTitle = display.originalTitle,
            originalArtist = display.originalArtist,
            coverSinger = display.coverSinger,
            coverOriginalArtist = display.coverOriginalArtist,
        ).withVideoThumbnailFallback()
    }

    /** The stored strings that decide the result; other fields are copied through. */
    private fun keyOf(song: Song): String = buildString {
        append(song.videoId).append('\u0000')
        append(song.title).append('\u0000')
        append(song.artist).append('\u0000')
        append(song.artistId).append('\u0000')
        append(song.albumId).append('\u0000')
        append(song.albumName).append('\u0000')
        append(song.originalTitle).append('\u0000')
        append(song.originalArtist).append('\u0000')
        append(song.coverSinger).append('\u0000')
        append(song.coverOriginalArtist).append('\u0000')
        append(song.isVideo).append('\u0000')
        // Cover reads depend on facts that can load or change after the first
        // read. Without them here the first, possibly stale, display would be
        // cached forever: a designated channel or a newly known singer could
        // never correct it. The original is cheap (a preferences lookup) and
        // the singer set is bounded, so its size/hash is enough to key it.
        val designated = song.artistId?.let { id ->
            runCatching { id.isNotBlank() && id in CoverTitles.designatedChannels() }.getOrDefault(false)
        } ?: false
        append(designated).append('\u0000')
        val singers = runCatching { CoverTitles.knownSingers() }.getOrDefault(emptySet())
        append(singers.size).append('\u0000').append(singers.hashCode()).append('\u0000')
        val original = runCatching { CoverTitles.knownOriginal(song.videoId) }.getOrNull()
        append(original?.first).append('\u0000').append(original?.second)
    }
}
