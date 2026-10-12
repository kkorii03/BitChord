package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song

/**
 * Reads a cover upload's title for the song, whose song it is and who sings it.
 *
 * Covers on YouTube are uploads, not catalogue tracks: the "artist" is the
 * channel, and the title carries everything else in one of two shapes —
 *
 *  - A: `원곡자 - 곡명 / cover by 부른사람(소속)` — the original's credit first,
 *    the singer after a "cover by" (any case, "covered by", the common
 *    "covey by" typo, with or without spaces, a slash, a bar or a bracket in
 *    front). Unambiguous, so it is always read.
 *  - B: `부른사람 - 곡명(원곡자) [24.10.10]` — the singer first and the original
 *    in brackets after the song. The same shape is an ordinary "artist - song
 *    (version)" title, so it is read only with the date tag these channels
 *    end on, or for a channel the listener has marked as a cover channel.
 *  - C: `곡명 - 부른사람 Cover(06.12)` — the song first, then the singer and the
 *    word "Cover", with a stream date or any other tag after it. The original's
 *    artist is not named at all. The same words could be "원곡자 - 곡명 Cover",
 *    so it is read this way only when the name before "Cover" is the channel's
 *    own (타비 on 아라하시 타비).
 *  - D: `부른사람-곡명(번역) Cover.(2)` — C the other way round: the singer
 *    first, the dash often without spaces, "Cover" with a full stop and a
 *    take number. Read the same way C is: only when the name before the dash
 *    is the channel's own, or shares a word with it (아카네 리제 on 리제 노래
 *    아카이브 — a fan archive of the same singer).
 *  - E: `[2023.06.18 부른사람] 원곡자 - 곡명` — a stream archive's clip: the
 *    date and the singer in a leading bracket, then the song as it is
 *    credited. The leading date-and-name tag makes it a cover outright, on
 *    whatever channel it was clipped to.
 *  - C/: `곡명(원곡자) / 부른사람Cover` — the slash says the name after it is
 *    who sings, so it is read on any channel; "Cover" may touch the name.
 *  - F: `곡명 - 원곡자 (부른사람 Cover)` — the singer and "Cover" in a closing
 *    bracket. Which side of the dash is the song is not certain
 *    ([Cover.orderUnsure]); song first is how these clip titles mostly run.
 *  - K: `부른사람 - 곡명` / `곡명 - 부른사람` with no "Cover" at all — read
 *    only for a singer already seen on a cover ([knownSingers]), and not on
 *    that singer's own channel, where it is their own song.
 *
 * Anything else is left as it is. When a title could still be a cover read the
 * B way, [lyricsQueries] tries both readings so the lyrics are found either way
 * while what is shown stays the upload's own title.
 */
object CoverTitles {

    /** A cover, read. */
    data class Cover(
        /** The song's own name. */
        val title: String,
        /** Whose song it is; null when the title does not say. */
        val originalArtist: String?,
        /** Who sings this upload. */
        val singer: String,
        /**
         * "X - Y (부른사람 Cover)" names the song and its artist in an order the
         * title does not settle. Read song first; the lyrics are looked up
         * both ways round.
         */
        val orderUnsure: Boolean = false,
    ) {
        /** "원곡자 · 부른사람 커버", or "부른사람 커버" with no original credit. */
        val byline: String
            get() = listOfNotNull(originalArtist, "$singer $COVER_WORD").joinToString(SEPARATOR)
    }

    /** Channel ids the listener marked as cover channels. Set by the app. */
    @Volatile
    var designatedChannels: () -> Set<String> = { emptySet() }

    /**
     * Singers seen named on covers read for certain ([onSingerSeen]), kept
     * by the app. A clip channel's "아카네 리제 - 곡명" or "곡명 - 아카네 리제"
     * says nothing else that it is a cover; the singer's name does.
     */
    @Volatile
    var knownSingers: () -> Set<String> = { emptySet() }

    /** Told each singer a cover title names for certain. Set by the app. */
    @Volatile
    var onSingerSeen: (String) -> Unit = {}

    private const val COVER_WORD = "커버"
    private const val SEPARATOR = " · "

    /** "cover by", "Covered By", "covey by", "coverby", "커버 by" — then the singer. */
    private val COVER_BY = Regex("""(?i)(?<![a-z])(?:cover(?:ed)?|covey|커버)\s*by\s*[:：]?\s*""")

    /**
     * A bare "cover" / "커버" tag with no name after it: in brackets, or the
     * title's last word. Not just anywhere — "Cover Me" is a song.
     */
    private val COVER_TAG = Regex("""(?i)[(\[]\s*(?:cover(?:ed)?|커버)\s*[)\]]|\s(?:cover|커버)\s*$""")

