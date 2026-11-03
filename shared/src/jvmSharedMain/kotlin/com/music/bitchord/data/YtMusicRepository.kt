package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.cover.YouTubeCoverResolver
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.MoodLabels
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.innertube.YouTubeWebSearch
import com.music.bitchord.ko.KoAudioSwitch
import com.music.bitchord.ko.KoCharts
import com.music.bitchord.ko.KoHomeFeed
import com.music.bitchord.ko.KoLibrary
import com.music.bitchord.ko.KoSearch
import com.music.bitchord.ko.withArtworkFallback
import com.music.bitchord.ko.KoRepoGate
import com.music.bitchord.ko.KoSongVersion
import com.music.bitchord.data.net.CounterpartSwitch
import com.music.bitchord.data.net.bestArtistCandidate
import com.music.bitchord.data.net.ProxyHealth
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.SharedLookups
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.net.SongVersionVerdictStore
import com.music.bitchord.data.net.YouTubeProxy
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.ChartsPage
import com.music.bitchord.data.model.normalizedArtistName
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.HomeSource
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.playlistMoves
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.firstArtworkOrStill
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.toSong
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongMenu
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.VersionTimeMap
import com.music.bitchord.data.model.WatchTarget
import com.music.bitchord.data.sources.TrackMatcher
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Suspend API over Innertube. Every call returns a Result so the UI can show a real error. */
object YtMusicRepository {

    private const val TAG = "BitChord"

    /** A repository call slower than this gets its own line in the log. */
    private const val SLOW_CALL_MS = 3_000L

    /** How far a rescue copy's stated runtime may be from the refused track. */
    private const val ALTERNATE_DURATION_TOLERANCE_SEC = 3

    /** Distinct AutoPlay seeds whose radio answers are kept for this run. */
    private const val RADIO_CACHE_SIZE = 32

    /** Distinct audio tracks remembered as having no video version. */
    private const val VIDEO_VERSION_MISSING_SIZE = 256
    private val moodGenreShelfCache = ConcurrentHashMap<String, List<HomeShelf>>()
    /** Battery over latency: Explore resolves at most three tile covers at once. */
    private val moodGenreArtworkLock = Semaphore(3)
    // Includes unchanged video fallbacks as well as successful matches. The
    // queue prefetcher asks before a track becomes current; remembering its
    // answer makes the eventual player switch use the exact rendition whose
    // bytes were warmed, without repeating a 10–30 second catalogue search.
    internal val audioVersionCache = ConcurrentHashMap<String, Song>()
    // Cache for video version lookups from audio tracks. A miss has its own
    // set rather than a null value: ConcurrentHashMap does not store null, so
    // the old `ConcurrentHashMap<String, Song?>` accepted the type, threw on
    // the write, and re-searched every time a track had no video version.
    private val videoVersionCache = ConcurrentHashMap<String, Song>()
    // Bounded like the other answer caches: a long run meets a lot of tracks
    // that have no video version, and remembering every one for ever would
    // only grow.
    private val videoVersionMissing =
        object : LinkedHashMap<String, Boolean>(VIDEO_VERSION_MISSING_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
                size > VIDEO_VERSION_MISSING_SIZE
        }
    // A lookup in flight, so the queue prefetcher and the player's switch
    // share one search instead of sending the same request twice at once.
    private val audioLookups = ConcurrentHashMap<String, CompletableDeferred<Song>>()
    private val videoVersionLookups = ConcurrentHashMap<String, CompletableDeferred<Song?>>()
    /** Liked Music's continuation tokens, whose later pages need the same split. */
    private val likedContinuations = ConcurrentHashMap.newKeySet<String>()

    /** Continuation tokens of pages read signed out (see [Innertube.browseMusicAnonymous]). */
    private val anonymousContinuations = ConcurrentHashMap.newKeySet<String>()

    // (from, to) -> YouTube's own alignment between two versions, kept for
    // both directions so switching back lines up without asking again.
    private val versionTimeMaps = ConcurrentHashMap<Pair<String, String>, VersionTimeMap>()

    fun cachedAudioVersion(videoId: String): Song? = audioVersionCache[videoId]
    fun cachedVideoVersion(videoId: String): Song? = videoVersionCache[videoId]

    /**
     * How a position in [fromId] maps into [toId], when YouTube Music paired
     * the two itself and said how they line up. Null for a pair found by
     * search, which still has to be measured.
     */
    fun versionTimeMap(fromId: String, toId: String): VersionTimeMap? = versionTimeMaps[fromId to toId]

    /**
     * The core personalised feed. It stays deliberately independent from the
     * optional shelves below: callers can paint this response immediately,
     * rather than making the Play page wait for every supplementary endpoint.
     *
     * FEmusic_home's own continuation token comes back, for [moreHome] —
     * signed in, it keeps paging into mood mixes and more personalised
     * shelves the same way the official app does as you scroll; signed out
     * it's empty and there's nothing more to fetch.
     *
     * [force] bypasses the gated youtube.com feed cache; only a
     * pull-to-refresh passes it. Nothing at this layer caches "home".
     */
    suspend fun home(force: Boolean = false): Result<HomeFeed> = call("home") {
        if (Innertube.cookie != null) runCatching { Innertube.ensureSessionScope() }
        val key = Innertube.sessionKey()
        val tryMusic = MusicGateFallback.shouldTryMusic(key)
        // The chart the feed's trending shelf is replaced with does not depend
        // on the feed itself. Start it beside the Home browse instead of after
        // it, so a cold start pays for the slower of the two rather than their
        // sum. A fallback or a failed browse cancels it inside the helper.
        val attempt = KoHomeFeed.loadWithChartBefore(
            startChart = { if (tryMusic) KoHomeFeed.startContentCountryChart() else null },
            load = {
                if (!tryMusic) {
                    KoHomeFeed.FallbackHome(YouTubeFallback.home(force))
                } else {
                    try {
                        // Signed even if the current verdict would read Music
                        // signed out: this re-check exists to learn the
                        // account's verdict.
                        val root = Innertube.browse("FEmusic_home", forceSigned = true)
                        KoHomeFeed.MusicHome(root, InnertubeParser.parseHome(root))
                    } catch (failure: Exception) {
                        // A working proxy is the listener's way out of the
                        // gate. If the request fails on the wire, surface that
                        // error instead of silently reading youtube.com behind
                        // their back; only the gate's own refusal still
                        // justifies the fallback there. A proxy that is down is
                        // different: the direct retry (see Innertube.postMusic)
                        // succeeded, recorded UNREACHABLE, and Home must fall
                        // back rather than show an error screen. An offline
                        // phone leaves health UNKNOWN, so the normal network
                        // error is surfaced instead.
                        if (
                            MusicGateFallback.shouldUseYouTubeFallback(
                                proxyUsable = YouTubeProxy.config.usable,
                                proxyUnreachable = ProxyHealthMonitor.state.value == ProxyHealth.UNREACHABLE,
                                gateActive = MusicGateFallback.isActive(key),
                                gateSignal = isMusicGateSignal(failure),
                            )
                        ) {
                            Log.w(TAG, "home: youtube.com fallback (music browse failed: ${failure.message})")
                            KoHomeFeed.FallbackHome(YouTubeFallback.home(force))
                        } else {
                            throw failure
                        }
                    }
                }
            },
            keepChart = {
                KoHomeFeed.shouldRenderMusicHome(MusicGateFallback.isActive(key)) &&
                    it is KoHomeFeed.MusicHome &&
                    it.shelves.any { shelf -> KoHomeFeed.TRENDING_TITLE.matches(shelf.title) }
            },
        )
        when (val loaded = attempt.first) {
            is KoHomeFeed.MusicHome -> {
                // A browse that returns while the verdict still reads GATED is
                // the Premium upsell, not the listener's feed. The old path
                // fell through to the youtube.com fallback here; keep that
                // routing even though the chart now runs beside the browse.
                if (!KoHomeFeed.shouldRenderMusicHome(MusicGateFallback.isActive(key))) {
                    Log.d(
                        TAG,
                        "home: youtube.com fallback (gate ${MusicGateFallback.state(key)})",
                    )
                    return@call YouTubeFallback.home(force)
                }
                Log.d(TAG, "home: music.youtube.com (gate ${MusicGateFallback.state(key)})")
                val chart = attempt.second ?: Innertube.contentCountry()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(KoHomeFeed::cachedContentCountryChart)
                HomeFeed(
                    KoHomeFeed.withContentCountryChart(loaded.shelves, chart),
                    InnertubeParser.continuationToken(loaded.root),
                )
            }
            is KoHomeFeed.FallbackHome -> {
                Log.d(
                    TAG,
                    "home: youtube.com fallback (gate ${MusicGateFallback.state(key)})",
                )
                loaded.feed
            }
        }
    }

    internal fun shouldRenderMusicHome(gateActive: Boolean): Boolean =
        KoHomeFeed.shouldRenderMusicHome(gateActive) // [KO] home-chart

    internal suspend fun <T> loadWithChartBefore(
        startChart: () -> SharedLookups.Lookup<HomeShelf?>?,
        load: suspend () -> T,
        keepChart: (T) -> Boolean = { true },
    ): Pair<T, HomeShelf?> =
        KoHomeFeed.loadWithChartBefore(startChart, load, keepChart) // [KO] home-chart

