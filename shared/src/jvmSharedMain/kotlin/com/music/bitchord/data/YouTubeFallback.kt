package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.charts.ChartType
import com.music.bitchord.data.charts.ChartsApi
import com.music.bitchord.data.cover.YouTubeCoverResolver
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.YouTubeWebParser
import com.music.bitchord.data.model.ChartsPage
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UserPlaylist
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The browsing side of the Korea/no-Premium fallback.
 *
 * `www.youtube.com` still serves this account's own home, history, liked
 * playlist and playlists, and its `RD…` mixes are the same seed radio the
 * Music app would have built. All of it is mapped onto the app's existing
 * [HomeShelf]/[Song] models, so no screen has to know which site answered.
 */
internal object YouTubeFallback {

    /** YouTube's own "Music" channel; what an anonymous gated listener can still see. */
    const val MUSIC_CHANNEL = "UC-9-kyTW8ZkZNDHQJ6FgpwQ"

    private const val TAG = "BitChord"
    private const val CHANNEL_CACHE_MS = 10L * 60 * 1000
    private const val LIKED_MUSIC = "VLLM"
    private const val LIKED_VIDEOS = "VLLL"
    private const val RECENT_TITLE = "Recents"
    private const val LIKED_TITLE = "Liked music"
    private const val RECENT_LIMIT = 20
    /** Only this much of a youtube.com history page is judged and shown at once. */
    private const val HISTORY_LIMIT = 30
    /** Square-cover lookups for the home feed, spread over its first shelves. */
    private const val HOME_COVER_LIMIT = 20
    private const val SHELF_COVER_LIMIT = 6
    private const val CATEGORY_PREFIX = "ytc:"

    private val continuationTokens = ConcurrentHashMap<String, Boolean>()

    private val channelLock = Mutex()

    private val likedLock = Mutex()

    @Volatile
    private var channelCache: Pair<Long, List<HomeShelf>>? = null

    @Volatile
    private var likedCache: Pair<Long, YouTubeWebParser.PlaylistResult?>? = null

    private const val LIKED_CACHE_MS = 5L * 60 * 1000

    // ---- Home ---------------------------------------------------------------

    suspend fun home(): HomeFeed {
        val personal = runCatching {
            YouTubeWebParser.parseHome(
                Innertube.browseYouTube("FEwhat_to_watch"),
                musicOnly = true,
            )
        }.onFailure { Log.w(TAG, "YouTube home failed: ${it.message}") }
            .getOrNull()

        val shelves = mutableListOf<HomeShelf>()
        personal?.shelves?.let { shelves.addAll(it) }

        val liked = if (Innertube.cookie != null) likedFirstPage() else null
        if (liked != null && liked.songs.isNotEmpty()) {
            shelves += HomeShelf(LIKED_TITLE, liked.songs.map(::shelfItem))
        }

        if (shelves.isEmpty()) shelves += channelShelves()
        val continuation = personal?.continuation?.also(::rememberContinuation)
        return HomeFeed(shelves = enrichCovers(shelves), continuation = continuation)
    }

    suspend fun moreHome(token: String): HomeFeed {
        val root = runCatching { Innertube.browseYouTubeContinuation(token) }.getOrNull()
            ?: return HomeFeed(emptyList(), null)
        val page = YouTubeWebParser.parseHome(root, musicOnly = true)
        page.continuation?.let(::rememberContinuation)
        return HomeFeed(enrichCovers(page.shelves), page.continuation)
    }

    suspend fun recentShelf(): HomeShelf? {
        val songs = runCatching { history() }.getOrDefault(emptyList())
            .distinctBy { it.videoId }
            .take(RECENT_LIMIT)
        if (songs.isEmpty()) return null
        return HomeShelf(RECENT_TITLE, songs.map(::shelfItem))
    }

    /**
     * The account's youtube.com history, music rows only.
     *
     * `FEhistory` is every video ever watched, so the parser's badges are not
     * enough on their own; [YouTubeMusicClassifier] settles the first page
     * (cheap rules first, one anonymous category lookup for the rest) and the
     * survivors get square covers. A failed lookup keeps the row.
     */
    suspend fun history(): List<Song> {
        val parsed = YouTubeWebParser.parseHistory(Innertube.browseYouTube("FEhistory"))
        val music = YouTubeMusicClassifier.filterMusic(parsed, HISTORY_LIMIT)
        return YouTubeCoverResolver.enrich(music, HISTORY_LIMIT)
    }

    suspend fun quickPicks(excludeSongIds: Set<String>): List<Song> =
        home().shelves
            .flatMap { it.items }
            .mapNotNull { item ->
                val videoId = item.videoId ?: return@mapNotNull null
                if (videoId in excludeSongIds) null
                else Song(
                    videoId = videoId,
                    title = item.title,
                    artist = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                )
            }
            .distinctBy { it.videoId }