    /** The date tag B-shaped titles end with: [24.10.10], (2024.10.10), [24-10-10]. */
    private val DATE_TAG = Regex("""[\[(]\s*\d{2,4}\s*[./-]\s*\d{1,2}\s*[./-]\s*\d{1,2}\s*[\])]\s*$""")

    /** A trailing bracket group: the original's credit in a B title, or an agency after a name. */
    private val TRAILING_GROUP = Regex("""\s*[(\[（【]([^()\[\]（）【】]*)[)\]）】]\s*$""")

    private val ANY_GROUP = Regex("""\s*[(\[（【][^()\[\]（）【】]*[)\]）】]""")

    /**
     * The catalogue's own (title, artist) for a cover video whose original
     * recording has been found and measured against — "Shape of You" / "Ed
     * Sheeran" where the upload wrote "Shape of you(Edsheren)". Set by the app.
     */
    @Volatile
    var knownOriginal: (videoId: String) -> Pair<String, String>? = { null }

    /** The cover a song plays as, or null for anything that is not one. */
    fun read(song: Song): Cover? =
        // A row that lost its video flag on the way — history and Recents
        // shelves often draw an upload with a square thumbnail — is still a
        // cover when its title says so outright. Only the shapes that can't be
        // an ordinary track are read for it.
        read(song.title, song.artist, song.artistId, unambiguousOnly = !song.isVideo)?.let { cover ->
            // Spelled the way the record is, once the record is known: covers
            // are titled by hand, typos and capitals and all.
            val known = runCatching { knownOriginal(song.videoId) }.getOrNull()
            if (known == null) cover
            else cover.copy(
                title = known.first.ifBlank { cover.title },
                originalArtist = known.second.ifBlank { cover.originalArtist.orEmpty() }.ifBlank { null },
                orderUnsure = false,
            )
        }

    fun read(title: String, channel: String, channelId: String?, unambiguousOnly: Boolean = false): Cover? {
        val tidy = normalize(title.trim())
        val raw = tidy.text
        if (raw.isEmpty()) return null
        // The shapes that name the singer for certain: worth remembering who.
        (
            readE(raw) ?: readG(raw) ?: readC(raw, channel) ?: (if (tidy.live) readLive(raw) else null) ?: readF(raw)
                ?: readA(raw, channel, allowBareTag = false) ?: readCredit(raw)
            )?.let {
            runCatching { onSingerSeen(it.singer) }
            return it
        }
        if (unambiguousOnly) return null
        // A bare "(Cover)": the channel stands in for the singer, so it isn't one to learn.
        readA(raw, channel, allowBareTag = true)?.let { return it }
        val dated = DATE_TAG.containsMatchIn(raw)
        val undated = DATE_TAG.replace(raw, "").trim()
        // Before B: a known singer on the right of the dash would read the wrong way round as B.
        readKnown(undated, channel)?.let { return it }
        val designated = channelId != null && channelId in designatedChannels()
        if (dated || designated) readB(undated)?.let { return it }
        return null
    }

    /**
     * The (title, artist) pairs worth searching lyrics under, best first. A
     * read cover searches as the original; a title that might be a B cover
     * without its tag tries both ways round.
     */
    fun lyricsQueries(song: Song): List<Pair<String, String>> {
        read(song)?.let { cover ->
            val artist = cover.originalArtist
            return listOfNotNull(
                cover.title to (artist ?: cover.singer),
                // "Song - Artist" read the other way round, in case it was "Artist - Song".
                (artist?.let { it to cover.title })?.takeIf { cover.orderUnsure },
                (cover.title to cover.singer).takeIf { artist != null },
            )
        }
        if (!song.isVideo) return listOf(song.title to song.artist)
        val raw = song.title.trim()
        val (left, right) = splitTopLevelDash(raw) ?: return listOf(song.title to song.artist)
        val group = TRAILING_GROUP.find(right)
        if (group == null) return listOf(song.title to song.artist)
        val credit = group.groupValues[1].trim()
        val name = right.removeRange(group.range).trim()
        if (credit.isEmpty() || name.isEmpty() || isVersionWord(credit)) return listOf(song.title to song.artist)
        // "X - Song (Y)": either X sings Y's song, or X's song in a version
        // called Y. The second is the usual one, so it is tried first.
        return listOf(name to left, name to credit)
    }

    /** What to look a track's lyrics up under — see [LyricsRepository.lyrics]. */
    data class LyricsQuery(
        val title: String,
        val artist: String,
        val alternatives: List<Pair<String, String>>,
        val isVideo: Boolean,
        /**
         * False for a cover whose title never names the original's artist:
         * [artist] is then only the singer, and the song has to be found by
         * its title alone.
         */
        val artistKnown: Boolean = true,
    )

