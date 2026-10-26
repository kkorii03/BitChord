package com.music.bitchord.data.model

/**
 * What a card on a home, search or artist shelf is, in the terms Apple Music
 * uses for its subtitles.
 *
 * YouTube writes its cards' second line as a tally ("1.2M plays", "구독자
 * 423만명") or as a type-and-credit pair ("Song • Artist"). Apple writes only
 * the kind and who made it, so the card is classified here and the credit is
 * pulled out by [appleSubtitleCredit].
 */
enum class AppleCardKind { SONG, ALBUM, PLAYLIST, ARTIST }

/**
 * The kind behind a shelf card, from the ids the parser already keeps.
 *
 * A card with no browse target is a playable song; albums and playlists share
 * the `MPRE`/`OLAK` and `VL`/`PL`/`RD` id families, and artists are the `UC`
 * channels. [isArtist] wins because YouTube sometimes serves an artist card
 * whose id is not the usual channel prefix — see [ShelfItem.isArtist].
 */
fun appleCardKind(
    videoId: String?,
    browseId: String?,
    isArtist: Boolean = false,
): AppleCardKind? {
    if (isArtist) return AppleCardKind.ARTIST
    val id = browseId?.trim().orEmpty()
    if (id.isEmpty()) return if (!videoId.isNullOrBlank()) AppleCardKind.SONG else null
    return when {
        id.startsWith("UC", ignoreCase = true) -> AppleCardKind.ARTIST
        id.startsWith("MPRE", ignoreCase = true) ||
            id.startsWith("OLAK", ignoreCase = true) -> AppleCardKind.ALBUM
        id.startsWith("VL", ignoreCase = true) ||
            id.startsWith("PL", ignoreCase = true) ||
            id.startsWith("RD", ignoreCase = true) ||
            id.startsWith("LM", ignoreCase = true) -> AppleCardKind.PLAYLIST
        !videoId.isNullOrBlank() -> AppleCardKind.SONG
        else -> null
    }
}

/** The same classification for a search result's own declared type. */
fun appleCardKind(type: BrowseType): AppleCardKind? = when (type) {
    BrowseType.ALBUM -> AppleCardKind.ALBUM
    BrowseType.PLAYLIST -> AppleCardKind.PLAYLIST
    BrowseType.ARTIST -> AppleCardKind.ARTIST
    BrowseType.OTHER -> null
}

/**
 * The credit out of a YouTube card subtitle, with its tally removed.
 *
 * "Song • Chelsea Wolfe" becomes "Chelsea Wolfe", "Album • Radiohead • 1997"
 * becomes "Radiohead", and a line that is nothing but "1.2M plays" or
 * "구독자 423만명" becomes null — there is no one to name, and Apple would
 * show only the kind.
 */
fun appleSubtitleCredit(subtitle: String): String? {
    val parts = subtitle
        .split("•", "·", "|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val credits = parts.filter { part ->
        val lower = part.lowercase()
        lower !in KIND_WORDS &&
            lower !in COUNT_WORDS &&
            !part.matches(DURATION) &&
            !part.matches(YEAR) &&
            !part.matches(TALLY)
    }
    val first = credits.firstOrNull() ?: return null
    // A cover's byline names the original artist first and the singer after
    // ("안예은 · 오화요 커버"). Keeping only the first name made a cover read
    // as the original release; the cover credit stays with it.
    val cover = credits.drop(1).firstOrNull { COVER_CREDIT.containsMatchIn(it) }
    return if (cover != null) "$first · $cover" else first
}

private val COVER_CREDIT = Regex("""커버|(?i:\bcover\b)|歌ってみた""")

/**
 * The second line a card draws, from its localized kind label and credit.
 *
 * A song names only its artist, the way Apple Music's song rows do; albums and
 * playlists keep the kind in front of the credit, and an artist card names the
 * kind alone because its title is already the name.
 */
fun appleSubtitleLine(kind: AppleCardKind, label: String, credit: String?): String {
    val name = credit?.trim()?.takeIf { it.isNotEmpty() }
    return when (kind) {
        AppleCardKind.SONG -> name ?: label
        AppleCardKind.ARTIST -> label
        AppleCardKind.ALBUM, AppleCardKind.PLAYLIST ->
            if (name == null) label else "$label · $name"
    }
}

private val DURATION = Regex("""\d+(?::\d{2}){1,2}""")
private val YEAR = Regex("""(?:19|20)\d{2}(?:년|年)?""")

/**
 * A counted quantity rather than a name — "1.2M plays", "구독자 423만명",
 * "1723만회 재생", "12곡", "1시간 4분". Kept narrow enough to leave "21
 * Savage" and "50 Cent" alone: a number only disqualifies a segment when it is
 * counting one of the words YouTube counts with.
 */
private val TALLY = Regex(
    """[\d.,]+\s*[KMB]?\s*(plays|views|likes|subscribers|listeners|songs|tracks|""" +
        """hours?|minutes?|seconds?)\b.*""" +
        """|(?:재생|조회수|좋아요|구독자|월간\s*시청자)\s*[\d.,]+\s*[천만억]?\s*(?:회|명|개)?.*""" +
        """|[\d.,]+\s*[천만억]?\s*(?:회|곡|시간|분|초|명)(?:\s.*)?""",
    RegexOption.IGNORE_CASE,
)

private val KIND_WORDS = setOf(
    "song", "video", "album", "single", "ep", "playlist", "artist", "podcast", "episode",
    "auto playlist", "자동 생성", "자동 재생목록",
    "노래", "동영상", "뮤직비디오", "앨범", "싱글", "아티스트", "재생목록", "팟캐스트", "에피소드",
    "曲", "動画", "アルバム", "シングル", "アーティスト", "プレイリスト", "ポッドキャスト", "エピソード",
)

private val COUNT_WORDS = setOf(
    "subscribers", "monthly listeners", "listeners", "plays", "views",
    "구독자", "월간 시청자", "시청자", "재생", "조회수", "좋아요",
)
