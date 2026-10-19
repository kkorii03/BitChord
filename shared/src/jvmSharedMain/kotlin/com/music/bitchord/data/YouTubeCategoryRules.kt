package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher

/**
 * The pure rules behind a curated mood/genre page on the youtube.com fallback.
 *
 * A tile's page is rebuilt from anonymous searches rather than YouTube Music's
 * gated mood feed. The same category is asked three ways so one search engine
 * ranking cannot decide the whole page, and the video results are narrowed to
 * single songs because a search for "발라드" also returns hour-long compilations.
 */
internal object YouTubeCategoryRules {

    /** A single song is no shorter than this when the row states a runtime. */
    const val MIN_SINGLE_SECONDS = 90

    /** And no longer than eight minutes; longer rows are mixes or compilations. */
    const val MAX_SINGLE_SECONDS = 8 * 60

    /** One per playlist query, in order. Extra queries have no shelf. */
    val PLAYLIST_SHELF_TITLES = listOf("추천 재생목록", "인기 재생목록", "더 보기")

    /** The three searches that populate the playlist shelves. */
    fun playlistQueries(category: ExploreCategory): List<String> = listOf(
        category.query,
        "${category.title} 노래 모음",
        category.englishQuery,
    ).map { it.replace(Regex("""\s+"""), " ").trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /** The songs shelf asks for the category itself, not for a playlist of it. */
    fun songQuery(category: ExploreCategory): String = category.query
        .replace(Regex("""(?i)\s*플레이리스트\s*$"""), "")
        .replace(Regex("""(?i)\s*playlist\s*$"""), "")
        .trim()
        .ifBlank { category.title }

    /**
     * One shelf per query, in order. An item already shown by an earlier shelf
     * is dropped, and a shelf left empty is omitted rather than drawn as a
     * heading.
     */
    fun playlistShelves(
        groups: List<List<ShelfItem>>,
        limitPerShelf: Int,
    ): List<HomeShelf> {
        if (limitPerShelf <= 0) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<HomeShelf>(groups.size)
        groups.forEachIndexed { index, items ->
            val title = PLAYLIST_SHELF_TITLES.getOrNull(index) ?: return@forEachIndexed
            val fresh = items.asSequence()
                .filter { item ->
                    val key = item.browseId
                        ?: item.videoId
                        ?: "${item.title}\n${item.subtitle}"
                    key.isNotBlank() && seen.add(key)
                }
                .take(limitPerShelf)
                .toList()
            if (fresh.isNotEmpty()) out += HomeShelf(title, fresh)
        }
        return out
    }

    /**
     * Whether [song] is one track rather than a playlist upload.
     *
     * Duration is checked only when the row states one. The title rules catch
     * the compilations that fit inside eight minutes too ("[PLAYLIST] …",
     * "노래 모음", "1시간", "mix").
     */
    fun isSingleSong(song: Song): Boolean {
        val seconds = TrackMatcher.secondsOf(song.durationText)
        if (seconds != null && (seconds < MIN_SINGLE_SECONDS || seconds > MAX_SINGLE_SECONDS)) {
            return false
        }
        val title = TrackMatcher.normalize(song.title)
        return !COMPILATION_MARKER.containsMatchIn(title)
    }

    private val COMPILATION_MARKER = Regex(
        """(?i)(?:\[?\s*playlist\s*\]?|플레이리스트|모음|1\s*시간|한\s*시간|""" +
            """\d+\s*hours?|\bhours?\b|\d+\s*hrs?|\bmix\b|compilation|full\s*album|전곡|연속\s*재생)""",
    )
}