    /**
     * The lyrics search for [song], whether it arrives as the upload or as a
     * queue row already shown as its song ([applyTo]).
     */
    fun lyricsQuery(song: Song): LyricsQuery {
        // Shown as a cover but with the upload's own title lost (a queue saved
        // before covers were kept whole): the byline still names both people.
        if (song.originalTitle == null && song.isVideo && song.artist.endsWith(" $COVER_WORD")) {
            val byline = song.artist.removeSuffix(" $COVER_WORD")
            val original = byline.substringBeforeLast(SEPARATOR, "").trim()
            val singer = byline.substringAfterLast(SEPARATOR).trim()
            return LyricsQuery(
                title = song.title,
                artist = original.ifEmpty { singer },
                alternatives = listOfNotNull((song.title to singer).takeIf { original.isNotEmpty() }),
                isVideo = true,
                artistKnown = original.isNotEmpty(),
            )
        }
        val upload = if (song.originalTitle != null) {
            song.copy(
                title = song.originalTitle ?: song.title,
                artist = song.originalArtist ?: song.artist,
                originalTitle = null,
                originalArtist = null,
            )
        } else {
            song
        }
        val queries = lyricsQueries(upload)
        val (title, artist) = queries.firstOrNull() ?: (song.title to song.artist)
        val cover = read(upload)
        return LyricsQuery(
            // A cover is an upload whatever the row says — a Recents row can
            // arrive without its video flag — so its captions are asked first
            // and its lyrics are put on its own clock.
            title, artist, queries.drop(1), song.isVideo || cover != null,
            artistKnown = cover == null || cover.originalArtist != null,
        )
    }

    /**
     * A queue row as the player shows it: the song as its title, the original
     * and the singer as its byline. The upload's own title and channel are kept
     * alongside, for the menu and for anything matching on them.
     */
    fun applyTo(song: Song): Song {
        if (song.originalTitle != null) return song
        val cover = read(song) ?: return song
        return song.copy(
            title = cover.title,
            artist = cover.byline,
            originalTitle = song.title,
            originalArtist = song.artist,
            coverSinger = cover.singer,
            coverOriginalArtist = cover.originalArtist,
        )
    }

    /**
     * Whether [song] is a cover, as the upload or as already shown — including
     * a row whose cover fields were lost on the way through a saved queue and
     * that only its "… 커버" byline still gives away.
     */
    fun isCover(song: Song): Boolean =
        song.originalTitle != null || song.coverSinger != null ||
            (song.isVideo && song.artist.endsWith(" $COVER_WORD")) || read(song) != null

    /**
     * Who to credit a play to: the singer of a cover, the artist of anything
     * else. Also recovers the singer from a byline that lost its fields on
     * the way through a saved queue.
     */
    fun creditedArtist(song: Song): String {
        song.coverSinger?.takeIf { it.isNotBlank() }?.let { return it }
        val byline = song.artist
        if (byline.endsWith(" $COVER_WORD")) {
            val withoutWord = byline.removeSuffix(" $COVER_WORD")
            return withoutWord.substringAfterLast(SEPARATOR).trim().ifEmpty { byline }
        }
        return byline
    }

    // ---- Clip titles, tidied ------------------------------------------------

    private class Tidy(val text: String, val live: Boolean)

    /**
     * Clippers dress a title up: "[4K] … ㅣ가수(Romaji) 「Live Cover」 [ENG SUB]",
     * "[26.05.29] 곡명 / …", "… LIVE COVERㅣ오화요 클립ㅣ허니즈", "… | 가사 | …".
     * The dressing goes, the separators become " | " and every way of writing
     * the cover tag becomes plain "Cover", so the shapes below see what an
     * official channel would have written. Whether it said "Live Cover" is
     * kept: "X - Y Live Cover" names the singer first.
     */
    private fun normalize(title: String): Tidy {
        val live = LIVE_WORD.containsMatchIn(title)
        var text = BAR.replace(title, " | ")
        while (true) {
            val next = TRAILING_TAG.replace(LEADING_TAG.replace(text, ""), "").trim()
            if (next == text) break
            text = next
        }
        text = DECORATED_COVER.replace(text, " Cover")
        text = LIVE_COVER.replace(text, "Cover")
        text = JUNK_SEGMENT.replace(text, "")
        text = AFTER_COVER.replace(text, "$1")
        return Tidy(text.replace(Regex("\\s{2,}"), " ").trim(), live)
    }

