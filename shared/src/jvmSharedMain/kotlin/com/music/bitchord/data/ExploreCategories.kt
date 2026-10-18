package com.music.bitchord.data

/**
 * A curated Explore tile for the Korea/no-Premium fallback.
 *
 * The Music app's mood & genre grid is gated on `FEmusic_moods_and_genres`
 * (measured: zero rows from a Korean IP without Premium), so the same kind of
 * varied grid is rebuilt here from an anonymous youtube.com search per tile.
 * The catalogue is static: nothing is fetched until the listener opens a tile.
 */
internal data class ExploreCategory(
    /** Stable, url-free key; the tile's browse id is `ytq:` + this. */
    val key: String,
    val title: String,
    val query: String,
)

internal object ExploreCategories {

    /** Marks a browse id this catalogue owns; never a YouTube browse id. */
    const val BROWSE_PREFIX = "ytq:"

    val genres: List<ExploreCategory> = listOf(
        ExploreCategory("kpop", "K-pop", "K-pop 플레이리스트"),
        ExploreCategory("ballad", "발라드", "발라드 플레이리스트"),
        ExploreCategory("hiphop", "힙합", "힙합 플레이리스트"),
        ExploreCategory("rnb", "R&B", "R&B 플레이리스트"),
        ExploreCategory("indie", "인디", "인디 플레이리스트"),
        ExploreCategory("rock", "록", "록 플레이리스트"),
        ExploreCategory("pop", "팝", "팝 플레이리스트"),
        ExploreCategory("jpop", "J-pop", "J-pop 플레이리스트"),
        ExploreCategory("anime", "애니메이션", "애니메이션 노래 플레이리스트"),
        ExploreCategory("utaite", "우타이테·VTuber 커버", "우타이테 커버 플레이리스트"),
        ExploreCategory("ost", "OST", "OST 플레이리스트"),
        ExploreCategory("jazz", "재즈", "재즈 플레이리스트"),
        ExploreCategory("classical", "클래식", "클래식 플레이리스트"),
        ExploreCategory("trot", "트로트", "트로트 플레이리스트"),
        ExploreCategory("edm", "EDM", "EDM 플레이리스트"),
        ExploreCategory("lofi", "로파이", "lofi study music playlist"),
    )

    val moods: List<ExploreCategory> = listOf(
        ExploreCategory("workout", "운동", "운동 음악 플레이리스트"),
        ExploreCategory("focus", "집중", "집중 음악 플레이리스트"),
        ExploreCategory("relax", "휴식", "휴식 음악 플레이리스트"),
        ExploreCategory("sleep", "잠잘 때", "잠잘 때 듣는 음악 플레이리스트"),
        ExploreCategory("commute", "출퇴근", "출퇴근 음악 플레이리스트"),
        ExploreCategory("party", "파티", "파티 음악 플레이리스트"),
        ExploreCategory("drive", "드라이브", "드라이브 음악 플레이리스트"),
        ExploreCategory("rainy", "비 오는 날", "비 오는 날 음악 플레이리스트"),
        ExploreCategory("cafe", "카페", "카페 음악 플레이리스트"),
        ExploreCategory("study", "공부", "공부 음악 플레이리스트"),
        ExploreCategory("mood-boost", "기분 전환", "기분 전환 음악 플레이리스트"),
        ExploreCategory("emotional", "감성", "감성 음악 플레이리스트"),
    )

    /** Section title to its tiles, in the order Explore shows them. */
    val sections: List<Pair<String, List<ExploreCategory>>> = listOf(
        "장르" to genres,
        "분위기·상황" to moods,
    )

    val all: List<ExploreCategory> = genres + moods

    fun browseId(category: ExploreCategory): String = BROWSE_PREFIX + category.key

    /** The category behind a tile's browse id, or null when it belongs to someone else. */
    fun find(browseId: String): ExploreCategory? {
        if (!browseId.startsWith(BROWSE_PREFIX)) return null
        val key = browseId.removePrefix(BROWSE_PREFIX)
        return all.firstOrNull { it.key == key }
    }
}