    // ---- Explore ------------------------------------------------------------

    suspend fun exploreSections(): List<MoodGenreSection> =
        channelShelves().mapIndexedNotNull { index, shelf ->
            val first = shelf.items.firstOrNull() ?: return@mapIndexedNotNull null
            MoodGenreSection(
                title = shelf.title,
                items = listOf(
                    MoodGenre(
                        title = shelf.title,
                        browseId = "$CATEGORY_PREFIX$index",
                        params = null,
                        thumbnailUrl = first.thumbnailUrl,
                    ),
                ),
            )
        }

    suspend fun categoryShelves(browseId: String): List<HomeShelf> {
        if (browseId.startsWith(CATEGORY_PREFIX)) {
            val index = browseId.removePrefix(CATEGORY_PREFIX).toIntOrNull()
                ?: return emptyList()
            return channelShelves().getOrNull(index)?.let { listOf(it) }.orEmpty()
        }
        if (browseId == MUSIC_CHANNEL) return channelShelves()
        return emptyList()
    }

    suspend fun charts(country: String? = null): ChartsPage {
        val shelves = ChartType.entries.mapNotNull { type ->
            ChartsApi.entries(country, type)
                .takeIf { it.isNotEmpty() }
                ?.let { entries -> HomeShelf(type.shelfTitle, entries.map { it.shelfItem() }) }
        }
        return ChartsPage(
            country = ChartsApi.displayCountry(country),
            countries = ChartsApi.countries,
            shelves = shelves.ifEmpty { channelShelves() },
        )
    }

    /** The Music channel's shelves, shared by Home, Explore and the charts card. */
    suspend fun channelShelves(): List<HomeShelf> {
        val now = System.currentTimeMillis()
        channelCache?.let { (at, shelves) ->
            if (now - at < CHANNEL_CACHE_MS) return shelves
        }
        return channelLock.withLock {
            channelCache?.let { (at, shelves) ->
                if (now - at < CHANNEL_CACHE_MS) return@withLock shelves
            }
            val shelves = runCatching {
                YouTubeWebParser.parseHome(
                    Innertube.browseYouTube(MUSIC_CHANNEL),
                    musicOnly = false,
                ).shelves
            }.onFailure { Log.w(TAG, "YouTube Music channel failed: ${it.message}") }
                .getOrDefault(emptyList())
            channelCache = System.currentTimeMillis() to shelves
            shelves
        }
    }

    // ---- Library ------------------------------------------------------------

    suspend fun library(): LibraryPage {
        val liked = likedFirstPage()
        liked?.continuation?.let(::rememberContinuation)
        val likedSongs = liked?.songs.orEmpty()
        if (likedSongs.isNotEmpty()) {
            LikeState.seedLiked(likedSongs.mapTo(HashSet()) { it.videoId })
        }
        val playlists = runCatching { libraryPlaylistItems() }.getOrDefault(emptyList())
        val shelves = if (playlists.isEmpty()) {
            emptyList()
        } else {
            listOf(HomeShelf(YtMusicRepository.PLAYLISTS_SHELF, playlists))
        }
        return LibraryPage(
            likedSongs = likedSongs,
            librarySongs = emptyList(),
            shelves = shelves,
            likedContinuation = liked?.continuation,
        )
    }

    suspend fun libraryPlaylistItems(): List<ShelfItem> {
        var root: JsonObject? = null
        for (browseId in listOf("FElibrary", "FEplaylist_aggregation")) {
            val candidate = runCatching { Innertube.browseYouTube(browseId) }.getOrNull() ?: continue
            val items = YouTubeWebParser.parseLibraryItems(candidate)
            if (items.isNotEmpty()) {
                root = candidate
                break
            }
        }
        val source = root ?: return emptyList()

        val out = LinkedHashMap<String, ShelfItem>()
        fun add(items: List<ShelfItem>) {
            items.forEach { item ->
                val key = item.browseId ?: item.videoId ?: "${item.title}\n${item.subtitle}"
                out.putIfAbsent(key, item)
            }
        }
        add(YouTubeWebParser.parseLibraryItems(source))

        var token = YouTubeWebParser.continuationToken(source)
        var page = 1
        while (token != null && page++ <= YtMusicRepository.MAX_PAGES) {
            rememberContinuation(token)
            val next = runCatching { Innertube.browseYouTubeContinuation(token) }.getOrNull() ?: break
            add(YouTubeWebParser.parseLibraryItems(next))
            token = YouTubeWebParser.continuationToken(next)
        }
        return out.values.toList()
    }

    fun userPlaylists(items: List<ShelfItem>): List<UserPlaylist> = items.mapNotNull { item ->
        val browseId = item.browseId ?: return@mapNotNull null
        if (!browseId.startsWith("VL")) return@mapNotNull null
        val playlistId = browseId.removePrefix("VL")
        if (playlistId.startsWith("RD")) return@mapNotNull null
        UserPlaylist(
            playlistId = playlistId,
            title = item.title,
            subtitle = item.subtitle,
            thumbnailUrl = item.thumbnailUrl,
        )
    }