    /** "ㅣ" (a lone vowel, never a word on its own), "｜", "│" and a spaced lower-case "l" used as bars. */
    private val BAR = Regex("""\s*[ㅣ｜│]\s*|\s+l\s+""")
    private val LIVE_WORD = Regex("""(?i)live\s*cover|라이브\s*커버""")
    private val LEADING_TAG = Regex(
        """(?i)^\s*[\[【(]\s*(?:4k|8k|hd|fhd|uhd|live\s*clip|live|mv|m/v|lyrics?|가사|\d{2,4}\s*[./-]\s*\d{1,2}\s*[./-]\s*\d{1,2})\s*[\]】)]\s*""",
    )
    private val TRAILING_TAG = Regex(
        """(?i)\s*[\[【(]\s*(?:eng\s*sub|kor\s*sub|engsub|sub|가사(?:\s*/\s*해석)?|해석|lyrics?|4k|hd)\s*[\]】)]\s*$""",
    )
    private val DECORATED_COVER = Regex("""(?i)\s*[「【]\s*(?:live\s+)?cover\s*[」】]|\s*[(\[]\s*live\s+cover\s*[)\]]""")
    private val LIVE_COVER = Regex("""(?i)(?<![a-z])live\s+cover(?![a-z])|라이브\s*커버""")
    private val JUNK_SEGMENT = Regex("""(?i)\s*\|\s*(?:가사|lyrics?|가사\s*/\s*해석|해석)\s*(?=\||$)""")
    private val AFTER_COVER = Regex("""(?i)((?<![a-z])cover(?:ed)?|(?<![가-힣])커버)\.?\s*\|.*$""")

    /** "X - Y Live Cover" (no "by", no channel to go on): a live clip names its singer first. */
    private fun readLive(raw: String): Cover? {
        val tail = TRAILING_COVER_WORD.find(raw) ?: return null
        val head = raw.substring(0, tail.range.first).trimEnd()
        if (splitTopLevelSlash(head) != null || splitTopLevelPipe(head) != null) return null
        val (left, right) = splitTopLevelDash(head) ?: splitTopLevelDash(head, unspaced = true) ?: return null
        val singer = cleanName(left)?.takeUnless { isVersionWord(it) || isDescriptor(it) } ?: return null
        return songCover(right, singer)
    }

    private val ANY_COVER_WORD = Regex("""(?i)\s*(?<![a-z])cover(?:ed)?(?![a-z])\.?|\s*(?<![가-힣])커버(?![가-힣])""")

    /** "[오화요] Ohwayo - 지금부터 (원곡 : Hebi) Cover": the singer in brackets up front. */
    private val LEADING_SINGER_TAG = Regex("""^[\[【]\s*([^\[\]【】\d][^\[\]【】]{0,24}?)\s*[\]】]\s*(.+)$""")

    private fun readG(raw: String): Cover? {
        val match = LEADING_SINGER_TAG.find(raw) ?: return null
        val singer = cleanName(match.groupValues[1])?.takeUnless { isVersionWord(it) || isDescriptor(it) } ?: return null
        // What follows the first bar is the clipper's tags: "… ㅣ오화요 클립ㅣ허니즈".
        var rest = match.groupValues[2].substringBefore(" | ").trim()
        if (!TRAILING_COVER_WORD.containsMatchIn(rest) && !ORIGINAL_CREDIT.containsMatchIn(rest) && !ANY_COVER_WORD.containsMatchIn(rest)) {
            return null
        }
        // The word itself, wherever it fell — "곡명 Cover (원곡 : X)" keeps its credit.
        rest = ANY_COVER_WORD.replace(rest, "").trim().ifEmpty { return null }
        val dash = splitTopLevelDash(rest)
        // The singer's name again, romanised, before the song: "Ohwayo - …".
        if (dash != null && !hasHangul(dash.first) && !hasKanaOrHan(dash.first) && dash.first.split(' ').size <= 2) {
            return songCover(dash.second, singer)
        }
        return if (dash != null) headCover(rest, singer) else songCover(rest, singer)
    }

    /** "조별하 & 오화요 - All For You | 원곡: 서인국 정은지": a credited original makes it a cover. */
    private fun readCredit(raw: String): Cover? {
        val match = ORIGINAL_CREDIT.find(raw) ?: return null
        val artist = cleanName(match.groupValues[1].ifEmpty { match.groupValues[2] }) ?: return null
        val head = raw.removeRange(match.range).trim().trimEnd('|', '/', ' ')
        val (left, right) = splitTopLevelDash(head) ?: return null
        val singer = cleanName(left)?.takeUnless { isVersionWord(it) } ?: return null
        val title = cleanTitle(right) ?: return null
        return Cover(title = title, originalArtist = artist, singer = singer)
    }

