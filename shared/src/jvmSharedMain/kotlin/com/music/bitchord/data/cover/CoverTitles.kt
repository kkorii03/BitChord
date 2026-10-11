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
    ) {
        /** "원곡자 · 부른사람 커버", or "부른사람 커버" with no original credit. */
        val byline: String
            get() = listOfNotNull(originalArtist, "$singer $COVER_WORD").joinToString(SEPARATOR)
    }

    /** Channel ids the listener marked as cover channels. Set by the app. */
    @Volatile
    var designatedChannels: () -> Set<String> = { emptySet() }

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

    /** The cover a song plays as, or null for anything that is not one. */
    fun read(song: Song): Cover? =
        // A row that lost its video flag on the way — history and Recents
        // shelves often draw an upload with a square thumbnail — is still a
        // cover when its title says so outright. Only the shapes that can't be
        // an ordinary track are read for it.
        read(song.title, song.artist, song.artistId, unambiguousOnly = !song.isVideo)

    fun read(title: String, channel: String, channelId: String?, unambiguousOnly: Boolean = false): Cover? {
        val raw = title.trim()
        if (raw.isEmpty()) return null
        readC(raw, channel)?.let { return it }
        readA(raw, channel, allowBareTag = !unambiguousOnly)?.let { return it }
        if (unambiguousOnly) return null
        val dated = DATE_TAG.containsMatchIn(raw)
        val designated = channelId != null && channelId in designatedChannels()
        if (dated || designated) readB(DATE_TAG.replace(raw, "").trim())?.let { return it }
        return null
    }

    /**
     * The (title, artist) pairs worth searching lyrics under, best first. A
     * read cover searches as the original; a title that might be a B cover
     * without its tag tries both ways round.
     */
    fun lyricsQueries(song: Song): List<Pair<String, String>> {
        read(song)?.let { cover ->
            return listOfNotNull(
                cover.title to (cover.originalArtist ?: cover.singer),
                (cover.title to cover.singer).takeIf { cover.originalArtist != null },
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

    /** "Cover" (or 커버) and whatever tags follow it, at the very end of a title. */
    private val TRAILING_COVER_WORD = Regex(
        """(?i)(?<![a-z가-힣])(?:cover(?:ed)?|커버)\s*(?:[(\[（【][^()\[\]（）【】]*[)\]）】]\s*)*$""",
    )

    private fun readC(raw: String, channel: String): Cover? {
        val tail = TRAILING_COVER_WORD.find(raw) ?: return null
        val head = raw.substring(0, tail.range.first).trimEnd()
        val (song, singerPart) = splitTopLevelDash(head) ?: return null
        val singer = cleanName(singerPart) ?: return null
        if (!isChannelName(singer, channel)) return null
        val title = cleanTitle(song) ?: return null
        return Cover(title = title, originalArtist = null, singer = singer)
    }

    /** Whether [name] is the channel's own: "타비" on "아라하시 타비", "아라하시타비 노래 저장소". */
    private fun isChannelName(name: String, channel: String): Boolean {
        val a = name.lowercase().filter { it.isLetterOrDigit() }
        val b = channel.lowercase().filter { it.isLetterOrDigit() }
        return a.length >= 2 && b.isNotEmpty() && (a in b || b in a)
    }

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
                '-', '–', '—' -> if (depth == 0) {
                    val spaced = (i > 0 && text[i - 1] == ' ') || (i + 1 < text.length && text[i + 1] == ' ')
                    if (c != '-' || spaced || unspaced) {
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
        name = name.split('|', '｜', '/', '#').first().trim()
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
        title = title.trim(' ', '-', '–', '/', '|', ',', '"', '\'', '“', '”', '「', '」', '『', '』')
        return title.takeIf { it.isNotEmpty() }
    }

    private val VERSION_WORDS = Regex(
        """(?i)\b(?:live|ver\.?|version|remix|mix|acoustic|inst\.?|instrumental|mr|piano|edit|demo|""" +
            """official|mv|m/v|audio|lyrics?|visuali[sz]er|sped up|slowed|feat\.?|ft\.?|prod\.?)\b|라이브|버전|가사""",
    )

    private fun isVersionWord(text: String): Boolean = VERSION_WORDS.containsMatchIn(text)
}