    /** Recently played is rendered independently, at the top of the Play page. */
    suspend fun homeRecentlyPlayed(): Result<HomeShelf?> = call("home:recent") {
        if (ensureGateKnown()) {
            // The gated feed already places Listen again in its fixed order.
            // Publishing it separately would prepend a duplicate and leave
            // Quick picks behind it, so the feed owns that shelf here.
            Log.i(TAG, "recents: gated feed owns Recents")
            null
        } else {
            Log.i(TAG, "recents: music")
            recentlyPlayed()
        }
    }

    /** Extra Play shelves are independently fetchable so each can appear as soon as it arrives. */
    suspend fun homeSupplement(browseId: String): Result<List<HomeShelf>> = call("home:$browseId") {
        if (fallbackActive()) {
            // The gated feed already ends with the Music channel and owns the
            // whole shelf order; a separately published supplement could land
            // before it and put a channel shelf above Quick picks.
            return@call emptyList()
        }
        val shelves = KoHomeFeed.contentCountryCharts(shelvesOf(browseId)) // [KO] home-chart
        if (browseId == "FEmusic_explore") KoHomeFeed.contentCountrySongs(shelves) else shelves
    }

    val HOME_SUPPLEMENT_BROWSE_IDS = listOf(
        "FEmusic_new_releases",
        "FEmusic_explore",
    )

    /**
     * More Home shelves past [home]'s first page, following FEmusic_home's
     * own continuation — the lever the official app pulls as you scroll
     * rather than a fixed one-shot page. "Recently played" and
     * FEmusic_new_releases are one-shot and don't participate.
     */
    suspend fun moreHome(token: String): Result<HomeFeed> = call("home:more") {
        if (fallbackActive()) {
            return@call if (YouTubeFallback.isContinuation(token)) {
                YouTubeFallback.moreHome(token)
            } else {
                HomeFeed(emptyList(), null, HomeSource.FALLBACK)
            }
        }
        val response = Innertube.browseContinuation(token)
        HomeFeed(
            shelves = KoHomeFeed.contentCountryCharts(InnertubeParser.parseHomeContinuation(response)), // [KO] home-chart
            continuation = InnertubeParser.continuationToken(response),
        )
    }

    /**
     * The lead shelf: the device's own plays first, then the account's
     * listening history.
     *
     * The local record is what this listener just heard here, so it is the
     * authority for the top of the shelf and is already newest-first. The
     * account's history follows it, de-duplicated, for listening in other
     * clients — and because YouTube's own "Listen again" ranks by how *often*
     * something was played rather than how *recently*, so it keeps leading
     * with last month's favourites after a change of mood. YouTube's own shelf
     * stays below, where its ranking is a feature.
     *
     * The remote half is signed-in only; the local half works for a guest.
     */
    private suspend fun recentlyPlayed(): HomeShelf? {
        val local = RecentShelf.localSongs(LocalPlayHistory.recent(RECENT_LIMIT))
        val ytSongs = if (Innertube.cookie != null && local.size < RecentShelf.LOCAL_ONLY_MINIMUM) {
            runCatching { fetchHistory() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val allSongs = RecentShelf.merge(local, ytSongs, RECENT_LIMIT)
        Log.i(
            TAG,
            "recents: music shelf local=${local.size} remote=${ytSongs.size} " +
                "top=${allSongs.firstOrNull()?.title.orEmpty()}",
        )
        if (allSongs.isEmpty()) return null
        return KoHomeFeed.recentsShelf(allSongs) // [KO] home-recents
    }

    suspend fun localRecentShelf(previous: List<ShelfItem> = emptyList()): HomeShelf? =
        KoHomeFeed.localRecentShelf(previous) // [KO] home-recents

    /**
     * The raw fetch behind both [recentlyPlayed] and [history]: the account's
     * listening history, newest first, one row per play collapsed to one row
     * per track.
     *
     * A track played three times today is three rows in the feed — what that
     * dedupe costs is the times, which is fine for "what you have been
     * listening to" but would matter for a log. YouTube's own page groups them
     * under Today and Yesterday headings that the shelf parser doesn't carry
     * through either.
     */
    private suspend fun fetchHistory(): List<Song> = historyLock.withLock {
        val cookie = Innertube.cookie
        // Recents and Top Picks both want the history the moment Home opens;
        // whichever asks second waits for the first and shares its answer
        // instead of sending the same request again.
        historyMemo?.takeIf { it.cookie == cookie && System.currentTimeMillis() - it.at < HISTORY_SHARE_MS }
            ?.let { return@withLock it.songs }
        InnertubeParser.collectSongsDeep(Innertube.browse(HISTORY)).distinctBy { it.videoId }
            .map(StoredSongDisplay::clean) // [KO] history-titles: kept from 1.8, lost in the 1.9 merge
            .also { historyMemo = HistoryMemo(cookie, System.currentTimeMillis(), it) }
    }

    private val historyLock = Mutex()

    /** Two requests for the history this close together are the same request. */
    private const val HISTORY_SHARE_MS = 5_000L

    /**
     * The account's listening history, in the order YouTube Music keeps it.
     *
     * The same feed [recentlyPlayed] reads, without the truncation: that one is
     * a shelf on the home page and stops at [RECENT_LIMIT] so it stays a shelf,
     * whereas this is the page you open when twenty is not enough.
     */
    suspend fun history(): Result<List<Song>> = call("history") {
        if (ensureGateKnown()) YouTubeFallback.history() else fetchHistory()
    }

    /** Account listening history for the Recents feed, the device's own plays first. */
    suspend fun recents(): Result<List<Song>> = call("recents") {
        if (ensureGateKnown()) return@call YouTubeFallback.history()
        val local = RecentShelf.localSongs(LocalPlayHistory.recent(RECENT_LIMIT))
        val remote = if (Innertube.cookie != null && local.size < RecentShelf.LOCAL_ONLY_MINIMUM) {
            runCatching { fetchHistory() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        RecentShelf.merge(local, remote, RECENT_LIMIT)
    }

    /** Removes a track from the account's listening history via its feedback token. */
    suspend fun removeFromHistory(token: String): Result<Unit> = call("removeFromHistory") {
        Innertube.sendFeedback(token)
    }

    /** Invalidates the in-memory history cache so the next load fetches fresh data. */
    fun invalidateHistoryCache() {
        historyMemo = null
    }

    /**
     * Recommended discovery tracks for Quick Picks, strictly excluding listening history and recents.
     */
    suspend fun quickPicks(excludeSongIds: Set<String> = emptySet()): Result<List<Song>> = call("quickPicks") {
        if (ensureGateKnown()) return@call YouTubeFallback.quickPicks(excludeSongIds)
        val homeRaw = runCatching { Innertube.browse("FEmusic_home") }.getOrNull()
        val shelves = if (homeRaw != null) InnertubeParser.parseHome(homeRaw) else emptyList()

        // 1. Direct "Quick picks" or "Mix" or "Recommendations" shelf
        val qpShelf = shelves.firstOrNull {
            it.title.contains("quick", ignoreCase = true) ||
                it.title.contains("pick", ignoreCase = true) ||
                it.title.contains("mix", ignoreCase = true) ||
                it.title.contains("recommend", ignoreCase = true)
        }
        val qpSongs = qpShelf?.items?.mapNotNull { item ->
            item.toSong()?.takeUnless { it.videoId in excludeSongIds }
        }.orEmpty()
        if (qpSongs.isNotEmpty()) return@call qpSongs

        // 2. All shelves on Home (excluding shelves mentioning recent/history, and skipping top shelf if multiple exist)
        val candidateShelves = if (shelves.size > 1) {
            shelves.drop(1).filterNot {
                it.title.contains("recent", ignoreCase = true) ||
                    it.title.contains("history", ignoreCase = true) ||
                    it.title.contains("listen again", ignoreCase = true)
            }
        } else shelves

        val homeShelfSongs = candidateShelves.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                item.toSong()?.takeUnless { it.videoId in excludeSongIds }
            }
        }.distinctBy { it.videoId }

        if (homeShelfSongs.isNotEmpty()) return@call homeShelfSongs

        // 3. Any shelf items on Home that have videoId
        val allHomeTrackSongs = shelves.flatMap { shelf ->
            shelf.items.mapNotNull { item -> item.toSong() }
        }.distinctBy { it.videoId }

        val uniqueAllHome = allHomeTrackSongs.filterNot { it.videoId in excludeSongIds }
        if (uniqueAllHome.isNotEmpty()) return@call uniqueAllHome

        // 4. Explore / New releases
        val explore = runCatching { shelvesOf("FEmusic_new_releases") }.getOrDefault(emptyList())
        val exploreSongs = explore.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                item.toSong()?.takeUnless { it.videoId in excludeSongIds }
            }
        }.distinctBy { it.videoId }

        if (exploreSongs.isNotEmpty()) return@call exploreSongs

        // 5. Fallback if everything was filtered out
        allHomeTrackSongs.ifEmpty { exploreSongs }
    }

    private const val HISTORY = "FEmusic_history"
    private const val RECENT_TITLE = "Recents"

    /** Enough to scroll through, short of turning the shelf into the history page. */
    private const val RECENT_LIMIT = 20

