package com.music.bitchord.data.net

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.isExactArtistMatch
import com.music.bitchord.data.model.normalizedArtistName

/**
 * The artist page a name search should open.
 *
 * A name alone is not an identity: searching `IVE` answers both the channel
 * called exactly `IVE` and the one called `IVE (아이브)`, and the first hit is
 * not always the artist the track was by. An exact match used to be the whole
 * safety rule, which opened a 73-subscriber channel that happened to be named
 * `IVE` while the 41M-listener artist was billed `IVE (아이브)`.
 *
 * So a candidate is accepted when its title is the name either exactly or
 * followed by a parenthetical alias — `IVE (아이브)`, `NewJeans (뉴진스)` — and
 * the accepted candidates are ranked by the audience size the search row
 * states. Names that merely *contain* the query are still refused: `2115` must
 * not open White 2115, Bedoes 2115, Blacha 2115 or Kuqe 2115.
 *
 * The row order is the last tie-break, so an answer with no stated audience
 * keeps YouTube's own relevance order.
 */
internal fun bestArtistCandidate(candidates: List<BrowseItem>, wanted: String): BrowseItem? {
    if (normalizedArtistName(wanted).isEmpty()) return null
    return candidates
        .asSequence()
        .filter { it.type == BrowseType.ARTIST && it.browseId.isNotBlank() }
        .mapIndexedNotNull { index, item ->
            if (!artistTitleMatches(item.title, wanted)) {
                null
            } else {
                RankedArtist(
                    item = item,
                    index = index,
                    audience = artistAudienceSize(item.subtitle),
                    exact = isExactArtistMatch(item.title, wanted),
                )
            }
        }
        .sortedWith(
            compareByDescending<RankedArtist> { it.audience }
                .thenByDescending { it.exact }
                .thenBy { it.index },
        )
        .firstOrNull()
        ?.item
}

/** Whether [title] is [wanted], or [wanted] plus a parenthetical alias. */
internal fun artistTitleMatches(title: String, wanted: String): Boolean {
    if (isExactArtistMatch(title, wanted)) return true
    val name = normalizedArtistName(wanted)
    if (name.isEmpty()) return false
    val folded = normalizedArtistName(title)
    if (!folded.startsWith(name)) return false
    val rest = folded.substring(name.length).trimStart()
    return rest.startsWith("(") || rest.startsWith("[")
}

/**
 * The audience figure a search row states, in people.
 *
 * YouTube Music writes "구독자 73명" or "월간 시청자 4120만명" in Korean and
 * "1.2M subscribers" / "4.1M monthly listeners" in English. A number with no
 * unit is read as people, which is right for a row that says "73 subscribers";
 * a row with no number scores zero and falls back to the exact-match and
 * relevance ordering.
 */
internal fun artistAudienceSize(subtitle: String): Long {
    var best = 0L
    for (match in AUDIENCE_NUMBER.findAll(subtitle)) {
        val value = parseCount(match.groupValues[1]) ?: continue
        val multiplier = when (match.groupValues.getOrNull(2).orEmpty().lowercase()) {
            "억" -> 100_000_000.0
            "만" -> 10_000.0
            "천" -> 1_000.0
            "b", "billion" -> 1_000_000_000.0
            "m", "million" -> 1_000_000.0
            "k", "thousand" -> 1_000.0
            else -> 1.0
        }
        val people = (value * multiplier).toLong().coerceAtLeast(0L)
        if (people > best) best = people
    }
    return best
}

/**
 * A count as written. A comma is a thousands separator when it groups three
 * digits (`4,120만`, `1,200 subscribers`) and a decimal mark otherwise.
 */
private fun parseCount(raw: String): Double? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val normalised = if (text.contains(',') && text.substringAfterLast(',').length == 3) {
        text.replace(",", "")
    } else {
        text.replace(',', '.')
    }
    return normalised.toDoubleOrNull()
}

private data class RankedArtist(
    val item: BrowseItem,
    val index: Int,
    val audience: Long,
    val exact: Boolean,
)

private val AUDIENCE_NUMBER = Regex(
    """(\d[\d,.]*)\s*(억|만|천|billion|million|thousand|[BMK])?""",
    RegexOption.IGNORE_CASE,
)