    // ---- Playlists and radio ------------------------------------------------

    /** One page of a playlist (`VL…`) or a mix (`RD…`, served by `next`). */
    suspend fun playlistPage(browseId: String): YouTubeWebParser.PlaylistResult {
        val raw = browseId.removePrefix("VL")
        val result = if (raw.startsWith("RD")) {
            YouTubeWebParser.parseRadio(Innertube.nextYouTube(videoId = null, playlistId = raw))
        } else {
            val id = if (browseId.startsWith("VL")) browseId else "VL$browseId"
            YouTubeWebParser.parsePlaylist(Innertube.browseYouTube(id))
        }
        result.continuation?.let(::rememberContinuation)
        return result
    }

    suspend fun morePlaylist(token: String): YouTubeWebParser.PlaylistResult {
        val result = YouTubeWebParser.parsePlaylistContinuation(
            Innertube.browseYouTubeContinuation(token),
        )
        result.continuation?.let(::rememberContinuation)
        return result
    }

    suspend fun allSongs(browseId: String): List<Song> {
        val out = LinkedHashMap<String, Song>()
        var page = playlistPage(browseId)
        page.songs.forEach { out.putIfAbsent(it.videoId, it) }
        var pages = 1
        var token = page.continuation
        while (token != null && pages++ <= YtMusicRepository.MAX_PAGES) {
            page = runCatching { morePlaylist(token) }.getOrNull() ?: break
            page.songs.forEach { out.putIfAbsent(it.videoId, it) }
            token = page.continuation
        }
        return out.values.toList()
    }

    suspend fun radio(videoId: String): List<Song> =
        YouTubeWebParser.parseRadio(
            Innertube.nextYouTube(videoId = videoId, playlistId = "RD$videoId"),
        ).songs

    private suspend fun likedFirstPage(): YouTubeWebParser.PlaylistResult? {
        val now = System.currentTimeMillis()
        likedCache?.let { (at, page) -> if (now - at < LIKED_CACHE_MS) return page }
        return likedLock.withLock {
            likedCache?.let { (at, page) -> if (now - at < LIKED_CACHE_MS) return@withLock page }
            for (id in listOf(LIKED_MUSIC, LIKED_VIDEOS)) {
                val root = runCatching { Innertube.browseYouTube(id) }.getOrNull() ?: continue
                val page = YouTubeWebParser.parsePlaylist(root)
                if (page.songs.isEmpty()) continue
                page.continuation?.let(::rememberContinuation)
                likedCache = System.currentTimeMillis() to page
                return@withLock page
            }
            likedCache = System.currentTimeMillis() to null
            null
        }
    }

    // ---- Continuations ------------------------------------------------------

    fun isContinuation(token: String): Boolean = continuationTokens.containsKey(token)

    private fun rememberContinuation(token: String?) {
        token ?: return
        if (continuationTokens.size > 128) continuationTokens.clear()
        continuationTokens[token] = true
    }

    // ---- Covers -------------------------------------------------------------

    /**
     * Swaps video thumbnails for square album covers on the part of the feed a
     * listener is about to see. The budget is spread across shelves in page
     * order so a long feed cannot turn into a library-wide lookup, and every
     * item is returned even when no cover was found.
     */
    private suspend fun enrichCovers(shelves: List<HomeShelf>): List<HomeShelf> {
        var budget = HOME_COVER_LIMIT
        return shelves.map { shelf ->
            if (budget <= 0) return@map shelf
            val indexes = shelf.items.indices
                .filter { index ->
                    val item = shelf.items[index]
                    item.videoId != null && YouTubeCoverResolver.isVideoThumbnail(item.thumbnailUrl)
                }
                .take(minOf(SHELF_COVER_LIMIT, budget))
            if (indexes.isEmpty()) return@map shelf
            val songs = indexes.map { index ->
                val item = shelf.items[index]
                Song(
                    videoId = item.videoId.orEmpty(),
                    title = item.title,
                    artist = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                )
            }
            val byIndex = indexes
                .zip(YouTubeCoverResolver.enrich(songs, songs.size))
                .toMap()
            budget -= indexes.size
            shelf.copy(
                items = shelf.items.mapIndexed { index, item ->
                    byIndex[index]?.let { item.copy(thumbnailUrl = it.thumbnailUrl) } ?: item
                },
            )
        }
    }

    private fun shelfItem(song: Song): ShelfItem = ShelfItem(
        title = song.title,
        subtitle = song.artist,
        thumbnailUrl = song.thumbnailUrl,
        videoId = song.videoId,
        browseId = null,
    )
}