    private suspend fun shelvesOf(browseId: String): List<HomeShelf> =
        InnertubeParser.parseHome(Innertube.browse(browseId))

    /** The server-defined mood and genre categories used by Explore. */
    suspend fun moodAndGenres(): Result<List<MoodGenreSection>> = call("moods-and-genres") {
        if (ensureGateKnown()) return@call YouTubeFallback.exploreSections()
        MoodLabels.tidy(
            InnertubeParser.parseMoodAndGenres(Innertube.browse("FEmusic_moods_and_genres")),
            Innertube.currentLanguage,
        )
    }

    /** The charts page for [country] (a code the page offered), or the content country's. */
    suspend fun charts(country: String?): Result<ChartsPage> =
        KoCharts.charts(country) // [KO] charts

    /**
     * The playlist shelves behind one mood/genre category. This result also
     * supplies its tile artwork, so caching it avoids a second request when a
     * user taps a category whose cover has already appeared.
     */
    suspend fun moodGenreShelves(browseId: String, params: String?): Result<List<HomeShelf>> {
        val gated = withContext(Dispatchers.IO) { fallbackActive() }
        if (gated || browseId.startsWith("ytc:") || browseId.startsWith("ytq:")) {
            // This used to hand the fallback's result straight back inside
            // Result.success. A failure there escaped the caller's fold, so
            // openMoodGenre never got an Error and its page kept the skeleton
            // for ever. call() catches it, logs the reason, and still keeps
            // cancellation out of the Result.
            return call("mood-genre:$browseId (fallback)") {
                YouTubeFallback.categoryShelves(browseId)
            }
        }
        val key = "$browseId:${params.orEmpty()}"
        moodGenreShelfCache[key]?.let { return Result.success(it) }
        return call("mood-genre:$browseId") {
            InnertubeParser.parseHome(Innertube.browse(browseId, params))
        }.also { result -> result.getOrNull()?.let { moodGenreShelfCache.putIfAbsent(key, it) } }
    }

    /**
     * A category card borrows the first real cover from the playlists it opens.
     * Only a tile the UI has made visible calls this, and the limiter keeps the
     * three-at-a-time budget even when a wide grid composes several at once.
     */
    suspend fun moodGenreArtwork(browseId: String, params: String?): Result<String?> =
        moodGenreArtworkLock.withPermit {
            if (browseId.startsWith(ExploreCategories.BROWSE_PREFIX)) {
                return@withPermit call("mood-genre-art:$browseId (fallback)") {
                    YouTubeFallback.categoryArtwork(browseId)
                }
            }
            moodGenreShelves(browseId, params).map { shelves ->
                shelves.asSequence().flatMap { it.items.asSequence() }
                    .mapNotNull(ShelfItem::thumbnailUrl)
                    .firstOrNull()
            }
        }

    /** One page of a search response, with the token for its next page if present. */
    data class SearchPage(
        val rows: List<SearchResult>,
        val continuation: String?,
    )

    /**
     * Fetches only the first search page. Publishing it immediately keeps the
     * search responsive; the UI asks [searchContinuation] for later pages as
     * the listener reaches the end of the list.
     *
     * Account-free like every search: [Innertube.search] deliberately does not
     * send the session, so a query the listener or the app runs never lands in
     * their YouTube search history.
     */
    suspend fun searchPage(
        query: String,
        filter: SearchFilter,
        anonymous: Boolean = true,
        kind: SearchKind = SearchKind.TYPED,
    ): Result<SearchPage> =
        call("search:${filter.name}") {
            val music = runCatching {
                InnertubeParser.parseSearchPage(
                    Innertube.search(query, filter.params, anonymous = anonymous),
                    includeVideos = filter == SearchFilter.VIDEOS,
                    includeEpisodes = filter == SearchFilter.EPISODES,
                ).let { page ->
                    SearchPage(page.rows.distinctBy { it.identityKey() }, page.continuation)
                }
            }
            // From a Korean IP without Premium the Songs filter answers with no
            // rows (and the gated session may refuse outright); the same tracks
            // come back from an anonymous youtube.com search. See YouTubeWebSearch.
            val wantsTracks = filter == SearchFilter.SONGS || filter == SearchFilter.VIDEOS
            val page = music.getOrNull()
            if (wantsTracks && (page == null || page.rows.isEmpty())) {
                Log.w(
                    TAG,
                    "search: youtube.com fallback length=${query.length} " +
                        "(music ${if (page == null) "failed" else "returned no rows"})",
                )
                val fallback = YouTubeWebSearch.songs(query, kind)
                val songs = fallback.getOrNull().orEmpty()
                if (filter == SearchFilter.SONGS) {
                    // The Songs filter is what Korea's catalogue refuses. Its
                    // album search still answers, so map the uploads to their
                    // catalogue tracks and put the matching album tracks first;
                    // whatever stays unmapped keeps the cover resolver's
                    // cleaned youtube.com metadata.
                    val catalogue = CatalogSongMapper.forSearch(query, songs)
                    if (catalogue.isNotEmpty()) {
                        val shown = YouTubeCoverResolver.enrich(
                            catalogue,
                            CatalogSongMapper.MAX_ROWS_PER_PAGE,
                        )
                        return@call SearchPage(
                            shown.map { SearchResult.Track(it) }.distinctBy { it.identityKey() },
                            null,
                        )
                    }
                } else if (songs.isNotEmpty()) {
                    return@call SearchPage(songs.map { SearchResult.Track(it) }.distinctBy { it.identityKey() }, null)
                }
                // Music had no rows of its own and the fallback never
                // answered. Returning the empty Music page here would show
                // "no results" for a broken connection; let the failure reach
                // the screen so its retry can run.
                fallbackFailureWhenMusicHasNoRows(music, fallback)?.let { throw it }
            }
            music.getOrThrow()
        }

    /** Fetches the next page of a search only when the result list needs it. */
    suspend fun searchContinuation(
        token: String,
        filter: SearchFilter,
        kind: SearchKind = SearchKind.TYPED,
    ): Result<SearchPage> = call("search:continuation") {
        InnertubeParser.parseSearchPage(
            Innertube.searchContinuation(token, kind),
            includeVideos = filter == SearchFilter.VIDEOS,
            includeEpisodes = filter == SearchFilter.EPISODES,
        ).let { page ->
            SearchPage(page.rows.distinctBy { it.identityKey() }, page.continuation)
        }
    }

    /**
     * Compatibility helper for callers that need candidates but not a scrolling
     * result screen. Those callers need the first, most relevant page only.
     */
    suspend fun search(
        query: String,
        filter: SearchFilter,
        kind: SearchKind = SearchKind.TYPED,
    ): Result<List<SearchResult>> =
        searchPage(query, filter, kind = kind).map { it.rows }

    private fun SearchResult.identityKey(): String = when (this) {
        is SearchResult.TopTrack -> "v:${song.videoId}"
        is SearchResult.Track -> "v:${song.videoId}"
        is SearchResult.Browse -> "b:${item.browseId}"
    }

    internal fun fallbackFailureWhenMusicHasNoRows(
        music: Result<SearchPage>,
        fallback: Result<List<Song>>,
    ): Throwable? = KoSearch.fallbackFailureWhenMusicHasNoRows(music, fallback) // [KO] search-fallback

    /**
     * What YouTube Music would suggest completing [input] to, for the search
     * field's typeahead. Unfiltered on purpose: a suggestion is a query, and
     * which tab it is then run against is the user's to pick afterwards.
     */
    suspend fun searchSuggestions(input: String): Result<List<String>> =
        call("suggest") {
            InnertubeParser.parseSearchSuggestions(Innertube.searchSuggestions(input))
        }

    /**
     * Live media results for the typeahead phase — a lightweight search that
     * returns tracks, artists, and albums so the dropdown can show playable
     * cards alongside text completions. Uses the ALL filter to get mixed
     * results quickly; callers may want to limit how many they display.
     *
     * Deliberately unauthenticated: [Innertube.searchTypeahead] sends no
     * session at all, so YouTube Music does not log each debounced keystroke
     * to the account's server-side search history. Confirmed searches (Enter /
     * IME Search / tapping a suggestion) go through [searchPage], which is
     * anonymous for the same reason.
     */
    suspend fun searchTypeahead(input: String): Result<SearchPage> =
        call("typeahead:length=${input.length}") {
            InnertubeParser.parseSearchPage(
                Innertube.searchTypeahead(input),
                includeVideos = false,
                includeEpisodes = false,
            ).let { page ->
                SearchPage(page.rows.distinctBy { it.identityKey() }, page.continuation)
            }
        }