    /** The last " | " outside any bracket: "곡명 - 원곡자 | 부른사람 Cover". */
    private fun splitTopLevelPipe(text: String): Pair<String, String>? {
        var depth = 0
        var at = -1
        for (i in text.indices) {
            when (text[i]) {
                '(', '[', '（', '【', '{', '「', '『' -> depth++
                ')', ']', '）', '】', '}', '」', '』' -> if (depth > 0) depth--
                '|' -> if (depth == 0) at = i
            }
        }
        if (at < 0) return null
        val left = text.substring(0, at).trim()
        val right = text.substring(at + 1).trim()
        return if (left.isNotEmpty() && right.isNotEmpty()) left to right else null
    }

    // ---- Shapes -------------------------------------------------------------

    private fun readA(raw: String, channel: String, allowBareTag: Boolean = true): Cover? {
        val marker = COVER_BY.find(raw)
        if (marker != null) {
            val singer = cleanName(raw.substring(marker.range.last + 1)) ?: return null
            val head = raw.substring(0, marker.range.first).trimEnd(' ', '/', '|', '｜', '(', '[', '-', '–', '—', '·', ',')
            return headCover(head, singer, looseDash = true)
        }
        // "원곡자 - 곡명 (Cover)" with no name: the channel is the singer.
        if (!allowBareTag) return null
        val tag = COVER_TAG.findAll(raw).lastOrNull() ?: return null
        val head = raw.removeRange(tag.range).trimEnd(' ', '/', '|', '｜', '-', '–', '—', '·', ',')
        if (head.isBlank() || splitTopLevelDash(head) == null) return null
        val singer = cleanName(channel) ?: return null
        return headCover(head, singer)
    }

    /**
     * "Cover" (or 커버) and whatever tags follow it, at the very end of a title.
     * "Cover" may touch a name in another script ("오화요Cover"), never a Latin
     * letter ("Discover"); "커버" never a Hangul one.
     */
    private val TRAILING_COVER_WORD = Regex(
        """(?i)(?:(?<![a-z])cover(?:ed)?|(?<![가-힣])커버)\.?\s*(?:[(\[（【][^()\[\]（）【】]*[)\]）】]\s*)*$""",
    )

    private fun readC(raw: String, channel: String): Cover? {
        val tail = TRAILING_COVER_WORD.find(raw) ?: return null
        val head = raw.substring(0, tail.range.first).trimEnd()
        // "곡명(원곡자) / 오화요Cover": after a slash, the name is who sings —
        // on any channel ("아오쿠모 린 Cover" on a fan's account).
        // "곡명 - 원곡자 | 부른사람 Cover" (a bar, from a clip or the singer's own channel) the same way.
        (splitTopLevelSlash(head) ?: splitTopLevelPipe(head))?.let { (song, name) ->
            // "ㅣ(Akane Lize) Cover": a name wholly in brackets is still the name.
            val bare = name.trim().removeSurrounding("(", ")").removeSurrounding("（", "）")
            val singer = cleanName(bare)?.takeUnless { isVersionWord(it) || isDescriptor(it) }
            if (singer != null) songFirst(song, singer)?.let { return it }
        }
        // "Cover" at the end makes a dash without spaces a split too
        // ("아카네 리제-붉은 장미 Cover."), though a spaced one still wins.
        val (left, right) = splitTopLevelDash(head) ?: splitTopLevelDash(head, unspaced = true) ?: return null
        // D: the singer first.
        cleanName(left)?.takeIf { isChannelName(it, channel) }?.let { singer ->
            cleanTitle(right)?.let { return Cover(title = it, originalArtist = null, singer = singer) }
        }
        // C: the song first.
        val singer = cleanName(right) ?: return null
        if (!isChannelName(singer, channel)) return null
        val title = cleanTitle(left) ?: return null
        return Cover(title = title, originalArtist = null, singer = singer)
    }

    /** "[2023.06.18 아카네 리제] 유우리 - 베텔기우스": the date, the singer, the song. */
    private val LEADING_DATE_SINGER = Regex(
        """^[\[(【]\s*\d{2,4}\s*[./-]\s*\d{1,2}\s*[./-]\s*\d{1,2}\s+([^\])】]+?)\s*[\])】]\s*(.+)$""",
    )

    private fun readE(raw: String): Cover? {
        val match = LEADING_DATE_SINGER.find(raw) ?: return null
        val singer = cleanName(match.groupValues[1]) ?: return null
        // "[23.06.18 Live] …" is a dated live upload, not someone's cover.
        if (isVersionWord(singer)) return null
        val rest = match.groupValues[2].trim()
        // A trailing "Cover" tag, if any, says nothing more.
        val head = TRAILING_COVER_WORD.find(rest)?.let { rest.substring(0, it.range.first).trim() }?.ifEmpty { null } ?: rest
        return headCover(head, singer, looseDash = true)
    }

