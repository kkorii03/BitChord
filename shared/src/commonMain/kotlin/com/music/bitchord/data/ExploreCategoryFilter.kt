package com.music.bitchord.data

import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection

/**
 * Removes mood and genre tiles the listener has hidden from Explore.
 *
 * Matching is by the visible label rather than the browse id: one YouTube
 * category arrives under a different id depending on whether Music or the
 * youtube.com fallback answered, while its label is the thing the listener
 * recognised and long-pressed. Normalisation treats `&`, `and` and `및` as one
 * word so a Korean label and its English counterpart can share an entry.
 *
 * Charts and hot lists are never hidden: they are not a taste to filter but
 * the page's standing entry points, and hiding one would remove the only way
 * back to the charts page.
 */
object ExploreCategoryFilter {

    /**
     * Seeded once, on the first run of the version that introduced hiding.
     *
     * The India-registered account this fork uses receives these regional
     * categories through the proxy. Both spellings are kept because YouTube
     * may answer in Korean or English.
     */
    val SEED_TITLES: List<String> = listOf(
        "발리우드 & 인도",
        "아랍 음악",
        "Bollywood & Indian",
        "Arabic",
    )

    private val PROTECTED_BROWSE_IDS = setOf("FEmusic_charts")

    private val PROTECTED_TITLES = setOf(
        "차트",
        "charts",
        "인기",
        "인기곡",
        "인기 급상승",
        "hot",
        "hot list",
        "hotlist",
    )

    /**
     * Lowercase, trimmed, single-spaced, with `&`, `and` and `및` all written
     * as `및`. That is the only equality the filter needs: it never tries to
     * guess that two different labels mean the same thing.
     */
    fun normalize(title: String): String = title
        .trim()
        .lowercase()
        .replace("&", " and ")
        .replace("및", " 및 ")
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { if (it == "and" || it == "및") "및" else it }
        .joinToString(" ")

    /** Whether a tile is a standing entry point that hiding must never remove. */
    fun isProtected(item: MoodGenre): Boolean =
        item.browseId in PROTECTED_BROWSE_IDS || normalize(item.title) in PROTECTED_TITLES

    /**
     * [sections] without the tiles whose normalised title is in [hidden].
     *
     * Section and tile order are preserved. A section left with no tiles is
     * dropped; an empty input stays empty.
     */
    fun visible(
        sections: List<MoodGenreSection>,
        hidden: Set<String>,
    ): List<MoodGenreSection> {
        if (hidden.isEmpty()) return sections
        val hiddenKeys = hidden.mapTo(HashSet(), ::normalize)
        return sections.mapNotNull { section ->
            val items = section.items.filter { item ->
                isProtected(item) || normalize(item.title) !in hiddenKeys
            }
            if (items.isEmpty()) null else section.copy(items = items)
        }
    }

    /**
     * The set to store on this run.
     *
     * [alreadySeeded] is the persisted one-time flag. Once it is true the
     * stored set is returned untouched, even when it is empty: the listener
     * deliberately showing every category again must not be re-seeded on the
     * next launch.
     */
    fun seedHidden(current: Set<String>, alreadySeeded: Boolean): Set<String> =
        if (alreadySeeded) current else current + SEED_TITLES
}
