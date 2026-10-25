package com.music.bitchord.data.model

/**
 * An album page's subtitle split into the parts its header and footer draw.
 *
 * YouTube's release headers arrive in more than one shape: the current one
 * puts "Album • 1997" on the subtitle and the artist on a strapline (joined by
 * the parser with the same bullet), while the youtube.com fallback and some
 * Korean responses use commas — "Bruno Mars, 앨범, 2026년". Both are read here,
 * including the Korean 년 suffix that the old year check missed.
 *
 * A full release date is kept when the subtitle actually states one; most
 * catalogue albums only state the year, so [year] is the reliable field and
 * [month]/[day] are null.
 */
data class AlbumSubtitle(
    val credit: String,
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
) {
    val hasFullDate: Boolean get() = year != null && month != null && day != null
}

fun parseAlbumSubtitle(subtitle: String): AlbumSubtitle {
    val parts = subtitle
        .split("•", "·", ",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    var year: Int? = null
    var month: Int? = null
    var day: Int? = null
    val credit = mutableListOf<String>()

    parts.forEach { part ->
        val date = parseDate(part)
        if (date != null) {
            if (year == null) {
                year = date.first
                month = date.second
                day = date.third
            }
            return@forEach
        }
        if (part.lowercase() in KIND_WORDS) return@forEach
        credit += part
    }

    return AlbumSubtitle(
        credit = credit.joinToString(", "),
        year = year,
        month = month,
        day = day,
    )
}

/** Year, and the month/day when the token states them. */
private fun parseDate(raw: String): Triple<Int, Int?, Int?>? {
    val text = raw.trim().removeSuffix(".")

    KOREAN_DATE.find(text)?.let { match ->
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
        val day = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
        return dateOrNull(year, month, day)
    }
    NUMERIC_DATE.find(text)?.let { match ->
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull()
        val day = match.groupValues[3].toIntOrNull()
        return dateOrNull(year, month, day)
    }
    BARE_YEAR.matchEntire(text)?.let { match ->
        val year = match.groupValues[1].toIntOrNull() ?: return null
        return dateOrNull(year, null, null)
    }
    return null
}

private fun dateOrNull(year: Int, month: Int?, day: Int?): Triple<Int, Int?, Int?>? {
    if (year !in 1000..9999) return null
    if (month != null && month !in 1..12) return null
    if (day != null && day !in 1..31) return null
    if (month == null && day != null) return null
    return Triple(year, month, day)
}

private val KOREAN_DATE = Regex(
    """^(\d{4})년(?:\s*(\d{1,2})월(?:\s*(\d{1,2})일)?)?$""",
)

private val NUMERIC_DATE = Regex(
    """^(\d{4})[-./]\s*(\d{1,2})[-./]\s*(\d{1,2})$""",
)

private val BARE_YEAR = Regex("""^(\d{4})$""")

private val KIND_WORDS = setOf(
    "album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video",
    "앨범", "정규 앨범", "미니 앨범", "싱글", "재생목록", "아티스트", "팟캐스트", "에피소드",
    "노래", "동영상", "뮤직비디오",
)