    /** "(아카네 리제 Cover)" closing a title: the singer's name, then the word. */
    private val SINGER_COVER_GROUP = Regex(
        """(?i)[(\[（【]\s*([^()\[\]（）【】]*?[^\s()\[\]（）【】])\s*(?:(?<![a-z])cover(?:ed)?|(?<![가-힣])커버)\.?\s*[)\]）】]\s*$""",
    )

    /** F: "Welcome to the Black Parade - My Chemical Romance (아카네 리제 Cover)". */
    private fun readF(raw: String): Cover? {
        val match = SINGER_COVER_GROUP.find(raw) ?: return null
        val name = match.groupValues[1].trim()
        // "(Acoustic Cover)", "(Korean Cover)", "(cover by X)" say how, not who.
        if (isVersionWord(name) || isDescriptor(name)) return null
        val singer = cleanName(name) ?: return null
        val head = raw.substring(0, match.range.first).trim().trimEnd('/', '|', '｜', '-', '–', '—', '·', ',', ' ')
        if (head.isEmpty()) return null
        return songFirst(head, singer)
    }

    /**
     * K: a clip of a singer already seen on covers, titled with nothing but
     * the singer and the song — "아카네 리제 - しわあわせ(주름 행복)",
     * "-ERROR (VOCALOID) - 아카네 리제". Not on the singer's own channel,
     * where the same title is their own song.
     */
    private fun readKnown(raw: String, channel: String): Cover? {
        val keys = knownSingerKeys()
        if (keys.isEmpty()) return null
        val (left, right) = splitTopLevelDash(raw) ?: splitTopLevelDash(raw, unspaced = true) ?: return null
        fun singerOf(side: String): String? =
            cleanName(side)?.takeIf { compactName(it) in keys && !isChannelName(it, channel) }
        singerOf(left)?.let { singer -> return songCover(right, singer) }
        singerOf(right)?.let { singer -> return songCover(left, singer) }
        return null
    }

    @Volatile
    private var singerKeyCache: Pair<Set<String>, Set<String>>? = null

    private fun knownSingerKeys(): Set<String> {
        val known = knownSingers()
        singerKeyCache?.let { (source, keys) -> if (source === known) return keys }
        val keys = known.mapNotNullTo(HashSet()) { compactName(it).takeIf { key -> key.length >= 2 } }
        singerKeyCache = known to keys
        return keys
    }

