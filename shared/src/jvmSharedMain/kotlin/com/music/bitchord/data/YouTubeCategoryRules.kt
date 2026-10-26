package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher

/**
 * The pure rules behind a curated mood/genre page on the youtube.com fallback.
 *
 * A tile's page is rebuilt from YouTube Music's own playlists rather than the
 * gated mood feed: the featured-playlist search answers YouTube-Music-authored
 * `RDCLAK` collections, the top one supplies a real track list, and the
 * community search supplies the rest. No youtube.com playlist or video search
 * decides a category any more — those returned compilation uploads.
 */
internal object YouTubeCategoryRules {

    /** The raw playlist-id prefix of a YouTube Music editorial collection. */
    const val OFFICIAL_PLAYLIST_PREFIX = "RDCLAK"

    /** The owner YouTube Music bills its editorial playlists to. */
    const val OFFICIAL_PLAYLIST_AUTHOR = "YouTube Music"

    const val OFFICIAL_SHELF_TITLE = "YouTube Music 추천 재생목록"
    const val SONGS_SHELF_TITLE = "노래"
    const val COMMUNITY_SHELF_TITLE = "커뮤니티 재생목록"

    /** The songs shelf is the top official playlist's first page of tracks. */
    const val OFFICIAL_TRACK_LIMIT = 25

    /** Longer rows are compilations rather than one of the playlist's songs. */
    const val MAX_OFFICIAL_TRACK_SECONDS = 10 * 60

    /** The tile's own Korean term, or the measured per-tile override. */
    fun searchTerm(category: ExploreCategory): String =
        (category.queryOverride?.takeIf { it.isNotBlank() } ?: category.query)
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { category.title }

    /** A YouTube Music editorial playlist: `RDCLAK…` art billed to YouTube Music. */
    fun isOfficialPlaylist(item: ShelfItem): Boolean {
        val playlistId = item.browseId?.removePrefix("VL") ?: return false
        if (!playlistId.startsWith(OFFICIAL_PLAYLIST_PREFIX)) return false
        return item.subtitle.contains(OFFICIAL_PLAYLIST_AUTHOR, ignoreCase = true)
    }

    fun officialPlaylists(items: List<ShelfItem>): List<ShelfItem> = items.filter(::isOfficialPlaylist)

    /**
     * Whether a row from an official playlist is one song rather than a
     * compilation. A stated runtime over ten minutes is the decisive answer;
     * the title markers catch a mix that claims to be shorter.
     */
    fun isOfficialTrack(song: Song): Boolean {
        val seconds = TrackMatcher.secondsOf(song.durationText)
        if (seconds != null && seconds > MAX_OFFICIAL_TRACK_SECONDS) return false
        return !COMPILATION_MARKER.containsMatchIn(TrackMatcher.normalize(song.title))
    }

    /**
     * The page's shelves in YouTube Music's order: editorial playlists, the
     * top playlist's songs, then community playlists. Empty shelves are
     * omitted, and a playlist already shown by an earlier shelf is dropped.
     */
    fun categoryShelves(
        official: List<ShelfItem>,
        songs: List<ShelfItem>,
        community: List<ShelfItem>,
        limitPerShelf: Int,
        songLimit: Int = OFFICIAL_TRACK_LIMIT,
    ): List<HomeShelf> {
        val seen = HashSet<String>()
        fun fresh(items: List<ShelfItem>, limit: Int): List<ShelfItem> {
            if (limit <= 0) return emptyList()
            return items.asSequence()
                .filter { item ->
                    val key = item.browseId
                        ?: item.videoId
                        ?: "${item.title}\n${item.subtitle}"
                    key.isNotBlank() && seen.add(key)
                }
                .take(limit)
                .toList()
        }
        return buildList {
            fresh(official, limitPerShelf)
                .takeIf { it.isNotEmpty() }
                ?.let { add(HomeShelf(OFFICIAL_SHELF_TITLE, it)) }
            fresh(songs, songLimit)
                .takeIf { it.isNotEmpty() }
                ?.let { add(HomeShelf(SONGS_SHELF_TITLE, it)) }
            fresh(community, limitPerShelf)
                .takeIf { it.isNotEmpty() }
                ?.let { add(HomeShelf(COMMUNITY_SHELF_TITLE, it)) }
        }
    }

    private val COMPILATION_MARKER = Regex(
        """(?i)(?:\[?\s*playlist\s*\]?|플레이리스트|모음|1\s*시간|한\s*시간|""" +
            """\d+\s*hours?|\bhours?\b|\d+\s*hrs?|\bmix\b|compilation|full\s*album|전곡|연속\s*재생)""",
    )
}
