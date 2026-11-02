package com.music.bitchord.data

/**
 * A curated Explore tile for the Korea/no-Premium fallback.
 *
 * The Music app's mood & genre grid is gated on `FEmusic_moods_and_genres`
 * (measured: zero rows from a Korean IP without Premium), so the same kind of
 * varied grid is rebuilt here from YouTube Music's own mood/genre searches.
 * The catalogue is static: a tile's search runs only when it is opened, and
 * its cover only once the tile scrolls into view.
 */
internal data class ExploreCategory(
    /** Stable, url-free key; the tile's browse id is `ytq:` + this. */
    val key: String,
    val title: String,
    /** The Korean mood/genre term YouTube Music itself answers for this tile. */
    val query: String,
    /**
     * A measured, better search term for this one tile when the label alone
     * returns the wrong official playlists. Null keeps [query].
     */
    val queryOverride: String? = null,
)

internal object ExploreCategories {

    /** Marks a browse id this catalogue owns; never a YouTube browse id. */
    const val BROWSE_PREFIX = "ytq:"

    val genres: List<ExploreCategory> = listOf(
        ExploreCategory("kpop", "K-pop", "K-pop"),
        ExploreCategory("ballad", "발라드", "발라드"),
        ExploreCategory("hiphop", "힙합", "힙합"),
        ExploreCategory("rnb", "R&B", "R&B"),
        ExploreCategory("indie", "인디", "인디"),
        ExploreCategory("rock", "록", "록"),
        ExploreCategory("pop", "팝", "팝"),
        ExploreCategory("jpop", "J-pop", "J-pop"),
        ExploreCategory("trot", "트로트", "트로트"),
        ExploreCategory("ost", "OST", "OST"),
        ExploreCategory("jazz", "재즈", "재즈"),
        ExploreCategory("classical", "클래식", "클래식"),
        ExploreCategory("edm", "EDM", "EDM"),
        ExploreCategory("anime", "애니메이션", "애니메이션"),
        ExploreCategory("utaite", "우타이테·VTuber 커버", "우타이테"),
        ExploreCategory("lofi", "로파이", "로파이"),
    )

    /** The same order as YouTube Music's mood grid, with its Korean labels. */
    val moods: List<ExploreCategory> = listOf(
        ExploreCategory("game", "게임", "게임"),
        ExploreCategory("energize", "에너지 충전", "에너지 충전"),
        ExploreCategory("sleep", "잠잘 때", "잠잘 때"),
        ExploreCategory("focus", "집중할 때", "집중할 때"),
        ExploreCategory("commute", "출퇴근 & 등하교", "출퇴근"),
        ExploreCategory("chill", "편안한 분위기", "편안한"),
        ExploreCategory("feel-good", "행복한 기분", "행복한"),
        ExploreCategory("party", "파티", "파티"),
        ExploreCategory("romance", "로맨스", "로맨스"),
        ExploreCategory("sad", "슬플 때", "슬플 때"),
        ExploreCategory("workout", "운동", "운동"),
        ExploreCategory("family", "가족", "가족"),
        ExploreCategory("rainy", "비 오는 날", "비 오는 날"),
        ExploreCategory("drive", "드라이브", "드라이브"),
        ExploreCategory("study", "공부", "공부"),
    )

    /** Section title to its tiles, in the order Explore shows them: moods first. */
    val sections: List<Pair<String, List<ExploreCategory>>> = listOf(
        "분위기·상황" to moods,
        "장르" to genres,
    )

    val all: List<ExploreCategory> = moods + genres

    fun browseId(category: ExploreCategory): String = BROWSE_PREFIX + category.key

    /** The category behind a tile's browse id, or null when it belongs to someone else. */
    fun find(browseId: String): ExploreCategory? {
        if (!browseId.startsWith(BROWSE_PREFIX)) return null
        val key = browseId.removePrefix(BROWSE_PREFIX)
        return all.firstOrNull { it.key == key }
    }
}
