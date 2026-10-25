package com.music.bitchord.data

/**
 * The Korean names YouTube Music's Explore tiles are known by.
 *
 * The server sends these categories in English even when the rest of the page
 * is Korean ("Enka/kayokyoku", "Anime & soundtracks"). The label is mapped at
 * display time so the browse id, the hide list and every other use of the
 * original title stay untouched.
 *
 * Only the Korean build gets the translation: every other language keeps the
 * server's own wording, which is already the language the listener asked for.
 */
object ExploreCategoryNames {

    private val KOREAN = mapOf(
        "enka/kayokyoku" to "엔카/가요곡",
        "anime & soundtracks" to "애니메이션 & 사운드트랙",
        "anime and soundtracks" to "애니메이션 & 사운드트랙",
        "seasonal" to "시즌",
        "vocaloid & utaite" to "보컬로이드 & 우타이테",
        "vocaloid and utaite" to "보컬로이드 & 우타이테",
    )

    /** [title] in Korean when this is the Korean build and a translation exists. */
    fun displayTitle(title: String, languageTag: String): String {
        if (!languageTag.lowercase().startsWith("ko")) return title
        return koreanTitle(title) ?: title
    }

    /** The Korean label for [title], or null when the table has no entry. */
    fun koreanTitle(title: String): String? = KOREAN[normalize(title)]

    /** Case, surrounding space and repeated whitespace never change a label. */
    private fun normalize(title: String): String = title
        .trim()
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}
