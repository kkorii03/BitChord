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
import kotlinx.coroutines.CancellationException
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
    private const val RECENT_TITLE = YouTubeFallbackFeed.LISTEN_AGAIN_TITLE
    private const val RECENT_LIMIT = 20
    /** Only this much of a youtube.com history page is judged and shown at once. */
    private const val HISTORY_LIMIT = 30
    /** Square-cover lookups for the home feed, spread over its first shelves. */
    private const val HOME_COVER_LIMIT = 20
    private const val SHELF_COVER_LIMIT = 6
    private const val CATEGORY_PREFIX = "ytc:"
    /** The built gated feed is reused by every tab switch inside this window. */
    private const val HOME_CACHE_MS = 10L * 60 * 1000

    private val continuationTokens = ConcurrentHashMap<String, Boolean>()

    private val channelLock = Mutex()

    private val likedLock = Mutex()

    private val homeLock = Mutex()

    @Volatile
    private var channelCache: Pair<Long, List<HomeShelf>>? = null

    @Volatile
    private var likedCache: Pair<Long, YouTubeWebParser.PlaylistResult?>? = null

    @Volatile
    private var homeCache: Triple<String, Long, HomeFeed>? = null

    private const val LIKED_CACHE_MS = 5L * 60 * 1000

    // ---- Home ---------------------------------------------------------------

    /**
     * The gated Home, built once and reused by every tab switch for ten
     * minutes. The order mirrors the Music Home a Premium listener had:
     * Quick picks, Listen again, the account's mixes, Liked music, YouTube's
     * own home shelves, then the Music channel last.
     *
     * [force] is only for a pull-to-refresh: it skips the cached feed and
     * replaces it with the one just built. First loads, tab switches and
     * returning to the screen keep the cache.
     */
    suspend fun home(force: Boolean = false): HomeFeed {
        cachedHome(force = force)?.let { return it }
        return homeLock.withLock {
            cachedHome(force = force)?.let { return@withLock it }
            val feed = buildHome()
            homeCache = Triple(Innertube.sessionKey(), System.currentTimeMillis(), feed)
            feed
        }
    }

    private fun cachedHome(
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): HomeFeed? {
        val key = Innertube.sessionKey()
        return homeCache?.let { (cachedKey, at, feed) ->
            feed.takeIf {
                YouTubeFallbackFeed.shouldUseCachedHome(
                    force = force,
                    cachedKey = cachedKey,
                    sessionKey = key,
                    cachedAtMs = at,
                    nowMs = now,
                    maxAgeMs = HOME_CACHE_MS,
                )
            }
        }
    }

    private suspend fun buildHome(): HomeFeed {
        val localPlays = LocalPlayHistory.recent(HISTORY_LIMIT)
        val listenSongs = try {
            mergedHistory(RECENT_LIMIT, localPlays)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Recently played failed: ${failure.message}")
            emptyList()
        }

        val seed = YouTubeFallbackFeed.mostPlayed(localPlays)?.song
            ?: listenSongs.firstOrNull()
        val quickPicks = quickPicksShelf(seed, localPlays)
        val listenAgain = listenSongs
            .takeIf { it.isNotEmpty() }
            ?.let { musicOnlyShelf(HomeShelf(RECENT_TITLE, it.map(::shelfItem))) }
        val myMixes = mixShelf(localPlays)
        val liked = likedShelf()

        val personal = runCatching {
            YouTubeWebParser.parseHome(
                Innertube.browseYouTube("FEwhat_to_watch"),
                musicOnly = true,
            )
        }.onFailure { Log.w(TAG, "YouTube home failed: ${it.message}") }
            .getOrNull()
        val personalShelves = personal?.shelves.orEmpty()
            .map { musicOnlyShelf(it) }
        val continuation = personal?.continuation?.also(::rememberContinuation)

        val shelves = YouTubeFallbackFeed.orderedShelves(
            quickPicks = quickPicks,
            listenAgain = listenAgain,
            myMixes = myMixes,
            liked = liked,
            youtubeHome = personalShelves,
            // The Music channel's own shelves are music by construction; only
            // the mixed uploads above need per-row classification.
            musicChannel = channelShelves(),
        )
        return HomeFeed(shelves = enrichCovers(shelves), continuation = continuation)
    }

    /**
     * YouTube's own `RD<videoId>` mix of the song most played recently, with
     * anything played in the last hour removed.
     */
    private suspend fun quickPicksShelf(
        seed: Song?,
        localPlays: List<LocalPlay>,
    ): HomeShelf? {
        val videoId = seed?.videoId?.takeIf { it.isNotBlank() } ?: return null
        val recentIds = YouTubeFallbackFeed.lastHourIds(localPlays, System.currentTimeMillis())
        val songs = try {
            radio(videoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "YouTube mix for Quick picks failed: ${failure.message}")
            emptyList()
        }.filterNot { it.videoId in recentIds }
        if (songs.isEmpty()) return null
        val music = YouTubeMusicClassifier.filterMusic(songs, songs.size)
            .distinctBy { it.videoId }
            .take(YouTubeFallbackFeed.QUICK_PICKS_LIMIT)
        if (music.isEmpty()) return null
        return HomeShelf(YouTubeFallbackFeed.QUICK_PICKS_TITLE, music.map(::shelfItem))
    }

    /**
     * An RD mix card for each of the recent local history's top artists, with
     * songs filling any shortfall, capped at [YouTubeFallbackFeed.MIX_LIMIT].
     */
    private fun mixShelf(localPlays: List<LocalPlay>): HomeShelf? {
        val seeds = YouTubeFallbackFeed.mixSeeds(localPlays)
        if (seeds.isEmpty()) return null
        val items = seeds.map { seed ->
            ShelfItem(
                title = if (seed.artist.isBlank()) "${seed.title} 믹스" else "${seed.artist} 믹스",
                subtitle = "믹스",
                // The local record already holds the cover the listener saw;
                // the UI crops it square when it is a video frame.
                thumbnailUrl = seed.thumbnailUrl,
                videoId = null,
                browseId = "RD${seed.videoId}",
            )
        }
        return HomeShelf(YouTubeFallbackFeed.MY_MIXES_TITLE, items)
    }

    private suspend fun likedShelf(): HomeShelf? {
        val liked = (if (Innertube.cookie != null) likedFirstPage() else null) ?: return null
        val music = YouTubeMusicClassifier.filterMusic(liked.songs, RECENT_LIMIT)
            .distinctBy { it.videoId }
            .take(RECENT_LIMIT)
        if (music.isEmpty()) return null
        return HomeShelf(YouTubeFallbackFeed.LIKED_TITLE, music.map(::shelfItem))
    }

    /** Drops rows [YouTubeMusicClassifier] states are not music, keeping collection cards. */
    private suspend fun musicOnlyShelf(shelf: HomeShelf): HomeShelf {
        val songIndexes = shelf.items.indices.filter { shelf.items[it].videoId != null }
        if (songIndexes.isEmpty()) return shelf
        val songs = songIndexes.map { index ->
            val item = shelf.items[index]
            Song(
                videoId = item.videoId.orEmpty(),
                title = item.title,
                artist = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
        }
        val kept = YouTubeMusicClassifier.filterMusic(songs, songs.size)
            .mapTo(HashSet()) { it.videoId }
        return shelf.copy(
            items = shelf.items.filterIndexed { index, item ->
                item.videoId == null || index !in songIndexes || item.videoId in kept
            },
        )
    }

    suspend fun moreHome(token: String): HomeFeed {
        val root = runCatching { Innertube.browseYouTubeContinuation(token) }.getOrNull()
            ?: return HomeFeed(emptyList(), null)
        val page = YouTubeWebParser.parseHome(root, musicOnly = true)
        page.continuation?.let(::rememberContinuation)
        val shelves = page.shelves.map { musicOnlyShelf(it) }
            .filter { it.items.isNotEmpty() }
        return HomeFeed(enrichCovers(shelves), page.continuation)
    }

    suspend fun recentShelf(): HomeShelf? {
        cachedHome()?.shelves
            ?.firstOrNull { it.title.equals(RECENT_TITLE, ignoreCase = true) }
            ?.let { return it }
        val songs = try {
            mergedHistory(RECENT_LIMIT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Recently played failed: ${failure.message}")
            emptyList()
        }
        if (songs.isEmpty()) return null
        return HomeShelf(RECENT_TITLE, songs.map(::shelfItem))
    }

    /**
     * The account's listening history, local first.
     *
     * The device's own record is what the listener played as music, so it leads
     * the page in the order they played it. youtube.com's `FEhistory` only fills
     * what is still missing while that record is short
     * ([RecentShelf.LOCAL_ONLY_MINIMUM]), because it mixes in everything watched
     * in the YouTube app.
     */
    suspend fun history(): List<Song> = mergedHistory(HISTORY_LIMIT)

    private suspend fun mergedHistory(
        limit: Int,
        localPlays: List<LocalPlay>? = null,
    ): List<Song> {
        val local = RecentShelf.localSongs(localPlays ?: LocalPlayHistory.recent(RECENT_LIMIT))
        if (local.size >= RecentShelf.LOCAL_ONLY_MINIMUM) return local
        val remote = try {
            youtubeHistory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Local plays are still a useful history when youtube.com cannot
            // answer; only an empty page has nothing to show and should surface
            // the failure.
            if (local.isEmpty()) throw failure
            Log.w(TAG, "YouTube history unavailable: ${failure.message}")
            emptyList()
        }
        return RecentShelf.merge(local, remote, limit)
    }

    /**
     * The account's youtube.com history, music rows only.
     *
     * `FEhistory` is every video ever watched, so the parser's badges are not
     * enough on their own; [YouTubeMusicClassifier] settles the first page
     * (cheap rules first, one anonymous category lookup for the rest) and the
     * survivors get square covers. A failed lookup keeps the row.
     */
    private suspend fun youtubeHistory(): List<Song> {
        val parsed = YouTubeWebParser.parseHistory(Innertube.browseYouTube("FEhistory"))
        val music = YouTubeMusicClassifier.filterMusic(parsed, HISTORY_LIMIT)
        return YouTubeCoverResolver.enrich(music, HISTORY_LIMIT)
    }

    suspend fun quickPicks(excludeSongIds: Set<String>): List<Song> {
        val feed = home()
        val quick = feed.shelves.firstOrNull {
            it.title.equals(YouTubeFallbackFeed.QUICK_PICKS_TITLE, ignoreCase = true)
        }
        val shelves = if (quick != null) listOf(quick) else feed.shelves
        return shelves
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
            .take(YouTubeFallbackFeed.QUICK_PICKS_LIMIT)
    }

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