    /**
     * The catalogue (audio-only) release of a music-video upload: YouTube
     * Music's own pairing when it has one (see [officialVersion]), otherwise
     * a search on the title and artist, taking the closest song match.
     * Called before a video-tagged [Song] ever reaches the queue, so
     * playback, the mini player/notification, and YouTube's own history all
     * see the audio track — never the video upload's title, art or id.
     *
     * Matched through [TrackMatcher] rather than a bare title compare, for
     * the same reason [SourceResolver][com.music.bitchord.data.sources.SourceResolver]
     * does: a query for a niche title can come back with nothing that is
     * really the recording, and taking the first row regardless was landing
     * on a same-language, wrong-song hit — a Telugu folk video resolving to
     * an unrelated devotional track was reported from exactly this path.
     * [TrackMatcher.best] returning null is a normal answer, not a failure to
     * work around.
     *
     * Returns [song] unchanged when it isn't a video (and its type is known),
     * or when nothing better turns up — playing the video's own audio track
     * beats guessing at a substitute. This is deliberately an explicit action
     * from the player, never part of normal queueing or playback.
     *
     * [search] already drops video rows from its results (see
     * [InnertubeParser.parseSearch]), so every candidate here is audio-only
     * without a second check.
     */
    suspend fun resolveAudio(song: Song): Song {
        // A preview row whose type has not landed yet may be a video; the
        // policy already says to ask about it, so this guard must use the same
        // rule instead of trusting the default `isVideo = false`.
        if (!SongVersionPolicy.wantsSongVersion(song.isVideo, song.typeUnknown)) return song
        if (song.title.isBlank() || song.artist.isBlank()) {
            Log.w(TAG, "audio switch skipped: ${song.videoId} has empty title/artist")
            return song
        }
        // A cover's "audio version" is the song it covers — someone else's
        // recording. A cover plays as itself.
        if (CoverTitles.isCover(song)) return song
        audioVersionCache[song.videoId]?.let { return it }
        val mine = CompletableDeferred<Song>()
        audioLookups.putIfAbsent(song.videoId, mine)?.let {
            return try {
                it.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
        try {
            val resolved = resolveAudioUncached(song)
            mine.complete(resolved)
            return resolved
        } catch (failure: Throwable) {
            mine.completeExceptionally(failure)
            throw failure
        } finally {
            audioLookups.remove(song.videoId, mine)
        }
    }

    private suspend fun resolveAudioUncached(song: Song): Song =
        KoAudioSwitch.resolveAudioUncached(song) // [KO] audio-switch

    suspend fun findAlternateRecording(song: Song, excluding: Set<String> = emptySet()): Song? =
        KoSongVersion.findAlternateRecording(song, excluding) // [KO] song-version

    /**
     * The inverse of [resolveAudio]: finds the video/music-video version of an
     * audio-only track, used when switching back from audio to video.
     * Resolves an audio-only track to its video version.
     *
     * This is the inverse of [resolveAudio] - it finds the music video
     * for a catalogue track. Returns null when no video version is found.
     */
    suspend fun counterpartAudio(videoId: String): Song? =
        KoSongVersion.counterpartAudio(videoId) // [KO] song-version

    suspend fun officialVideo(videoId: String): String? =
        KoSongVersion.officialVideo(videoId) // [KO] song-version

    suspend fun resolveVideo(song: Song): Song? {
        if (song.isVideo) return null
        videoVersionCache[song.videoId]?.let { return it }
        officialVersion(song, wantAudio = false)?.let { video ->
            videoVersionCache[song.videoId] = video
            return video
        }
        val target = TrackMatcher.targetOf(song)
        for (query in TrackMatcher.queries(target)) {
            val candidates = search(query, SearchFilter.VIDEOS)
                .getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            TrackMatcher.best(candidates, target)?.let { match ->
                Log.d(TAG, "video switch: '${song.title}' -> '${match.title}' ($query)")
                videoVersionCache[song.videoId] = match
                return match
            }
        }
        Log.w(TAG, "video switch: no video match for '${song.title}' by '${song.artist}'")
        synchronized(videoVersionMissing) { videoVersionMissing[song.videoId] = true }
        return null
    }

    fun cachedSongVersionFor(song: Song): Song? =
        KoSongVersion.cachedSongVersionFor(song) // [KO] song-version

    fun isSongVersionMissing(videoId: String): Boolean =
        KoSongVersion.isSongVersionMissing(videoId) // [KO] song-version

    fun isSongVersionRetrySuggested(videoId: String): Boolean =
        KoSongVersion.isSongVersionRetrySuggested(videoId) // [KO] song-version

    suspend fun songVersion(
        song: Song,
        beforeAudio: Boolean = false,
        retryTimedOut: Boolean = false,
    ): Song? = KoSongVersion.songVersion(song, beforeAudio, retryTimedOut) // [KO] song-version

    suspend fun resolveQueueAhead(
        songs: List<Song>,
        startIndex: Int,
        ahead: Int = 4,
        timeoutMs: Long = SongVersionPolicy.BEFORE_AUDIO_TIMEOUT_MS,
    ): List<Song> =
        KoSongVersion.resolveQueueAhead(songs, startIndex, ahead, timeoutMs) // [KO] song-version

    suspend fun officialVideoBySearch(song: Song): String? =
        KoSongVersion.officialVideoBySearch(song) // [KO] song-version

    /**
     * The other version of [song] as YouTube Music pairs it — the pairing
     * behind its own Song/Video switch — or null when it offers none. Asked
     * before any search, because this is an answer rather than a guess: the
     * search can land on a same-titled cover or a different release, this
     * cannot. Its time map, when there is one, is kept for the service, so
     * the swap seeks straight to the matching moment instead of measuring it.
     *
     * Signed out, YouTube pairs nothing, so the request is not made at all.
     */
    private suspend fun officialVersion(song: Song, wantAudio: Boolean): Song? {
        if (Innertube.cookie == null) return null
        val pair = try {
            InnertubeParser.parseVersionPair(Innertube.next(song.videoId), song.videoId, wantAudio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "version pair lookup failed for '${song.title}': ${e.message}")
            null
        } ?: run {
            Log.d(TAG, "version pair: YouTube pairs nothing with '${song.title}'")
            return null
        }
        pair.timeMap?.let { map ->
            versionTimeMaps[song.videoId to pair.song.videoId] = map
            versionTimeMaps[pair.song.videoId to song.videoId] = map.inverted()
        }
        Log.d(
            TAG,
            "version pair: '${song.title}' -> '${pair.song.title}' (${pair.song.videoId}), " +
                "segments=${pair.timeMap?.segments?.size ?: 0}",
        )
        return pair.song
    }

    /** Signed-in profile for the settings header. Null when signed out. */
    suspend fun account(): Result<Account> = call("account") {
        // Music first, then youtube.com's own menu, then its account switcher:
        // a gated Korean session (no Premium) is answered 404 by Music's
        // account_menu even though the login is valid (S24 log, ko-53).
        runCatching { InnertubeParser.parseAccount(Innertube.accountMenu()) }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "account: music menu refused (${it.message?.take(60)}); trying youtube.com")
            }
            .getOrNull()
            ?: runCatching { InnertubeParser.parseAccount(Innertube.accountMenuYouTube()) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "account: youtube.com menu refused (${it.message?.take(60)})")
                }
                .getOrNull()
            ?: runCatching {
                InnertubeParser.parseAccountChannels(Innertube.accountSwitcher())
                    .let { list -> list.firstOrNull { it.activeOnWeb } ?: list.firstOrNull() }
                    ?.let {
                        // The switcher's subtitle is usually an @handle, not
                        // an email; the email field is used to dedupe accounts,
                        // so the handle is kept beside it rather than in it.
                        val email = it.subtitle.takeIf { s -> '@' in s && !s.startsWith("@") }.orEmpty()
                        Account(
                            name = it.name,
                            email = email,
                            thumbnailUrl = it.thumbnailUrl,
                            handle = it.subtitle.takeIf { s -> s.startsWith("@") },
                        )
                    }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "account: switcher refused (${it.message?.take(60)})")
            }.getOrNull()
            ?: error("No account details")
    }

    /**
     * The channels this login can act as — its own, plus any brand channels.
     *
     * Two endpoints are asked in turn because either can come back with an
     * envelope holding no `accountItem` at all, and the two do not fail
     * together: `accounts_list` is the first-party route and the switcher is
     * what youtube.com's own avatar menu uses. An empty list from the first is
     * not an answer, it is a shape this parser didn't recognise, so it is
     * treated the same as a failure and the other route is tried.
     */
    suspend fun accountChannels(): Result<List<AccountChannel>> = call("channels") {
        val viaInnertube = runCatching {
            InnertubeParser.parseAccountChannels(Innertube.accountsList())
        }.onFailure { Log.w(TAG, "accounts_list unavailable: ${it.message}") }
            .getOrNull()
            .orEmpty()
        if (viaInnertube.isNotEmpty()) return@call viaInnertube
        InnertubeParser.parseAccountChannels(Innertube.accountSwitcher())
    }

    /**
     * The whole library in one shot — requires a signed-in session.
     *
     * YouTube Music has no single "my library" feed: Liked Music is the `LM`
     * auto-playlist, the songs added to the library are a separate feed, and
     * every saved collection has its own browse id. They're fetched in
     * parallel and a feed that fails or is simply empty (a fresh account has
     * no saved albums) is dropped rather than failing the whole page.
     */
    suspend fun library(): Result<LibraryPage> = call("library") {
        if (ensureGateKnown()) return@call YouTubeFallback.library()
        coroutineScope {
            // Liked Music is read one page at a time. The first page is what
            // the Library tab needs to fill and is published straight away;
            // the rest of the collection is synced into LikeState in the
            // background, so a liked track past the first page still reads as
            // liked without holding this page open behind the whole list —
            // see [syncLikedMusic] and MainViewModel's fetchLibrary.
            val liked = async { browseSongs(LIKED_MUSIC).getOrNull() }
            val added = async { runCatching { songsPaged(LIBRARY_SONGS) }.getOrDefault(emptyList()) }
            val shelves = LIBRARY_FEEDS
                .map { (title, browseId) ->
                    async {
                        HomeShelf(title, runCatching { libraryItemsPaged(browseId) }.getOrDefault(emptyList()))
                    }
                }
                .awaitAll()
                .filter { it.items.isNotEmpty() }

            val likedPage = liked.await()
            val likedSongs = likedPage?.songs.orEmpty()
            // Hidden rows are still liked: they must keep their heart and must
            // not reappear in the library "Songs" section as if unliked.
            val likedIds = (likedSongs + likedPage?.hiddenSongs.orEmpty())
                .mapTo(HashSet()) { it.videoId }
            LikeState.seedLiked(likedIds)
            LibraryPage(
                likedSongs = likedSongs,
                // Thumbs-up'd tracks are also in the library feed; only what
                // the "Liked Music" list doesn't already cover is worth a
                // second section.
                librarySongs = added.await().filterNot { it.videoId in likedIds },
                shelves = shelves,
                likedContinuation = likedPage?.continuation,
            )
        }
    }

    /**
     * Finishes syncing the liked collection into [LikeState], following
     * [firstToken] page by page until the feed runs dry.
     *
     * Unlike [songsPaged]'s [MAX_PAGES], this is deliberately unbounded: it
     * seeds only video ids (not the full [Song]s), so syncing a long Liked
     * Music library stays cheap, and stopping at a page cap would leave every
     * liked track past that page reading as "not liked" — the very symptom
     * #219 reported.
     *
     * [loadNext] is injectable so the pagination loop is unit-testable; the
     * default reads through [moreSongs] and stops on a failed page rather
     * than surfacing an error for a background sync. Cancellation is
     * cooperative and the caller (a ViewModel scope in the app) decides when
     * this outlives its usefulness.
     *
     * Guards only against a token repeating — a page pointing back at one
     * already read, which would otherwise spin forever — rather than a page
     * count, since a genuinely large library is exactly what this exists to
     * keep reading.
     */
    suspend fun syncLikedMusic(
        firstToken: String?,
        loadNext: suspend (String) -> SongPage? = { moreSongs(it).getOrNull() },
    ) {
        if (firstToken == null) return
        val seen = HashSet<String>()
        var next: String? = firstToken
        while (next != null && seen.add(next)) {
            val page = loadNext(next) ?: return
            LikeState.seedLiked(
                (page.songs + page.hiddenSongs).mapTo(HashSet()) { it.videoId },
            )
            next = page.continuation
        }
    }

    /**
     * What YouTube Music would play on after [videoId]. Feeds AutoPlay; the
     * seed track itself comes back first, so callers filter what they have.
     * [preferVideo] takes each song YouTube pairs with its music video as the
     * video — a station played on from a video stays videos.
     *
     * Answers are kept for the run, misses included only as long as the cache
     * slot lives. AutoPlay tops up from the same seed across several track
     * changes, and every one of those used to be another `next` round trip for
     * an answer the station had already given.
     */
    suspend fun radio(videoId: String, preferVideo: Boolean = false): Result<List<Song>> {
        val key = "$videoId:$preferVideo"
        synchronized(radioCache) { radioCache[key] }?.let { return Result.success(it) }
        return call("radio:$key") {
            // [KO] korea-gate: behind the music.youtube.com gate `next` answers
            // with the seed alone, which left AutoPlay empty on every track.
            if (ensureGateKnown()) return@call YouTubeFallback.radio(videoId, preferVideo)
            InnertubeParser.parseWatchQueue(Innertube.next(videoId), preferVideo = preferVideo)
        }.onSuccess { songs ->
            synchronized(radioCache) { radioCache[key] = songs }
        }
    }

    /** Radio answers by seed id, least recently asked first out; a bounded LRU. */
    private val radioCache = object : LinkedHashMap<String, List<Song>>(RADIO_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>) =
            size > RADIO_CACHE_SIZE
    }

    /**
     * The page of the artist named [name], or null when there is none.
     *
     * YouTube does not link every credited artist. On a real two-artist track
     * the byline and even the album header linked only the first name, so
     * there is no channel behind the second name anywhere in the response. A
     * name is all that is left, and the first search hit for one is not it: ask
     * for `2115` and the results are White 2115, Bedoes 2115, Blacha 2115 and
     * Kuqe 2115, four different people.
     *
     * A hit is accepted only when its own title is the name asked for, or the
     * name followed by a parenthetical alias (`IVE (아이브)`). Among those,
     * [bestArtistCandidate] takes the one with the largest audience the search
     * row states, because the exact spelling can belong to a tiny channel while
     * the artist's page carries an alias. Names that merely contain the query
     * are still refused: `2115` opens nobody rather than White 2115, and
     * `Monday Waxie` still finds its page.
     */
    suspend fun findArtistPageId(name: String): Result<String?> =
        call("artistByName:length=${name.length}") {
            if (normalizedArtistName(name).isEmpty()) return@call null
            val filter = SearchFilter.ARTISTS
            val candidates = InnertubeParser.parseSearchPage(
                Innertube.search(name, filter.params),
            ).rows
                .filterIsInstance<SearchResult.Browse>()
                .map { it.item }
            bestArtistCandidate(candidates, name)?.browseId
        }

    /**
     * The artist and album pages a track links out to.
     *
     * Search rows carry them, but home cards and anything already sitting in a
     * queue often don't — and the credits in the player have to lead somewhere
     * either way. A track's own watch queue entry always names both.
     */
    suspend fun trackLinks(videoId: String): Result<Song> = call("links:$videoId") {
        InnertubeParser.parseWatchQueue(Innertube.next(videoId))
            .firstOrNull { it.videoId == videoId }
            ?: error("no watch entry for $videoId")
    }

    /**
     * One page of a browse feed's tracks, and the token for the page after
     * it — null once there is nothing more. [suggested] is only ever
     * non-empty for a playlist page — see [InnertubeParser.parsePlaylistShelf].
     */
    data class SongPage(
        val songs: List<Song>,
        val continuation: String?,
        val suggested: List<Song> = emptyList(),
        /**
         * Liked Music's rows that are videos, not songs. Kept off [songs] so
         * the page shows music by default, but carried for its reveal row.
         */
        val hiddenSongs: List<Song> = emptyList(),
        /**
         * Whether the release this page describes is in the library. Only the
         * first page can answer — a continuation carries rows and nothing else
         * — so it is null from [moreSongs] and must not overwrite what
         * [browseSongs] already established.
         */
        val library: LibraryState? = null,
        /**
         * Whether this page's playlist is one the account made rather than one
         * it saved — see [InnertubeParser.parsePlaylistOwned]. Null for an
         * album, a continuation, or a page that doesn't say.
         */
        val owned: Boolean? = null,
        /**
         * What the page calls itself — only needed by callers that opened it
         * with nothing but a browse id, i.e. a tapped link. Null on a
         * continuation, which carries rows and no header.
         */
        val header: InnertubeParser.BrowseHeader? = null,
        /**
         * The editorial blurb YouTube Music writes for the release, when it
         * has one — see [InnertubeParser.parseDescription]. Null from a
         * continuation, same as [header].
         */
        val description: String? = null,
    )

    /**
     * The first page of an album/playlist's tracks, and nothing more.
     *
     * Deliberately not the whole list. Following every continuation before
     * returning meant a long playlist spent up to ten round trips showing a
     * spinner, when every row needed to fill the first screenful was in the
     * first response. The rest arrives behind a page that is by then already
     * being read — see [moreSongs].
     */
    suspend fun browseSongs(browseId: String): Result<SongPage> = call("browse:$browseId") {
        if (isYouTubePlaylistId(browseId) && ensureGateKnown()) {
            val result = YouTubeFallback.playlistPage(browseId)
            val songs = result.songs.map(StoredSongDisplay::clean)
            return@call SongPage(
                songs = songs,
                continuation = result.continuation,
                header = result.header.withArtworkFallback(songs),
                hiddenSongs = result.hiddenSongs.map(StoredSongDisplay::clean),
            )
        }
        val response = Innertube.browse(browseId)
        val page = if (browseId.startsWith("MPREb")) {
            albumPageOf(response)
        } else {
            pageOf(response)
        }
        // The signed browse of a `VLRDCLAK…` editorial playlist can answer no
        // rows. Music's anonymous browse (through the proxy) answers them with
        // each row's music-video type, which the youtube.com fallback cannot
        // supply — without it every MV in the list looks like a song and is
        // never switched to its song version. youtube.com stays the last
        // resort for when Music has nothing either.
        if (page.songs.isEmpty() && YouTubeFallback.isOfficialMusicPlaylistId(browseId)) {
            val anonymousPage = runCatching { pageOf(Innertube.browseMusicAnonymous(browseId)) }
                .getOrNull()
                ?.takeIf { it.songs.isNotEmpty() }
            if (anonymousPage != null) {
                anonymousPage.continuation?.let(anonymousContinuations::add)
                TrackLog.i(
                    TAG,
                    "playlist: $browseId rows via music anonymous " +
                        "(${anonymousPage.songs.size}, videos=${anonymousPage.songs.count { it.isVideo }})",
                )
                return@call anonymousPage
            }
            TrackLog.i(TAG, "playlist: $browseId rows via youtube.com (no music-video type on rows)")
            val result = YouTubeFallback.playlistPage(browseId)
            val songs = result.songs.map(StoredSongDisplay::clean)
            return@call SongPage(
                songs = songs,
                continuation = result.continuation,
                header = result.header.withArtworkFallback(songs),
                hiddenSongs = result.hiddenSongs.map(StoredSongDisplay::clean),
            )
        }
        val shown = if (browseId == LIKED_MUSIC) musicOnlyLikedPage(page) else page
        // Only a playlist has an owner in the sense that matters — see
        // parsePlaylistOwned — and only its own first response can be asked.
        if (!browseId.startsWith("VL")) shown
        else shown.copy(owned = InnertubeParser.parsePlaylistOwned(response))
    }

    /**
     * Liked Music is the account's whole hearted history, videos included.
     * Split off the non-music rows exactly as the youtube.com fallback does
     * (see [YouTubeMusicClassifier.splitMusic]) so the page reads as songs and
     * the hidden rows stay available behind its reveal row. Only this feed
     * gets the treatment.
     */
    private suspend fun musicOnlyLikedPage(page: SongPage): SongPage {
        page.continuation?.let(likedContinuations::add)
        if (page.songs.isEmpty()) {
            Log.i(TAG, "liked: shown=0 hidden=0 (music mode)")
            return page
        }
        val (music, videos) = YouTubeMusicClassifier.splitMusic(page.songs, LIKED_JUDGE_LIMIT)
        val shown = music.map { YouTubeDisplayCleaner.cleanUpload(it) }
        val hidden = videos.map { YouTubeDisplayCleaner.cleanUpload(it) }
        Log.i(TAG, "liked: shown=${shown.size} hidden=${hidden.size} (music mode)")
        return page.copy(songs = shown, hiddenSongs = hidden)
    }

    /**
     * Joins an album's metadata page to its authoritative track listing.
     *
     * Catalogue album pages sometimes carry only a handful of preview rows.
     * Their header play action names a backing playlist containing every track,
     * so read songs and pagination from there while retaining the richer album
     * header and controls from the original response.
     *
     * Only when that playlist is actually the better listing — see
     * [albumPlaylistOf]. Otherwise the album's own rows stand.
     */
    private suspend fun albumPageOf(albumResponse: JsonObject): SongPage {
        val metadata = pageOf(albumResponse)
        val playlist = albumPlaylistOf(albumResponse, metadata.songs.size) ?: return metadata
        return pageOf(playlist).copy(
            library = metadata.library,
            header = metadata.header,
            description = metadata.description,
        )
    }

    suspend fun enrichDetailCovers(songs: List<Song>, limit: Int = 20): List<Song> =
        KoLibrary.enrichDetailCovers(songs, limit) // [KO] library-artwork

    fun needsDetailCover(song: Song): Boolean =
        KoLibrary.needsDetailCover(song) // [KO] library-artwork

    /**
     * The first response of the playlist backing an album, or null when the
     * album page's own rows are the better listing.
     *
     * Not every backing playlist can be browsed. Drake's "Views"
     * (`MPREb_g9nBVaaauTA`) names `OLAK5uy_mmdeq…` in its play button, and
     * browsing that answers 200 with no `contents` at all — only a `noindex`
     * microformat — while the album page itself lists all twenty tracks.
     * Swapping the playlist in unconditionally showed such an album empty.
     *
     * Row counts alone can't decide it, though: an album page stops at 200
     * rows with no continuation, while its playlist pages 100 at a time —
     * `MPREb_pCTElvzIlpy` is 200 rows on the album, 100 + a token on the
     * playlist, and 233 once that is followed. So the album's rows win only
     * when the playlist has fewer *and* nothing more to page.
     */
    private suspend fun albumPlaylistOf(albumResponse: JsonObject, albumRows: Int): JsonObject? {
        val playlistId = InnertubeParser.parseAlbumPlaylistId(albumResponse) ?: return null
        val response = try {
            Innertube.browse("VL${playlistId.removePrefix("VL")}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "album playlist $playlistId failed, keeping album rows: ${e.message}")
            return null
        }
        val shelf = InnertubeParser.parsePlaylistShelf(response)
        val rows = (shelf?.songs ?: InnertubeParser.collectSongsDeep(response)).size
        val more = (shelf?.continuation ?: InnertubeParser.continuationToken(response)) != null
        if (rows < albumRows && !more) {
            Log.w(TAG, "album playlist $playlistId has $rows rows vs album's $albumRows, keeping album rows")
            return null
        }
        return response
    }

    /** The page [SongPage.continuation] points at. */
    suspend fun moreSongs(token: String): Result<SongPage> = call("browse:more") {
        if (YouTubeFallback.isContinuation(token)) {
            val result = YouTubeFallback.morePlaylist(token)
            val songs = result.songs.map(StoredSongDisplay::clean)
            return@call SongPage(
                songs = songs,
                continuation = result.continuation,
                header = result.header.withArtworkFallback(songs),
                hiddenSongs = result.hiddenSongs.map(StoredSongDisplay::clean),
            )
        }
        if (token in anonymousContinuations) {
            // A signed-out page's token is followed signed out, and so is the
            // token it hands on, until the list ends.
            return@call pageOf(Innertube.browseContinuationMusicAnonymous(token)).also { page ->
                page.continuation?.let(anonymousContinuations::add)
            }
        }
        pageOf(Innertube.browseContinuation(token), showByToken[token]).let { page ->
            if (token in likedContinuations) musicOnlyLikedPage(page) else page
        }
    }

    /**
     * The podcast each continuation token pages through — see
     * [InnertubeParser.podcastShow].
     *
     * Kept here rather than handed back with the token, because a token goes
     * on to places that know nothing about podcasts — the detail page's
     * fill-in, the playback service's own paging — and each of them only ever
     * calls [moreSongs] with it.
     */
    private val showByToken = ConcurrentHashMap<String, String>()

    /**
     * Whether [browseId] is a playlist the account made — see
     * [InnertubeParser.parsePlaylistOwned].
     *
     * The same question [browseSongs] answers on the way past, asked on its own
     * by whatever needs it without a page open: holding a playlist card offers
     * Rename and Delete, and the card itself cannot say whether either applies.
     * The rows it fetches are thrown away, which is the price of one request for
     * a menu that would otherwise have to guess.
     */
    suspend fun playlistOwned(browseId: String): Result<Boolean?> = call("owner:$browseId") {
        InnertubeParser.parsePlaylistOwned(Innertube.browse(browseId))
    }

    /** [show] is the podcast a continuation [response] pages through, if any — see [showByToken]. */
    private fun pageOf(response: JsonObject, show: String? = null): SongPage {
        val library = InnertubeParser.parseLibraryState(response)
        val parsedHeader = InnertubeParser.parseBrowseHeader(response)
        // A playlist page is scoped to its own shelf so its "Suggested
        // tracks" never read as songs the user added — see
        // parsePlaylistShelf. Anything else (album, library, history,
        // podcast) has no such shelf, and falls back to the layout-agnostic walk.
        InnertubeParser.parsePlaylistShelf(response)?.let { shelf ->
            // A signed playlist/album row arrives as the upload's own title
            // and channel. Clean it here, where every browse page is read, so
            // a fresh playlist row matches a replayed one.
            val shown = shelf.songs.map(StoredSongDisplay::clean)
            return SongPage(
                songs = shown,
                continuation = shelf.continuation,
                suggested = shelf.suggested.map(StoredSongDisplay::clean),
                library = library,
                header = parsedHeader.withArtworkFallback(shown),
            )
        }
        val podcast = InnertubeParser.podcastShow(response) ?: show
        val continuation = InnertubeParser.continuationToken(response)
        if (podcast != null && continuation != null) showByToken[continuation] = podcast
        val songs = InnertubeParser.collectSongsDeep(response, podcast).distinctBy { it.videoId }
        val shown = songs.map(StoredSongDisplay::clean)
        return SongPage(
            // One response can name the same track twice — an album page that
            // also carries a "you might also like" shelf, say. Collecting into a
            // map used to take care of that; paging by hand means saying so.
            songs = shown,
            continuation = continuation,
            library = library,
            header = parsedHeader.withArtworkFallback(shown),
            description = InnertubeParser.parseDescription(response),
        )
    }

    /**
     * The complete track listing behind an album or playlist browse id.
     *
     * The whole list rather than [browseSongs]' first page, because the callers
     * are the ones that act on all of it at once — "Add to queue" on a card
     * whose page was never opened. Queueing the first hundred rows of a
     * three-hundred-track playlist and calling it the playlist would be a
     * quieter kind of wrong than failing outright.
     *
     * Takes as long as the list is long — see [songsPaged].
     */
    suspend fun allSongs(browseId: String): Result<List<Song>> = call("all:$browseId") {
        songsPaged(browseId).ifEmpty { error("No tracks here") }
    }

    /**
     * Every track behind a browse id, following continuations.
     *
     * A playlist page returns its first ~100 rows and a token for the rest, so
     * a long list otherwise arrives silently truncated. Capped at
     * [MAX_PAGES] so a runaway feed can't hold the UI open forever, and a
     * failed page keeps whatever was already collected.
     *
     * Holds its caller until the last page lands, so it belongs behind things
     * nobody is watching — the library sync, an artist's back catalogue. For
     * anything a screen is waiting on, use [browseSongs] and [moreSongs].
     */
    private suspend fun songsPaged(browseId: String): List<Song> {
        if (isYouTubePlaylistId(browseId) && ensureGateKnown()) {
            return YouTubeFallback.allSongs(browseId).map(StoredSongDisplay::clean)
        }
        val out = LinkedHashMap<String, Song>()
        var response = Innertube.browse(browseId)
        if (browseId.startsWith("MPREb")) {
            val albumRows = InnertubeParser.collectSongsDeep(response).size
            albumPlaylistOf(response, albumRows)?.let { response = it }
        }
        var page = 1
        // Named by the first page alone; see [InnertubeParser.podcastShow].
        val show = InnertubeParser.podcastShow(response)
        while (true) {
            // Same shelf-scoping as pageOf: a playlist (Liked Music and the
            // Library Songs auto-playlist included) is read from its own
            // shelf so a trailing "Suggested tracks" shelf never joins in.
            val shelf = InnertubeParser.parsePlaylistShelf(response)
            (shelf?.songs ?: InnertubeParser.collectSongsDeep(response, show)).forEach { out[it.videoId] = it }
            val token = shelf?.continuation ?: InnertubeParser.continuationToken(response)
            if (token == null || page++ >= MAX_PAGES) break
            response = runCatching { Innertube.browseContinuation(token) }.getOrNull() ?: break
        }
        // The same editorial-playlist gap as [browseSongs]: when the signed
        // browse has no rows, read the list signed out so every row keeps its
        // music-video type.
        if (out.isEmpty() && YouTubeFallback.isOfficialMusicPlaylistId(browseId)) {
            var anonymous = runCatching { Innertube.browseMusicAnonymous(browseId) }.getOrNull()
            var anonymousPage = 1
            while (anonymous != null) {
                val shelf = InnertubeParser.parsePlaylistShelf(anonymous)
                (shelf?.songs ?: InnertubeParser.collectSongsDeep(anonymous)).forEach { out[it.videoId] = it }
                val token = shelf?.continuation ?: InnertubeParser.continuationToken(anonymous)
                if (token == null || anonymousPage++ >= MAX_PAGES) break
                anonymous = runCatching { Innertube.browseContinuationMusicAnonymous(token) }.getOrNull()
            }
        }
        return out.values.map(StoredSongDisplay::clean)
    }

    /**
     * Every saved library card behind a feed browse id, following continuations.
     *
     * The library shelves are capped by YouTube's first page just like search:
     * playlists, albums and artists often stop at about twenty-five rows unless
     * their feed continuation is followed. These are background library loads,
     * so collecting the full bounded set before publishing is preferable to a
     * shelf that looks complete and silently is not.
     */
    private suspend fun itemsPaged(browseId: String, params: String? = null): List<ShelfItem> {
        val out = LinkedHashMap<String, ShelfItem>()
        var response = Innertube.browse(browseId, params)
        var page = 1
        while (true) {
            val parsed = InnertubeParser.parseLibraryItemPage(response)
            parsed.items.forEach { item ->
                val key = item.browseId ?: item.videoId ?: "${item.title}\n${item.subtitle}"
                out.putIfAbsent(key, item)
            }
            val token = parsed.continuation ?: break
            if (page++ >= MAX_PAGES) break
            response = runCatching { Innertube.browseContinuation(token) }.getOrNull() ?: break
        }
        return out.values.toList()
    }

    private suspend fun libraryItemsPaged(browseId: String): List<ShelfItem> = itemsPaged(browseId, null)

    const val MAX_PAGES = 10

    /**
     * Liked Music: the `LM` auto-playlist, addressed as a playlist browse id.
     * Public because it is also the page a track has to disappear from the
     * moment it stops being liked — see MainViewModel's `dropFromLikedLists`.
     */
    const val LIKED_MUSIC = "VLLM"

    /** Rows of a Liked Music page judged by category; the rest are trusted. */
    private const val LIKED_JUDGE_LIMIT = 30

    /** Songs explicitly added to the library — distinct from Liked Music. */
    private const val LIBRARY_SONGS = "FEmusic_liked_videos"

    /** Saved and own playlists; also what the "add to playlist" picker lists. */
    private const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"

    /**
     * What the playlists shelf is called in a [LibraryPage].
     *
     * Coined here, and named here rather than spelt out at each use, because it
     * is the only shelf in the library anything else looks for by name: it is
     * the one the create tile leads (see LibraryScreen) and the one a rename or
     * a delete has to reach into (see MainViewModel's `editPlaylistShelf`).
     * Three copies of a bare "Playlists" is three places a retitling silently
     * turns those features off.
     */
    const val PLAYLISTS_SHELF = "Playlists"

    private val LIBRARY_FEEDS = listOf(
        PLAYLISTS_SHELF to LIBRARY_PLAYLISTS,
        "Albums" to "FEmusic_liked_albums",
        "Artists" to "FEmusic_library_corpus_track_artists",
        "Subscriptions" to "FEmusic_library_corpus_artists",
        "Podcasts" to "FEmusic_library_non_music_audio_list",
    )

    // ---- Writes -------------------------------------------------------------

    /**
     * The account's own state for one track — rating and library membership.
     *
     * Deliberately a lookup rather than something cached with the [Song]: a
     * track reaching the player through the queue has been round-tripped
     * through a MediaItem, which carries an id and little else, and the
     * feedback tokens are per-row anyway. Fetched when a menu is opened, which
     * is the only moment the answer is looked at.
     */
    suspend fun songMenu(videoId: String): Result<SongMenu> = call("menu:$videoId") {
        InnertubeParser.parseSongMenu(Innertube.next(videoId), videoId)
            ?: error("no menu for $videoId")
    }

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> =
        call("rate:$videoId") {
            if (fallbackActive()) Innertube.rateYouTube(videoId, status)
            else Innertube.rate(videoId, status)
        }

    /** Adds or removes a track from the library; [token] says which. */
    suspend fun setLibraryStatus(token: String): Result<Unit> =
        call("library:feedback") { Innertube.sendFeedback(token) }

    /**
     * Saves an album or playlist to the library, or removes it. [playlistId] is
     * the one the page named — see [LibraryState].
     */
    /**
     * Whether the release at [browseId] is in the library, and the playlist that
     * saving it acts on — read off the release's own page, since a card that
     * only knows the browse id has neither. Null where the page offers no save
     * button (a signed-out response, or a release YouTube marks unsaveable).
     */
    suspend fun releaseLibraryState(browseId: String): Result<LibraryState?> =
        call("library-state:$browseId") { InnertubeParser.parseLibraryState(Innertube.browse(browseId)) }

    suspend fun setSaved(playlistId: String, saved: Boolean): Result<Unit> =
        call("library:$playlistId") { Innertube.ratePlaylist(playlistId, saved) }

    /**
     * Subscribes to an artist's channel, or unsubscribes. [channelId] is the one
     * the page's own subscribe button named — see
     * [com.music.bitchord.data.model.SubscriptionState].
     */
    suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> =
        call("subscription:$channelId") { Innertube.setSubscribed(channelId, subscribed) }

    /**
     * The playlists a track can be added to. Paged because accounts with long
     * playlist collections otherwise lose everything past YouTube's first
     * library-feed response.
     */
    suspend fun userPlaylists(): Result<List<UserPlaylist>> = call("playlists") {
        if (ensureGateKnown()) {
            return@call YouTubeFallback.userPlaylists(YouTubeFallback.libraryPlaylistItems())
        }
        InnertubeParser.parseUserPlaylists(libraryItemsPaged(LIBRARY_PLAYLISTS))
    }

    /**
     * All playlists in user library (including Liked Music and saved playlists).
     */
    suspend fun libraryPlaylists(): Result<List<ShelfItem>> = call("libraryPlaylists") {
        if (ensureGateKnown()) return@call YouTubeFallback.libraryPlaylistItems()
        InnertubeParser.parseLibraryItems(Innertube.browse(LIBRARY_PLAYLISTS))
    }

    /** Creates a playlist, optionally seeded with [videoIds]; returns its id. */
    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String> = emptyList(),
    ): Result<String> = call("playlist:create") {
        Innertube.createPlaylist(title, privacy, videoIds = videoIds)
    }

    /**
     * Adds tracks to a playlist. Succeeds with the per-entry ids YouTube minted
     * for them — see [Innertube.addToPlaylist]. A caller with nothing on screen
     * to update can ignore the map; one splicing the row into a playlist it is
     * looking at needs it for the row's "remove".
     */
    suspend fun addToPlaylist(
        playlistId: String,
        videoIds: List<String>,
    ): Result<Map<String, String>> =
        call("playlist:add") { Innertube.addToPlaylist(playlistId, videoIds) }

    /** [entries] are (setVideoId, videoId) pairs — see [Song.setVideoId]. */
    suspend fun removeFromPlaylist(
        playlistId: String,
        entries: List<Pair<String, String>>,
    ): Result<Unit> = call("playlist:remove") {
        Innertube.removeFromPlaylist(playlistId, entries)
    }

    /**
     * A playlist's entries in their current order, every page of them, for
     * rearranging.
     *
     * Fetched fresh rather than read off the open page, because a reorder is
     * sent as moves relative to neighbours (see [playlistMoves]) and a list
     * that stops short of the end — the open page while it is still filling
     * in — would send the last row it has "to the end" past rows it never saw.
     * Fails when an entry comes back without the set-video-id a move has to
     * name, rather than offering to reorder something it can't.
     */
    suspend fun playlistEntries(browseId: String): Result<List<Song>> = call("entries:$browseId") {
        val out = LinkedHashMap<String, Song>()
        var response = Innertube.browse(browseId)
        var page = 1
        while (true) {
            val shelf = InnertubeParser.parsePlaylistShelf(response) ?: break
            shelf.songs.forEach { song ->
                val setVideoId = song.setVideoId ?: error("playlist entry without an id")
                out.putIfAbsent(setVideoId, song)
            }
            val token = shelf.continuation ?: break
            // Same cap as [songsPaged] — a playlist past it can't be listed
            // whole, and a partial list can't be reordered safely.
            if (page++ >= MAX_PAGES) error("playlist too long to reorder")
            response = Innertube.browseContinuation(token)
        }
        out.values.map(StoredSongDisplay::clean)
    }

    /**
     * Rearranges a playlist from [current] to [target], both its entries'
     * set-video-ids — see [playlistMoves]. Sent in batches so a large
     * rearrangement doesn't become one oversized request.
     */
    suspend fun reorderPlaylist(
        playlistId: String,
        current: List<String>,
        target: List<String>,
    ): Result<Unit> = call("playlist:reorder") {
        playlistMoves(current, target).chunked(MOVE_BATCH).forEach { batch ->
            Innertube.movePlaylistItems(playlistId, batch)
        }
    }

    private const val MOVE_BATCH = 50

    suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit> =
        call("playlist:rename") { Innertube.renamePlaylist(playlistId, title) }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        call("playlist:delete") { Innertube.deletePlaylist(playlistId) }

    /**
     * Artist page. The landing page only lists ~5 songs, so the linked
     * "Top songs" playlist is fetched to fill the list out, and any linked
     * full release shelves (Albums, Singles & EPs) are fetched to populate
     * their complete discography.
     */
    suspend fun artistPage(browseId: String): Result<ArtistPage> = call("artist:$browseId") {
        val page = InnertubeParser.parseArtistPage(Innertube.browse(browseId))
        coroutineScope {
            val fullSongsDeferred = async {
                page.moreSongsBrowseId?.let { playlistId ->
                    runCatching { songsPaged(playlistId) }.getOrNull()
                }
            }
            val shelvesDeferred = page.sections.map { shelf ->
                async {
                    val moreBrowseId = shelf.moreBrowseId
                    if (moreBrowseId != null) {
                        val fullItems = runCatching {
                            itemsPaged(moreBrowseId, shelf.moreParams)
                        }.getOrNull()
                        if (!fullItems.isNullOrEmpty()) {
                            shelf.copy(items = fullItems)
                        } else {
                            shelf
                        }
                    } else {
                        shelf
                    }
                }
            }
            val fullSongs = fullSongsDeferred.await()
            val fullShelves = shelvesDeferred.awaitAll()
            val resolvedSongs = if (!fullSongs.isNullOrEmpty()) fullSongs else page.songs
            page.copy(songs = resolvedSongs, sections = fullShelves)
        }
    }

    /**
     * The artist's landing page alone — one request. [artistPage] goes on to
     * page out every song and release, which a Top Picks card has no use for:
     * it needs the header (photo, Mix and Shuffle) and the first card of each
     * shelf.
     */
    suspend fun artistLanding(browseId: String): Result<ArtistPage> = call("artistLanding:$browseId") {
        InnertubeParser.parseArtistPage(Innertube.browse(browseId))
    }

    /**
     * The queue a watch target starts — a mix, a playlist radio, or an artist's
     * Mix or Shuffle button. Music videos are dropped, as everywhere a queue is
     * built for the listener rather than picked by them.
     */
    suspend fun watchQueue(target: WatchTarget): Result<List<Song>> = call("watch:${target.playlistId}") {
        InnertubeParser.parseWatchQueue(
            Innertube.nextPlaylist(target.playlistId, target.videoId, target.params),
        ).filterNot { it.isVideo }
    }

    /**
     * A playlist's tracks as a watch queue, each one as audio where YouTube
     * can say what the audio version is. Editorial playlists list mostly
     * official videos; signed in, `next` pairs each with the song it's a
     * video of, so the song is taken directly instead of being searched for
     * when it comes to play. A row with no pair stays the video — the player's
     * own video-to-audio step still covers it.
     */
    suspend fun playlistAsAudio(playlistId: String): Result<List<Song>> = call("watchAudio:$playlistId") {
        val response = Innertube.nextPlaylist(playlistId)
        val songs = InnertubeParser.parseWatchQueue(response, preferAudio = true)
        val (pairs, audioPairs) = InnertubeParser.watchQueuePairs(response)
        Log.d(TAG, "watchAudio $playlistId rows=${songs.size} pairs=$pairs audioPairs=$audioPairs videosLeft=${songs.count { it.isVideo }}")
        songs
    }

    /**
     * The cards of the signed-in "Mixed for you" page — My Supermix, Discover
     * Mix, New Release Mix and the numbered My Mixes. Home carries the same
     * cards as a shelf, but not always on its first page; this is the page
     * that shelf's More opens, and it answers in one request.
     */
    suspend fun mixedForYou(): Result<List<ShelfItem>> = call("mixedForYou") {
        if (Innertube.cookie == null) return@call emptyList()
        InnertubeParser.parseTwoRowItemsDeep(Innertube.browse("FEmusic_mixed_for_you"))
    }

    /**
     * Listening history for a caller that can live with it being a minute or
     * two old — Top Picks, built right after Recents fetched the same feed.
     * Every real fetch refreshes the memo, so nothing reading [history] or
     * [recentlyPlayed] ever sees a stale copy.
     */
    suspend fun recentHistory(): Result<List<Song>> = call("history:recent") {
        if (Innertube.cookie == null) return@call emptyList()
        val memo = historyMemo
        if (memo != null && memo.cookie == Innertube.cookie &&
            System.currentTimeMillis() - memo.at < HISTORY_MEMO_MS
        ) {
            memo.songs
        } else {
            fetchHistory()
        }
    }

    private class HistoryMemo(val cookie: String?, val at: Long, val songs: List<Song>)

    @Volatile
    private var historyMemo: HistoryMemo? = null

    private const val HISTORY_MEMO_MS = 120_000L

    /** Whether the Music gate is active, settling an UNKNOWN verdict first. */
    private suspend fun ensureGateKnown(): Boolean = KoRepoGate.ensureGateKnown() // [KO] korea-gate

    /** One immediate gate re-check after the proxy settings moved. */
    suspend fun recheckGateNow(): Boolean = ensureGateKnown() // [KO] korea-gate

    private fun isMusicGateSignal(failure: Exception): Boolean =
        KoRepoGate.isMusicGateSignal(failure) // [KO] korea-gate
    private suspend fun fallbackActive(): Boolean = KoRepoGate.fallbackActive() // [KO] korea-gate

    private fun isYouTubePlaylistId(browseId: String): Boolean =
        KoRepoGate.isYouTubePlaylistId(browseId) // [KO] korea-gate

    internal suspend fun <T> call(label: String, block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
            runCatching { block() }.recoverCatching { failure ->
                // Context cookies can rotate while a process is alive. Refresh
                // once and retry; never loop or silently sign the listener out.
                val rejected = failure.message?.contains("401") == true ||
                    failure.message?.contains("403") == true ||
                    failure.message?.contains("rejected", true) == true
                if (!rejected || Innertube.cookie == null) throw failure
                Log.w(TAG, "$label rejected; refreshing active session context once")
                Innertube.refreshSessionScope()
                block()
            }
                // runCatching catches Throwable, cancellation included, which
                // would turn "the user typed another letter" into a failed
                // Result and put the abandoned request's error on screen.
                // Cancellation isn't this call's to answer for.
                .onFailure { if (it is CancellationException) throw it }
                .onSuccess {
                    val ms = elapsedMs()
                    Log.d(TAG, "$label ok ${ms}ms")
                    if (ms > SLOW_CALL_MS) Log.w(TAG, "slow: $label $ms")
                }
                .onFailure {
                    val ms = elapsedMs()
                    val status = (it as? ResponseException)?.response?.status?.value
                    val via = if (YouTubeProxy.config.usable) "proxy" else "direct"
                    Log.w(
                        TAG,
                        "fail: $label ${it::class.simpleName ?: "Exception"} " +
                            "${status ?: "-"} via=$via (${ms}ms)",
                    )
                    if (ms > SLOW_CALL_MS) Log.w(TAG, "slow: $label $ms")
                }
        }
}