    private fun compactName(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * "곡명 - 원곡자" when [head] has a dash — song first, the order unsure —
     * or "곡명(원곡자)" when it doesn't.
     */
    private fun songFirst(head: String, singer: String): Cover? {
        val (song, artist) = splitTopLevelDash(head) ?: return songCover(head, singer)
        val title = cleanTitle(song) ?: return null
        val credit = cleanName(artist)?.takeUnless { isVersionWord(it) || isGenericCredit(it) }
            ?: return Cover(title = title, originalArtist = null, singer = singer)
        return Cover(title = title, originalArtist = credit, singer = singer, orderUnsure = true)
    }

    private fun songCover(text: String, singer: String): Cover? {
        val (title, artist) = songAndCredit(text) ?: return null
        return Cover(title = title, originalArtist = artist, singer = singer)
    }

    /** "(원곡: Vaundy)", "(원곡 Vaundy)", "/ 원곡 Vaundy". */
    private val ORIGINAL_CREDIT = Regex(
        """\s*(?:[(\[（【]\s*원곡\s*[:：]?\s*([^()\[\]（）【】]+?)\s*[)\]）】]|[/|｜]\s*원곡\s*[:：]?\s*(.+)$)""",
    )

    /**
     * A song's name and, from a bracket closing it, its artist: "A Thousand
     * Years(Christina Perri)". Not when the bracket is a version, a voicebank
     * ("VOCALOID") or the name in the other script ("しわあわせ(주름 행복)",
     * "소녀 레이(少女レイ)") — those stay with the name, or are dropped.
     */
    private fun songAndCredit(text: String): Pair<String, String?>? {
        var rest = text.trim()
        var artist: String? = null
        ORIGINAL_CREDIT.find(rest)?.let { match ->
            artist = cleanName(match.groupValues[1].ifEmpty { match.groupValues[2] })
            rest = rest.removeRange(match.range).trim()
        }
        if (artist == null) {
            TRAILING_GROUP.find(rest)?.let { group ->
                val credit = group.groupValues[1].trim()
                val inner = rest.removeRange(group.range).trim()
                when {
                    inner.isEmpty() -> Unit
                    // "(『명조』 OST)", "(최애의 아이 OST)": where it is from, not whose.
                    credit.isEmpty() || isGenericCredit(credit) || OST.containsMatchIn(credit) -> rest = inner
                    isVersionWord(credit) || isDescriptor(credit) || isTranslation(inner, credit) -> Unit
                    else -> {
                        // "[恋風邪にのせて - Vaundy]": the original title and the
                        // artist together — the artist is the side in Latin
                        // letters against kana, or else the first.
                        val pair = splitTopLevelDash(credit)
                        artist = if (pair == null) {
                            cleanName(credit)
                        } else {
                            val (a, b) = pair
                            cleanName(if (hasKanaOrHan(a) && !hasKanaOrHan(b)) b else a)
                        }
                        rest = inner
                    }
                }
            }
        }
        val title = cleanTitle(rest) ?: return null
        return title to artist
    }

    private fun hasHangul(text: String) = text.any { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }
    private fun hasKanaOrHan(text: String) = text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

    /** The bracket gives [name] in the other script — a reading or a translation, not a person. */
    private fun isTranslation(name: String, bracket: String): Boolean =
        (hasKanaOrHan(name) && !hasHangul(name) && hasHangul(bracket) && !hasKanaOrHan(bracket)) ||
            (hasHangul(name) && hasKanaOrHan(bracket) && !hasHangul(bracket)) ||
            // "An Unaware Drunk (酔いどれ知らず)": a phrase in hiragana is a title, not a name.
            (!hasKanaOrHan(name) && !bracket.contains(" - ") && bracket.count { it in '぀'..'ゟ' } >= 3 && bracket.length >= 6)

    private val GENERIC_CREDIT = Regex(
        """(?i)^(?:vocaloid|보컬로이드|보카로|utau|cevio|synth\s*v|synthesizer\s*v|歌ってみた|cover|커버|노래)$""",
    )

    private val OST = Regex("""(?i)\bost\b|o\.s\.t""")

    /** A voicebank or a word, not whose song it is. */
    private fun isGenericCredit(text: String): Boolean = GENERIC_CREDIT.matches(text.trim())

    private val DESCRIPTOR_WORDS = setOf(
        "korean", "english", "japanese", "chinese", "kor", "eng", "jpn", "jp", "kr",
        "한국어", "영어", "일본어", "중국어", "한국어버전", "male", "female", "남자", "여자", "남자키", "여자키",
        "full", "short", "band", "밴드", "acoustic", "어쿠스틱", "guitar", "기타", "피아노", "vocal", "보컬",
        "a", "cappella", "acappella", "아카펠라", "duet", "듀엣", "혼성", "ai", "커버곡", "rock", "jazz", "재즈",
        "orchestra", "오케스트라", "lofi", "lo-fi", "metal", "punk", "rnb", "r&b", "ballad", "발라드",
    )

    /** "Korean", "Full", "Male Ver", "어쿠스틱 밴드" — how it was covered, not by whom. */
    private fun isDescriptor(text: String): Boolean {
        val words = text.lowercase().split(' ', '　', '.', '/').filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { it in DESCRIPTOR_WORDS || it.all(Char::isDigit) }
    }

    /** The last slash between words, outside any bracket: "곡명(원곡자) / 부른사람". Not "AC/DC". */
    internal fun splitTopLevelSlash(text: String): Pair<String, String>? {
        var depth = 0
        var at = -1
        for (i in text.indices) {
            when (text[i]) {
                '(', '[', '（', '【', '{' -> depth++
                ')', ']', '）', '】', '}' -> if (depth > 0) depth--
                '/', '／' -> if (depth == 0) {
                    val spaced = (i > 0 && text[i - 1] == ' ') || (i + 1 < text.length && text[i + 1] == ' ')
                    // "곡명(원곡자)/부른사람cover": right after a closing bracket a
                    // slash can't be inside a name like "AC/DC".
                    val afterGroup = i > 0 && text[i - 1] in ")]）】}"
                    if (spaced || afterGroup || text[i] == '／') at = i
                }
            }
        }
        if (at < 0) return null
        val left = text.substring(0, at).trim()
        val right = text.substring(at + 1).trim()
        return if (left.isNotEmpty() && right.isNotEmpty()) left to right else null
    }

    /**
     * Whether [name] is the channel's own: "타비" on "아라하시 타비",
     * "아라하시타비 노래 저장소" — or shares a word of two letters or more
     * with it: "아카네 리제" on "리제 노래 아카이브".
     */
    private fun isChannelName(name: String, channel: String): Boolean {
        fun compact(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
        val a = compact(name)
        val b = compact(channel)
        if (a.length < 2 || b.isEmpty()) return false
        if (a in b || b in a) return true
        return name.split(' ', '\u3000').map(::compact).any { word -> word.length >= 2 && word !in NOT_NAMES && word in b }
    }

    /** Words a channel and a title can share without being the same person. */
    private val NOT_NAMES = setOf("cover", "커버", "노래", "music", "official", "song", "songs", "channel", "채널")

    private fun headCover(head: String, singer: String, looseDash: Boolean = false): Cover? {
        // With "cover by" in the title it is a cover for certain, so a dash
        // without spaces ("아이유-밤편지") is taken for the split too — though a
        // spaced one, where there is one, still wins.
        val split = splitTopLevelDash(head) ?: if (looseDash) splitTopLevelDash(head, unspaced = true) else null
        val (artist, song) = if (split != null) split else (null to head)
        val title = cleanTitle(song) ?: return null
        return Cover(title = title, originalArtist = artist?.let { cleanName(it) }, singer = singer)
    }

    private fun readB(raw: String): Cover? {
        val (left, right) = splitTopLevelDash(raw) ?: return null
        val singer = cleanName(left) ?: return null
        val group = TRAILING_GROUP.find(right)
        val credit = group?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && !isVersionWord(it) }
        val name = if (group != null && credit != null) right.removeRange(group.range) else right
        val title = cleanTitle(name) ?: return null
        return Cover(title = title, originalArtist = credit, singer = singer)
    }

    // ---- Pieces -------------------------------------------------------------

    /**
     * Splits at the first dash standing between words, outside any bracket:
     * "A - B" and "A-B" but not "Re-Love" inside a name — a dash counts only
     * with a space on at least one side, or as an en/em dash.
     */
    internal fun splitTopLevelDash(text: String, unspaced: Boolean = false): Pair<String, String>? {
        var depth = 0
        for (i in text.indices) {
            when (val c = text[i]) {
                '(', '[', '（', '【', '{' -> depth++
                ')', ']', '）', '】', '}' -> if (depth > 0) depth--
                '-', '–', '—', '_' -> if (depth == 0) {
                    val spaced = (i > 0 && text[i - 1] == ' ') || (i + 1 < text.length && text[i + 1] == ' ')
                    // An underscore separates only with spaces on both sides:
                    // "YOUNHA _ WINTER FLOWER", never "snake_case".
                    val bothSpaced = i > 0 && text[i - 1] == ' ' && i + 1 < text.length && text[i + 1] == ' '
                    if (if (c == '_') bothSpaced else c != '-' || spaced || unspaced) {
                        val left = text.substring(0, i).trim()
                        val right = text.substring(i + 1).trim()
                        if (left.isNotEmpty() && right.isNotEmpty()) return left to right
                    }
                }
            }
        }
        return null
    }

    /** A singer's or artist's name: brackets (an agency, a translation) and stray marks dropped. */
    private fun cleanName(text: String): String? {
        var name = text.trim()
        // Everything after a closing bracket or a second separator is not the name.
        // A slash only separates with a space beside it: "HUNTR/X" and "AC/DC"
        // are names, "가수 / 소속" is two things.
        name = name.split('|', '｜', '#').first().split(SPACED_SLASH).first().trim()
        while (true) {
            val stripped = ANY_GROUP.replace(name, "").trim()
            if (stripped == name) break
            name = stripped
        }
        name = name.trim(' ', '(', ')', '[', ']', '-', '–', '·', ',', '"', '\'', '“', '”')
        return name.takeIf { it.isNotEmpty() && it.length <= 60 }
    }

    private fun cleanTitle(text: String): String? {
        var title = text.trim()
        title = COVER_TAG.replace(title, " ").trim()
        // A dash touching the first letter is part of the name: "-ERROR".
        val dashName = title.length > 1 && title[0] == '-' && title[1].isLetterOrDigit()
        title = title.trim(' ', '-', '–', '/', '|', ',', '"', '\'', '“', '”', '「', '」', '『', '』')
        if (dashName && title.isNotEmpty()) title = "-$title"
        // "여우의 별자리 「심월과 달의 노래」": a quote the title opens, it closes.
        if (title.count { it == '「' } > title.count { it == '」' }) title += "」"
        if (title.count { it == '『' } > title.count { it == '』' }) title += "』"
        return title.takeIf { it.isNotEmpty() }
    }

    private val SPACED_SLASH = Regex("""\s/|/\s""")

    private val VERSION_WORDS = Regex(
        """(?i)\b(?:live|ver\.?|version|remix|mix|acoustic|inst\.?|instrumental|mr|piano|edit|demo|""" +
            """official|mv|m/v|audio|lyrics?|visuali[sz]er|sped up|slowed|feat\.?|ft\.?|prod\.?)\b|라이브|버전|가사""",
    )

    private fun isVersionWord(text: String): Boolean = VERSION_WORDS.containsMatchIn(text)
}
