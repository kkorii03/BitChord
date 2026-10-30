package com.music.bitchord.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.R
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.auth.SignedIn
import com.music.bitchord.auth.GoogleAccountSession
import com.music.bitchord.auth.YouTubeProfile
import com.music.bitchord.auth.profileId
import com.music.bitchord.auth.sessionId
import com.music.bitchord.auth.adjacentProfile
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.TrackDiagnostics
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.LocalPlayHistory
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.net.TYPEAHEAD_CACHE_ENTRIES
import com.music.bitchord.data.net.TYPEAHEAD_DEBOUNCE_MS
import com.music.bitchord.data.net.typeaheadCacheKey
import com.music.bitchord.data.net.typeaheadLookupTarget
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.navigation.AppNavigator
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.PlaybackTracker
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.auth.CapturedSession
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.GENERIC_ACCOUNT_NAME
import com.music.bitchord.data.model.mergeAccountIdentity
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.ExploreCategoryFilter
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.HomeSource
import com.music.bitchord.data.model.mergeHomeShelves
import com.music.bitchord.data.model.withoutRepeatsOf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.SmartPlaylist
import com.music.bitchord.data.stats.SmartPlaylists
import com.music.bitchord.data.model.SongMenu
import com.music.bitchord.data.model.SubscriptionState
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.download.Downloads
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.BoundedContextRequests
import com.music.bitchord.playback.contextEntryKey
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.ui.screens.CACHE_FOLDER_BROWSE_ID
import com.music.bitchord.data.LocalGroup
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.matchesSearch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the Search tab searches: YouTube Music, or the Local Music folder on this device. */
enum class SearchSource { YOUTUBE, LIBRARY, ADDONS }

/** Work that may run after the first screen has had time to settle. */
internal enum class StartupDeferredWork { ACCOUNT, UPDATE_CHECK }

/**
 * The only launch work allowed behind the start-up delay. Feeds are absent on
 * purpose: Explore and Library are fetched by the tab that shows them, never
 * by a timer racing Home's first request.
 */
internal fun startupDeferredWork(signedIn: Boolean): List<StartupDeferredWork> = buildList {
    if (signedIn) add(StartupDeferredWork.ACCOUNT)
    add(StartupDeferredWork.UPDATE_CHECK)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val authStore = AuthStore(app)

    // Process-wide, so the playback service offers Like on the same tracks.
    private val _signedIn = SignedIn.state(app)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = _home.asStateFlow()

    /**
     * Token for the next page of Home shelves; null once there's nothing
     * more. Declared here rather than by [loadMoreHome] because [init] calls
     * [loadHome] synchronously up to its first suspension point — a property
     * declared after [init] would still be null when that runs.
     */
    private var homeContinuation: String? = null

    /** Titles already on screen, so a later page can't repeat a shelf. */
    private val homeSeenTitles = mutableSetOf<String>()

    /**
     * The catalogue the shelves on screen came from. A refresh may only carry
     * old shelves over when the fresh feed is from the same source; switching
     * between Music and the youtube.com fallback replaces the page.
     */
    private var homeSource: HomeSource? = null

    private val _homeLoadingMore = MutableStateFlow(false)

    /**
     * Requests still filling out the first Play page. The core feed and each
     * supplement browse run in parallel and publish as they land, so the list
     * goes Success while several shelves are still on the wire — counted here
     * so the feed can say more is coming instead of just ending mid-load.
     */
    private val _homePendingShelves = MutableStateFlow(0)

    /** True whenever shelves are still due at the end of the feed, from either source. */
    val homeLoadingMore: StateFlow<Boolean> =
        combine(_homeLoadingMore, _homePendingShelves) { paging, pending -> paging || pending > 0 }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _homeRecentlyPlayedLoading = MutableStateFlow(false)
    val homeRecentlyPlayedLoading: StateFlow<Boolean> = _homeRecentlyPlayedLoading.asStateFlow()
    private val homeLoadGeneration = AtomicLong(0L)

    /**
     * The first-page load in flight, if any.
     *
     * A load and a refresh ask the same endpoints (core feed, recently played)
     * and used to be able to run together: the refresh guard below only knew
     * about other refreshes. A tab shown while the launch load was still
     * arriving therefore started a second copy of both requests. The refresh
     * joins this load instead: it does not bump the generation, so the load's
     * own answer is still the one that lands.
     */
    private var homeLoadJob: Job? = null

    private val _explore = MutableStateFlow<UiState<List<MoodGenreSection>>>(UiState.Loading)
    val explore: StateFlow<UiState<List<MoodGenreSection>>> = _explore.asStateFlow()

    /** The load in flight, so a tab that is shown while it runs does not ask twice. */
    private var exploreLoadJob: Job? = null

    /** The last answer before hidden categories are applied, so showing one again can restore it. */
    private var rawExploreSections: List<MoodGenreSection> = emptyList()

    private val _selectedMoodGenre = MutableStateFlow<MoodGenre?>(null)
    val selectedMoodGenre: StateFlow<MoodGenre?> = _selectedMoodGenre.asStateFlow()

    private val _moodGenreShelves = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val moodGenreShelves: StateFlow<UiState<List<HomeShelf>>> = _moodGenreShelves.asStateFlow()

    /**
     * The charts page's country choice while the charts are open: the code
     * shown and the codes offered. Null on every other category.
     */
    private val _chartCountries = MutableStateFlow<Pair<String?, List<String>>?>(null)
    val chartCountries: StateFlow<Pair<String?, List<String>>?> = _chartCountries.asStateFlow()
    private var chartsJob: Job? = null

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<UiState<List<SearchResult>>?>(null)
    val results: StateFlow<UiState<List<SearchResult>>?> = _results.asStateFlow()

    /** The mixed YouTube Music result page is the fast, useful default. */
    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _searchLoadingMore = MutableStateFlow(false)
    val searchLoadingMore: StateFlow<Boolean> = _searchLoadingMore.asStateFlow()

    /** Increments once per first-page request so the UI can reset its list. */
    private val _searchScrollReset = MutableStateFlow(0)
    val searchScrollReset: StateFlow<Int> = _searchScrollReset.asStateFlow()

    /** True while a committed search is in flight — gates suggestion/media callbacks. */
    private var searchSubmitted = false

    /**
     * What the search page offers while a query is being typed, led by the
     * query itself.
     *
     * Non-empty *is* the signal that the field is mid-edit, so the screen
     * needs no second flag: these rows are shown in place of the results
     * whenever there are any, and cleared the moment a search is actually run
     * — see [submitSearch], [searchFor].
     *
     * Element 0 is always the raw text as typed. It's put there by the
     * keystroke itself rather than taken from the response, so the row the
     * thumb is already heading for is correct before the network answers, and
     * stays correct if it never does — YouTube's list never contains the
     * half-typed text, only completions of it.
     */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    /**
     * Live media results shown alongside typeahead suggestions. Populated by a
     * lightweight search that runs in parallel with text completions; the UI
     * renders these as playable track cards and browse items below the text
     * suggestion rows. Cleared when the user commits to a search or empties
     * the field.
     */
    private val _typeaheadResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val typeaheadResults: StateFlow<List<SearchResult>> = _typeaheadResults.asStateFlow()

    /** Where the Search tab looks — see [setSearchSource]. */
    private val _searchSource = MutableStateFlow(SearchSource.YOUTUBE)
    val searchSource: StateFlow<SearchSource> = _searchSource.asStateFlow()

    /**
     * The Addons source's answer for the submitted query: every enabled addon
     * searched at once and their rows interleaved. Null until something has
     * been submitted. These songs carry source track keys, so a tap plays the
     * addon's own stream with no YouTube lookup in between.
     */
    private val _addonResults = MutableStateFlow<UiState<List<Song>>?>(null)
    val addonResults: StateFlow<UiState<List<Song>>?> = _addonResults.asStateFlow()
    private var addonSearchJob: Job? = null

    /** The words typed into the Addons search before, newest first. */
    val addonSearchHistory: StateFlow<List<String>> = SearchHistory.addonRecent

    fun removeAddonSearch(query: String) = SearchHistory.removeAddonQuery(query)

    fun clearAddonSearchHistory() = SearchHistory.clearAddonQueries()

    /** Every track in the Local Music folder, read when the Library source is picked. */
    private val _librarySongs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)

    /**
     * The Library source's answer for the current query: the Local Music
     * folder narrowed by the same match its own filter box uses, so the two
     * places never disagree about what is on the device. Null while the field
     * is empty — there is nothing asked yet.
     */
    val libraryResults: StateFlow<UiState<List<Song>>?> = combine(_librarySongs, _query) { state, query ->
        val term = query.trim()
        when {
            term.isEmpty() -> null
            state is UiState.Success -> UiState.Success(state.data.filter { it.matchesSearch(term) })
            else -> state
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The search pipeline's own state. Declared here, above [init], because
    // that is where the collector is started from and a property declared
    // below it would still be null when it runs. See [startSearchPipeline].

    /**
     * Buffered so an emission is never lost to a collector that happens to be
     * mid-search, and [BufferOverflow.DROP_OLDEST] because when two arrive
     * together the later one is the one meant.
     */
    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The same arrangement as [searchRequests], for the typeahead — where the
     * drop policy earns its keep rather than just being safe: this one really
     * does take a keystroke each, and a fast typist's backlog should collapse
     * to the prefix they ended on instead of being worked through a letter at
     * a time.
     */
    private val suggestRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Answers already fetched for an input, so typing the same word again —
     * or backing up over a letter and retyping it — costs no request. Both
     * pipelines keep their own because one answers text and the other rows.
     * Bounded like every other cache here: the Search tab is long-lived.
     */
    private val suggestionCache = object : LinkedHashMap<String, List<String>>(TYPEAHEAD_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) =
            size > TYPEAHEAD_CACHE_ENTRIES
    }
    private val typeaheadMediaCache =
        object : LinkedHashMap<String, List<SearchResult>>(TYPEAHEAD_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchResult>>) =
                size > TYPEAHEAD_CACHE_ENTRIES
        }

    private val newestRequestId = AtomicLong(0L)

    /**
     * Whether the request currently on screen was made without the account.
     * A retry repairs that same request and must not turn an anonymous lookup
     * into a signed one.
     */
    private var lastSearchAnonymous = true

    /**
     * Results of recent searches, so a query searched before is answered
     * without asking again. That covers the two ways a query is repeated most:
     * a filter tab, which re-runs the same text against a different tab and
     * then usually goes back, and a term tapped out of the recent searches.
     *
     * Its other half is [prefixMatch], which is what the typeahead makes worth
     * keeping: picking "coldplay yellow" off a list is normally preceded by
     * having searched "coldplay", and those results are close enough to leave
     * up for the moment the narrower one takes rather than blanking the page
     * to a spinner.
     */
    private data class SearchCacheEntry(
        val rows: List<SearchResult>,
        val continuation: String?,
    )

    private data class SearchSession(
        val key: String,
        val requestId: Long,
        val filter: SearchFilter,
        val continuation: String?,
    )

    private val searchCache = LruCache<String, SearchCacheEntry>(SEARCH_CACHE_ENTRIES)
    private var searchSession: SearchSession? = null

    private val lyricsController = LyricsController(app, viewModelScope)

    /** Synced lyrics for whatever is playing; null while unknown or absent. */
    val lyrics: StateFlow<List<LyricLine>?> = lyricsController.lyrics

    /** How the lyrics on screen were moved onto the video's clock. */
    val lyricsTiming: StateFlow<com.music.bitchord.ui.player.LyricsTimingUi> =
        lyricsController.lyricsTiming

    /** Which of the four databases [lyrics] came from, for the panel's credit. */
    val lyricsSource: StateFlow<LyricsSource?> = lyricsController.lyricsSource

    /**
     * Whether the lookup for the current track has finished. [lyrics] alone
     * can't tell "still looking" apart from "looked, found nothing" — both
     * are null — and the player needs that distinction to show "Lyrics not
     * available" only once it actually means that.
     */
    val lyricsChecked: StateFlow<Boolean> = lyricsController.lyricsChecked

    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        lyricsController.lyricsProviderStates

    /**
     * Called as the playing track changes; cheap no-op when already loaded.
     * See [LyricsController.loadLyrics].
     */
    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
        /** A video upload: its captions are asked first. */
        isVideo: Boolean = false,
        /** Other readings of a cover's title to search under — see CoverTitles. */
        alternatives: List<Pair<String, String>> = emptyList(),
        /** False when [artist] is only a cover's singer — see CoverTitles.LyricsQuery. */
        artistKnown: Boolean = true,
        /** Other names the row's artist is known by — see CoverTitles.LyricsQuery. */
        artistAliases: List<String> = emptyList(),
    ) = lyricsController.loadLyrics(
        videoId = videoId,
        title = title,
        artist = artist,
        durationMs = durationMs,
        album = album,
        localUri = localUri,
        isVideo = isVideo,
        alternatives = alternatives,
        artistKnown = artistKnown,
        artistAliases = artistAliases,
    )

    /** Selects a provider from the player drawer — see [LyricsController.selectLyricsProvider]. */
    fun selectLyricsProvider(source: LyricsSource) = lyricsController.selectLyricsProvider(source)

    /** The AI timing button — see [LyricsController.toggleAiLyricsTiming]. */
    fun toggleAiLyricsTiming() = lyricsController.toggleAiLyricsTiming()

    /** The sync drawer's "this song" correction — see [LyricsController.setSongLyricsOffset]. */
    fun setSongLyricsOffset(ms: Long) = lyricsController.setSongLyricsOffset(ms)

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    /**
     * The channels this login can act as. Empty until the picker asks for
     * them — it is one more request per sign-in and nothing else on the
     * settings page needs the answer.
     */
    private val _channels = MutableStateFlow<List<AccountChannel>>(emptyList())
    val channels: StateFlow<List<AccountChannel>> = _channels.asStateFlow()

    private val _channelsLoading = MutableStateFlow(false)
    private var channelsJob: Job? = null

    /**
     * The chosen channel's [AccountChannel.key], or null while the app is
     * acting as whichever channel YouTube Music serves by default.
     */
    private val _selectedChannelKey = MutableStateFlow(
        authStore.activeSession?.activeProfileId ?: authStore.channelPageId ?: authStore.channelDataSyncId,
    )

    private val _selectedChannelName = MutableStateFlow(
        authStore.activeSession?.profiles?.firstOrNull { it.profileId == authStore.activeProfileId }?.name
            ?: authStore.channelName,
    )
    val selectedChannelName: StateFlow<String?> = _selectedChannelName.asStateFlow()

    /** Source of truth for Google sessions and their YouTube identities. */
    private val _googleAccounts = MutableStateFlow(authStore.sessions)
    val googleAccounts: StateFlow<List<GoogleAccountSession>> = _googleAccounts.asStateFlow()
    private val _activeAccountId = MutableStateFlow(authStore.activeSession?.accountId)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()
    private val _activeProfileId = MutableStateFlow(authStore.activeProfileId)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _history = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val history: StateFlow<UiState<List<Song>>> = _history.asStateFlow()

    private val _library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val library: StateFlow<UiState<LibraryPage>> = _library.asStateFlow()

    /** The load in flight, so a tab that is shown while it runs does not ask twice. */
    private var libraryLoadJob: Job? = null

    /** The Local Music scan in flight, so a retry does not stack a second full scan. */
    private var localMusicLoadJob: Job? = null

    /** In-memory cache is partitioned by account and profile; it is never shared. */
    private data class ListenerSnapshot(
        val account: Account?, val library: UiState<LibraryPage>,
        val history: UiState<List<Song>>, val playlists: List<UserPlaylist>,
        val owned: Map<String, Boolean>,
    )
    private val listenerCache = mutableMapOf<String, ListenerSnapshot>()

    /**
     * Album / artist / playlist pages, as a stack — opening an artist from an
     * album page and pressing back returns to the album, not to search.
     */
    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()

    /**
     * The app's back stack: the routes pushed over the tabs, interleaved with
     * [detailStack]. Held here so it survives a configuration change along
     * with the release pages it is ordered against.
     */
    val navigator = AppNavigator(
        detailDepth = { _detailStack.value.size },
        popDetail = ::closeDetail,
        clearDetails = ::clearDetail,
    )

    /** UI-side continuation fetches that can be handed to the playback service. */
    private val fillInJobs = mutableMapOf<String, Job>()

    /** Visible detail rows whose cover lookup is already running. */
    private val detailCoverLookups = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _releaseLibrary = MutableStateFlow<Map<String, LibraryState>>(emptyMap())

    /**
     * Library state of releases that are only a card on some page — the artist
     * page's top release — keyed by browse id. They have no page of their own on
     * the stack to carry it, so it is read off the release and kept here.
     */
    val releaseLibrary: StateFlow<Map<String, LibraryState>> = _releaseLibrary.asStateFlow()

    /** Reads whether the release [browseId] is saved, once; a no-op if already known. */
    fun loadReleaseLibrary(browseId: String) {
        if (browseId in _releaseLibrary.value) return
        viewModelScope.launch {
            YtMusicRepository.releaseLibraryState(browseId).getOrNull()?.let { state ->
                _releaseLibrary.value += (browseId to state)
            }
        }
    }

    /** Saves the release [browseId] to the library or takes it out, as [toggleLibrary] does for a page. */
    fun toggleReleaseLibrary(browseId: String) {
        if (!requireSignIn()) return
        viewModelScope.launch {
            val current = _releaseLibrary.value[browseId]
                ?: YtMusicRepository.releaseLibraryState(browseId).getOrNull()
                ?: return@launch
            val target = !current.saved
            _releaseLibrary.value += (browseId to current.copy(saved = target))
            if (YtMusicRepository.setSaved(current.playlistId, target).isSuccess) {
                libraryStale = true
            } else {
                _releaseLibrary.value += (browseId to current)
            }
        }
    }


    /** Set once per launch if GitHub has a release newer than this build. */
    val updateAvailable: StateFlow<AppUpdateChecker.UpdateInfo?> = AppUpdateChecker.available

    // ---- Ratings, library and playlists -------------------------------------

    /**
     * Ratings this session has set, which win over whatever the library feed
     * last said.
     *
     * Kept apart from the library rather than folded into it because the two
     * answer different questions: Liked Music is what YouTube knew when the
     * page was fetched, and this is what the user has done since. Layering
     * them ([likeStatuses]) means a tap shows immediately without the library
     * having to be re-fetched, and a later refresh can't undo it.
     */
    /** Every rating known for this account: the library's, then this session's. */
    val likeStatuses: StateFlow<Map<String, LikeStatus>> =
        combine(_library, LikeState.overrides) { library, overrides ->
            val liked = (library as? UiState.Success)?.data?.likedSongs
                ?.associate { it.videoId to LikeStatus.LIKE }
                .orEmpty()
            liked + overrides
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun likeStatusOf(videoId: String): LikeStatus =
        likeStatuses.value[videoId] ?: LikeStatus.INDIFFERENT

    /**
     * Sets (or clears) the thumbs rating on [videoId].
     *
     * Written to the screen first and rolled back if YouTube refuses. A rating
     * is a one-tap, low-stakes action taken while a song is playing; waiting
     * on a round trip before the heart fills reads as the tap not having
     * registered, and people tap again.
     */
    fun setLike(videoId: String, status: LikeStatus) {
        if (!requireSignIn()) return
        val previous = likeStatusOf(videoId)
        if (previous == status) return
        LikeState.set(videoId, status)
        viewModelScope.launch {
            YtMusicRepository.rate(videoId, status).fold(
                onSuccess = {
                    // The same event the notification reports: a rating the
                    // listener made, recorded once YouTube has accepted it.
                    com.music.bitchord.data.taste.TasteSignals.onRated(
                        videoId,
                        knownSong(videoId),
                        status,
                    )
                    // Liked Music is now out of date either way.
                    libraryStale = true
                    if (status != LikeStatus.LIKE) dropFromLikedLists(videoId)
                    // Clearing the heart means forgetting the song, not
                    // demoting it — see [forgetFromLibrary].
                    val unliked = previous == LikeStatus.LIKE &&
                        status == LikeStatus.INDIFFERENT
                    if (unliked) forgetFromLibrary(videoId)
                    if (status == LikeStatus.LIKE) {
                        com.music.bitchord.download.LikedAutoDownload.onLiked(videoId, knownSong(videoId))
                    } else if (previous == LikeStatus.LIKE) {
                        com.music.bitchord.download.LikedAutoDownload.onUnliked(videoId)
                    }
                },
                onFailure = {
                    LikeState.set(videoId, previous)
                },
            )
        }
    }

    /**
     * Takes an un-liked track out of the library as well, and reports whether
     * it did.
     *
     * Liking and saving are two independent flags on YouTube's side, and
     * clearing only the first leaves the song saved — still feeding the
     * Library tab's Artists shelf, still in the library feeds, with nowhere
     * left in this app to reach it and finish the job. Clearing the heart
     * reads as "forget this song", so it clears both.
     *
     * The token is fetched here rather than taken from [songMenu] because the
     * heart in the player never opens a menu, so there is often nothing
     * cached to take. One extra request, on an action nobody performs in bulk.
     * A song that was never saved has no removal token and this is a no-op.
     */
    private suspend fun forgetFromLibrary(videoId: String): Boolean {
        val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return false
        val token = menu.removeFromLibraryToken?.takeIf { menu.inLibrary } ?: return false
        if (YtMusicRepository.setLibraryStatus(token).isFailure) return false
        // The menu may be the one on screen; don't leave it offering a
        // removal that has already happened.
        _songMenu.value = _songMenu.value?.copy(inLibrary = false)
        return true
    }

    /**
     * Takes an un-liked track out of the lists that exist *because* it was
     * liked — the Library tab's Liked Music section, and the Liked Music page
     * itself if it happens to be open.
     *
     * Marking the library stale isn't enough on its own: that only acts when
     * the tab is next opened, and un-liking is nearly always done from inside
     * one of these two lists, looking straight at the row. Leaving it there
     * reads as the tap not having worked — the menu says "Like" again while
     * the song sits in Liked Music.
     *
     * Only ever removes. A track liked from somewhere else doesn't get spliced
     * into a list that YouTube orders for itself; the next fetch places it.
     */
    private fun dropFromLikedLists(videoId: String) {
        val library = (_library.value as? UiState.Success)?.data
        if (library != null && library.likedSongs.any { it.videoId == videoId }) {
            _library.value = UiState.Success(
                library.copy(likedSongs = library.likedSongs.filterNot { it.videoId == videoId }),
            )
        }
        _detailStack.value = _detailStack.value.map { page ->
            val songs = (page.songs as? UiState.Success)?.data
            if (page.browseId != YtMusicRepository.LIKED_MUSIC || songs == null) {
                page
            } else {
                page.copy(
                    songs = UiState.Success(songs.filterNot { it.videoId == videoId }),
                    hiddenSongs = page.hiddenSongs.filterNot { it.videoId == videoId },
                )
            }
        }
    }

    /** A row already on screen for [videoId], newest page first, so a like can download it without a lookup. */
    private fun knownSong(videoId: String): Song? {
        _detailStack.value.asReversed().forEach { page ->
            (page.songs as? UiState.Success)?.data?.firstOrNull { it.videoId == videoId }?.let { return it }
        }
        (_library.value as? UiState.Success)?.data?.likedSongs?.firstOrNull { it.videoId == videoId }?.let { return it }
        (_history.value as? UiState.Success)?.data?.firstOrNull { it.videoId == videoId }?.let { return it }
        return null
    }

    /** The heart: liked becomes neutral, anything else becomes liked. */
    fun toggleLike(videoId: String) = setLike(
        videoId,
        if (likeStatusOf(videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE,
    )

    /** As [toggleLike], for the thumb-down. */
    fun toggleDislike(videoId: String): LikeStatus? {
        if (!requireSignIn()) return null
        val previous = likeStatusOf(videoId)
        setLike(
            videoId,
            if (previous == LikeStatus.DISLIKE) {
                LikeStatus.INDIFFERENT
            } else {
                LikeStatus.DISLIKE
            },
        )
        return previous
    }

    /**
     * Saves the album or playlist [browseId] to the library, or takes it out.
     *
     * Written to the screen first and rolled back if YouTube refuses, for the
     * same reason [setLike] is: it is one tap on a page the user is looking at,
     * and a control that waits on a round trip before it changes reads as a tap
     * that missed.
     *
     * A page with no [DetailPage.library] is one YouTube never offered to save
     * — a local page, an auto-playlist, a generated mix — and the UI has no
     * control on it to have been tapped, so this is a no-op rather than a guess.
     */
    fun toggleLibrary(browseId: String) {
        if (!requireSignIn()) return
        val current = _detailStack.value.firstOrNull { it.browseId == browseId }?.library ?: return
        val target = !current.saved
        setSavedOnPage(browseId, target)
        viewModelScope.launch {
            if (YtMusicRepository.setSaved(current.playlistId, target).isSuccess) {
                // The Library tab's Albums/Playlists shelf is now out of date.
                libraryStale = true
            } else {
                setSavedOnPage(browseId, current.saved)
            }
        }
    }

    /**
     * Subscribes to the artist page [browseId]'s channel, or unsubscribes.
     *
     * The release equivalent is [toggleLibrary], and this behaves the same way:
     * optimistic, rolled back on refusal, and a no-op on a page whose header
     * never offered a subscribe button — a signed-out response among them.
     */
    fun toggleSubscription(browseId: String) {
        if (!requireSignIn()) return
        val current = _detailStack.value
            .firstOrNull { it.browseId == browseId }?.subscription ?: return
        val target = !current.subscribed
        setSubscribedOnPage(browseId, target)
        viewModelScope.launch {
            if (YtMusicRepository.setSubscribed(current.channelId, target).isSuccess) {
                // The Library tab's Subscriptions shelf is now out of date.
                libraryStale = true
            } else {
                setSubscribedOnPage(browseId, current.subscribed)
            }
        }
    }

    /** As [setSavedOnPage], for the artist header's subscribe button. */
    private fun setSubscribedOnPage(browseId: String, subscribed: Boolean) {
        _detailStack.value = _detailStack.value.map { page ->
            val subscription = page.subscription
            if (page.browseId != browseId || subscription == null) {
                page
            } else {
                page.copy(subscription = subscription.copy(subscribed = subscribed))
            }
        }
    }

    /**
     * Restates whether a page is saved. By id rather than by index: the user may
     * have pushed or popped pages while the write was in flight.
     */
    private fun setSavedOnPage(browseId: String, saved: Boolean) {
        _detailStack.value = _detailStack.value.map { page ->
            val library = page.library
            if (page.browseId != browseId || library == null) {
                page
            } else {
                page.copy(library = library.copy(saved = saved))
            }
        }
    }

    /**
     * The open track menu's account state, or null while it is still being
     * fetched. Only one menu can be open at a time, so one slot is enough.
     */
    private val _songMenu = MutableStateFlow<SongMenu?>(null)
    val songMenu: StateFlow<SongMenu?> = _songMenu.asStateFlow()

    private var songMenuJob: Job? = null

    /** In-flight liked-library continuation sync — see [syncLikedMusic]. */
    private var likedSyncJob: Job? = null

    /**
     * Loads the account state behind an opening track menu — the library
     * tokens, and any rating the response happens to state.
     *
     * The rating is only ever taken when it *adds* something: a LIKE or a
     * DISLIKE the library couldn't have told us, such as a disliked track or
     * one liked past the tenth page of Liked Music. An INDIFFERENT is
     * discarded.
     *
     * That asymmetry is not fussiness. This lookup reads a watch queue, and a
     * watch queue routinely renders a liked track with no rating on it at all;
     * believing that silence downgraded songs sitting in Liked Music to
     * "not liked" a beat after their menu opened — the label changing under
     * the user, with no request sent and nothing removed.
     */
    fun loadSongMenu(videoId: String?) {
        songMenuJob?.cancel()
        _songMenu.value = null
        if (videoId == null || !_signedIn.value) return
        songMenuJob = viewModelScope.launch {
            val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return@launch
            _songMenu.value = menu
            LikeState.rememberStated(videoId, menu.likeStatus)
        }
    }

    /** The account's own playlists, for the picker and the library tab. */
    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()

    /** Re-fetched rather than cached for the session: playlists are edited here. */
    fun loadPlaylists() {
        if (!_signedIn.value || _playlistsLoading.value) return
        val identity = listenerKey()
        _playlistsLoading.value = true
        viewModelScope.launch {
            YtMusicRepository.userPlaylists().onSuccess { if (identity == listenerKey()) _playlists.value = it }
            if (identity == listenerKey()) _playlistsLoading.value = false
        }
    }

    /**
     * The library feed's Playlists shelf, rewritten by [edit].
     *
     * Every playlist edit has to do this by hand, because the library tab reads
     * `_library` and nothing else — [playlists] is the picker's list, not the
     * tab's — so a rename that only updated that list left the card on screen
     * still bearing the old name.
     *
     * Re-fetching instead is what this replaces, and it did not work: YouTube's
     * `FEmusic_liked_playlists` is eventually consistent, and a fetch fired the
     * moment an edit returns reliably answers with the state from *before* it.
     * So the edit was applied, the feed denied it, and the denial is what
     * reached the screen — the bug this exists to fix. The re-fetch still
     * happens, via [libraryStale], once the tab is next opened and the feed has
     * caught up.
     *
     * A shelf that isn't there yet is created by [edit] returning rows for it
     * (a first playlist has no shelf to add to), and one left empty is dropped —
     * see [LibraryScreen], which draws the create tile with or without a shelf.
     */
    private fun editPlaylistShelf(edit: (List<ShelfItem>) -> List<ShelfItem>) {
        val page = (_library.value as? UiState.Success)?.data ?: return
        val existing = page.shelves.firstOrNull { it.title == YtMusicRepository.PLAYLISTS_SHELF }
        val items = edit(existing?.items.orEmpty())
        if (items == existing?.items) return
        val shelves = when {
            existing == null && items.isEmpty() -> return
            // No shelf yet: this is the account's first playlist, so the feed
            // has never had one to send. Leads the page, as the feed orders it.
            existing == null ->
                listOf(HomeShelf(YtMusicRepository.PLAYLISTS_SHELF, items)) + page.shelves
            // Emptied by deleting the last playlist. Dropped rather than left as
            // a heading over nothing; the create tile is drawn either way.
            items.isEmpty() -> page.shelves.filterNot { it === existing }
            else -> page.shelves.map { if (it === existing) existing.copy(items = items) else it }
        }
        _library.value = UiState.Success(page.copy(shelves = shelves))
    }

    /**
     * Restates a playlist's name everywhere it is currently drawn: its card in
     * the library, the picker's list, and its own open page — header and top
     * bar both, which read [DetailPage.title].
     */
    private fun setPlaylistTitle(playlist: UserPlaylist, title: String) {
        _playlists.value = _playlists.value.map {
            if (it.playlistId == playlist.playlistId) it.copy(title = title) else it
        }
        editPlaylistShelf { items ->
            items.map { if (it.browseId == playlist.browseId) it.copy(title = title) else it }
        }
        _detailStack.value = _detailStack.value.map {
            if (it.browseId == playlist.browseId) it.copy(title = title) else it
        }
    }

    /**
     * Adds [song] to every playlist in [playlists] — the picker's ticked rows —
     * and reports how each went, once, when they all have.
     *
     * Not optimistic, unlike a rating: the picker closes on the tap and there is
     * nothing left of it to update, and a playlist that shows a track it turned
     * out not to have taken is worse than one that shows it a moment late. The
     * playlist's own page is the exception — see [addOne].
     *
     * YouTube itself has no objection to a duplicate row, so whether the track
     * is already there is checked here, against the playlist's open page or a
     * fresh fetch of it, and a real duplicate is never sent.
     *
     * One at a time rather than all at once: each check reads the playlist it
     * is about, and a burst of parallel edits is exactly what YouTube answers
     * with a rate limit. The duplicate check is per playlist, so a track
     * already in one of them is skipped there and still added to the rest.
     */
    fun addToPlaylists(
        playlists: List<UserPlaylist>,
        song: Song,
        onResult: (added: Int, alreadyThere: Int, failed: Int) -> Unit = { _, _, _ -> },
    ) {
        if (!requireSignIn() || playlists.isEmpty()) return
        viewModelScope.launch {
            val outcomes = playlists.map { addOne(it, song) }
            onResult(
                outcomes.count { it == AddOutcome.ADDED },
                outcomes.count { it == AddOutcome.ALREADY_THERE },
                outcomes.count { it == AddOutcome.FAILED },
            )
        }
    }

    private enum class AddOutcome { ADDED, ALREADY_THERE, FAILED }

    private suspend fun addOne(playlist: UserPlaylist, song: Song): AddOutcome {
        val openSongs = (_detailStack.value.firstOrNull { it.browseId == playlist.browseId }
            ?.songs as? UiState.Success)?.data
        val known = openSongs
            ?: YtMusicRepository.allSongs(playlist.browseId).getOrNull()
        if (known?.any { it.videoId == song.videoId } == true) return AddOutcome.ALREADY_THERE
        return YtMusicRepository.addToPlaylist(playlist.playlistId, listOf(song.videoId)).fold(
            onSuccess = { added ->
                libraryStale = true
                // The playlist's page may be open behind the picker — it is
                // reachable from a row's own menu on it — so the track goes
                // into it for the same reason [addSuggestedSong] does.
                appendToOpenPlaylist(playlist.browseId, song, added[song.videoId])
                AddOutcome.ADDED
            },
            onFailure = { AddOutcome.FAILED },
        )
    }

    /**
     * Creates a playlist, seeded with [song] when the flow started from a
     * track's menu — one request, so it can't half-succeed into an empty
     * playlist the user has to add to again.
     */
    fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        song: Song? = null,
        /** Told when YouTube refused the write, so the surface can say so. */
        onFailure: () -> Unit = {},
    ) {
        if (!requireSignIn()) return
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        viewModelScope.launch {
            YtMusicRepository.createPlaylist(
                title = name,
                privacy = privacy,
                videoIds = listOfNotNull(song?.videoId),
            ).fold(
                onSuccess = { playlistId ->
                    // Nothing to look up for a playlist this account has just
                    // made: it is the owner by construction, so its card is
                    // editable the moment it appears rather than one request
                    // after someone holds it.
                    setPlaylistOwned("VL$playlistId", true)
                    libraryStale = true
                    val created = UserPlaylist(
                        playlistId = playlistId,
                        title = name,
                        // Only what this request itself establishes. Both
                        // surfaces that draw it leave a blank one out, so an
                        // unseeded playlist gets a card of just its name rather
                        // than a guess at what the feed will call it.
                        subtitle = if (song != null) quantity(R.plurals.song_count_plural, 1) else "",
                        thumbnailUrl = song?.thumbnailUrl,
                    )
                    // Drawn from what was just sent rather than waited for: the
                    // library feed does not have this playlist yet, and the
                    // fetch that used to run here answered without it — see
                    // [editPlaylistShelf]. Leads the shelf because it is the
                    // newest, which is the order the feed itself comes in.
                    _playlists.value = listOf(created) +
                        _playlists.value.filterNot { it.playlistId == created.playlistId }
                    editPlaylistShelf { items ->
                        listOf(
                            ShelfItem(
                                title = created.title,
                                subtitle = created.subtitle,
                                thumbnailUrl = created.thumbnailUrl,
                                videoId = null,
                                browseId = created.browseId,
                            ),
                        ) + items.filterNot { it.browseId == created.browseId }
                    }
                },
                onFailure = { onFailure() },
            )
        }
    }

    fun createPlaylistWithVideoIds(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
        songs: List<Song> = emptyList(),
        /** [savedLocally] is true when the playlist went to this device, not YouTube Music. */
        onResult: ((browseId: String?, title: String, savedLocally: Boolean) -> Unit)? = null,
    ) {
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        viewModelScope.launch {
            if (authStore.isSignedIn) {
                val initialBatch = videoIds.take(50)
                YtMusicRepository.createPlaylist(
                    title = name,
                    privacy = privacy,
                    videoIds = initialBatch,
                ).fold(
                    onSuccess = { playlistId ->
                        if (videoIds.size > 50) {
                            videoIds.drop(50).chunked(50).forEach { chunk ->
                                YtMusicRepository.addToPlaylist(playlistId, chunk)
                            }
                        }
                        setPlaylistOwned("VL$playlistId", true)
                        libraryStale = true
                        val created = UserPlaylist(
                            playlistId = playlistId,
                            title = name,
                            subtitle = quantity(R.plurals.song_count_plural, videoIds.size),
                            thumbnailUrl = null,
                        )
                        _playlists.value = listOf(created) +
                            _playlists.value.filterNot { it.playlistId == created.playlistId }
                        editPlaylistShelf { items ->
                            listOf(
                                ShelfItem(
                                    title = created.title,
                                    subtitle = created.subtitle,
                                    thumbnailUrl = created.thumbnailUrl,
                                    videoId = null,
                                    browseId = created.browseId,
                                ),
                            ) + items.filterNot { it.browseId == created.browseId }
                        }
                        onResult?.invoke(created.browseId, created.title, false)
                    },
                    onFailure = {
                        val local = com.music.bitchord.data.spotify.LocalPlaylistStore.savePlaylist(name, songs)
                        onResult?.invoke(local.browseId, local.title, true)
                    },
                )
            } else {
                val local = com.music.bitchord.data.spotify.LocalPlaylistStore.savePlaylist(name, songs)
                onResult?.invoke(local.browseId, local.title, true)
            }
        }
    }

    /**
     * Drops [song] from the playlist page it is being read on, and takes the
     * row out from under the reader rather than waiting for a re-fetch.
     */
    fun removeFromPlaylist(
        browseId: String,
        song: Song,
        /** Told when YouTube refused the write, so the surface can say so. */
        onFailure: () -> Unit = {},
    ) {
        val setVideoId = song.setVideoId ?: return
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        viewModelScope.launch {
            YtMusicRepository.removeFromPlaylist(
                playlistId,
                listOf(setVideoId to song.videoId),
            ).fold(
                onSuccess = {
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        val songs = (page.songs as? UiState.Success)?.data
                        if (page.browseId != browseId || songs == null) {
                            page
                        } else {
                            page.copy(
                                songs = UiState.Success(
                                    songs.filterNot { it.setVideoId == setVideoId },
                                ),
                            )
                        }
                    }
                },
                onFailure = { onFailure() },
            )
        }
    }

    /**
     * Adds one of [DetailPage.suggestedSongs] to the playlist it was suggested
     * for: out of that section, and into the track list above it.
     *
     * Both halves, because either alone is a worse answer than doing nothing.
     * Only removing it — which is what this used to do — reads as the track
     * having been discarded rather than added: it leaves the Suggested list and
     * turns up nowhere, and the playlist it was added to looks unchanged until
     * the page is closed and reopened. Only adding it would leave YouTube still
     * suggesting a track that is now in the playlist.
     *
     * The row goes in complete, per-entry id included, because
     * [YtMusicRepository.addToPlaylist] reports the one it was just filed
     * under — so "Remove from this playlist" works on it immediately rather
     * than after a re-fetch. A response that named no id still adds the row;
     * it just can't offer to take it back out yet.
     */
    fun addSuggestedSong(
        browseId: String,
        song: Song,
        /** Told when YouTube refused the write, so the surface can say so. */
        onFailure: () -> Unit = {},
    ) {
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        viewModelScope.launch {
            YtMusicRepository.addToPlaylist(playlistId, listOf(song.videoId)).fold(
                onSuccess = { added ->
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId != browseId) {
                            page
                        } else {
                            page.copy(
                                suggestedSongs = page.suggestedSongs
                                    .filterNot { it.videoId == song.videoId },
                            )
                        }
                    }
                    appendToOpenPlaylist(browseId, song, added[song.videoId])
                },
                onFailure = { onFailure() },
            )
        }
    }

    /**
     * Puts [song] at the end of the playlist page at [browseId], if that page
     * is open — where YouTube itself puts it, so the order survives the next
     * fetch.
     *
     * An empty playlist counts as open: it renders as an empty-state message, and the
     * first track added to one has to replace that message rather than be
     * dropped for want of a list to join. Only that message, though — any other
     * error is a page that failed to load, whose real contents are unknown, and
     * answering it with a one-track listing would be a playlist invented out of
     * a network failure. A page still loading is left alone too: the fetch in
     * flight is newer than this and will land with the addition already in it.
     */
    private fun appendToOpenPlaylist(browseId: String, song: Song, setVideoId: String?) {
        val added = song.copy(setVideoId = setVideoId)
        _detailStack.value = _detailStack.value.map { page ->
            if (page.browseId != browseId) return@map page
            val songs = when (val state = page.songs) {
                is UiState.Success -> state.data
                is UiState.Error -> if (state.message == text(R.string.no_tracks_here)) emptyList() else return@map page
                UiState.Loading -> return@map page
            }
            // Already there — a track added twice is two real entries on
            // YouTube's side, but a duplicate row from a double tap is not
            // something the user asked for.
            if (songs.any { it.videoId == song.videoId }) return@map page
            page.copy(
                songs = UiState.Success(
                    songs + added.copy(
                        thumbnailUrl = added.thumbnailUrl ?: page.thumbnailUrl,
                    ),
                ),
            )
        }
    }

    /**
     * Renames a playlist, and says so everywhere it is named — see
     * [setPlaylistTitle]. Renaming is nearly always done from the playlist's
     * own page or its card, so there is always something on screen still
     * showing the old name.
     */
    fun renamePlaylist(
        playlist: UserPlaylist,
        title: String,
        /** Told when YouTube refused the write, so the surface can say so. */
        onFailure: () -> Unit = {},
    ) {
        if (!requireSignIn()) return
        val name = title.trim()
        if (name.isBlank() || name == playlist.title) return
        viewModelScope.launch {
            YtMusicRepository.renamePlaylist(playlist.playlistId, name).fold(
                onSuccess = {
                    setPlaylistTitle(playlist, name)
                    libraryStale = true
                },
                onFailure = { onFailure() },
            )
        }
    }

    /**
     * The playlist's entries as they stand on YouTube, for the reorder page —
     * see [YtMusicRepository.playlistEntries] for why not the open page's list.
     */
    fun loadPlaylistEntries(playlist: UserPlaylist, onResult: (Result<List<Song>>) -> Unit) {
        if (!requireSignIn()) return onResult(Result.failure(IllegalStateException("signed out")))
        viewModelScope.launch {
            onResult(YtMusicRepository.playlistEntries(playlist.browseId))
        }
    }

    /**
     * Saves a new running order for [playlist], sent as the fewest moves that
     * get from [original] to [reordered], and puts the open page — if this
     * playlist's is — into that order straight away rather than after a
     * re-fetch the feed may answer with the old order.
     */
    fun reorderPlaylist(
        playlist: UserPlaylist,
        original: List<Song>,
        reordered: List<Song>,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!requireSignIn()) return onResult(false)
        viewModelScope.launch {
            YtMusicRepository.reorderPlaylist(
                playlist.playlistId,
                current = original.mapNotNull { it.setVideoId },
                target = reordered.mapNotNull { it.setVideoId },
            ).fold(
                onSuccess = {
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId != playlist.browseId || page.songs !is UiState.Success) {
                            page
                        } else {
                            page.copy(songs = UiState.Success(reordered.withArtwork(page.thumbnailUrl)))
                        }
                    }
                    onResult(true)
                },
                onFailure = { onResult(false) },
            )
        }
    }

    /**
     * Renames a playlist that lives only on this device, the counterpart to
     * [renamePlaylist] for one made without an account (a Spotify import, or
     * one saved while signed out). The store owns the change; the open page,
     * if any, is restated here.
     */
    fun renameLocalPlaylist(browseId: String, title: String) {
        val name = title.trim()
        if (name.isBlank()) return
        com.music.bitchord.data.spotify.LocalPlaylistStore.renamePlaylist(browseId, name)
        _detailStack.value = _detailStack.value.map { page ->
            if (page.browseId == browseId) page.copy(title = name) else page
        }
    }

    /** Deletes a device-only playlist, closing its page when it is the one open. */
    fun deleteLocalPlaylist(browseId: String) {
        com.music.bitchord.data.spotify.LocalPlaylistStore.deletePlaylist(browseId)
        val before = _detailStack.value.size
        _detailStack.value = _detailStack.value.filterNot { it.browseId == browseId }
        if (_detailStack.value.size != before) navigator.onDetailStackShrunk(_detailStack.value.size)
    }

    fun deletePlaylist(
        playlist: UserPlaylist,
        /** Told when YouTube refused the write, so the surface can say so. */
        onFailure: () -> Unit = {},
    ) {
        if (!requireSignIn()) return
        viewModelScope.launch {
            YtMusicRepository.deletePlaylist(playlist.playlistId).fold(
                onSuccess = {
                    _playlists.value = _playlists.value
                        .filterNot { it.playlistId == playlist.playlistId }
                    // The card in the library tab, which is the surface the
                    // deletion was almost certainly ordered from — and which the
                    // re-fetch that used to stand in for this left in place; see
                    // [editPlaylistShelf].
                    editPlaylistShelf { items ->
                        items.filterNot { it.browseId == playlist.browseId }
                    }
                    // Its page may be the one open; a deleted playlist has
                    // nothing left to show.
                    _detailStack.value = _detailStack.value
                        .filterNot { it.browseId == playlist.browseId }
                    navigator.onDetailStackShrunk(_detailStack.value.size)
                    libraryStale = true
                },
                onFailure = { onFailure() },
            )
        }
    }

    /**
     * Whether [browseId] is a playlist this account can be asked to edit.
     *
     * Only ever yes for a playlist [playlistOwned] has confirmed the account
     * made. "In this account's library" is not the same thing and cannot stand
     * in for it: `FEmusic_liked_playlists` lists a playlist saved from someone
     * else in exactly the shape it lists one this account created, so a lookup
     * in [playlists] alone called a stranger's playlist editable and the menus
     * offered Rename and Delete on it — neither of which YouTube would have
     * honoured.
     *
     * Strict rather than permissive-until-proven, so that every surface gives
     * the same answer for the same playlist. The permissive version was right on
     * a playlist's own page — where the page load supplies the answer — and
     * wrong on a card until that page had been opened once, which is a menu that
     * changes its mind about what a playlist is depending on where it is held.
     */
    fun editablePlaylist(browseId: String?): UserPlaylist? {
        if (browseId == null || _playlistOwned.value[browseId] != true) return null
        return _playlists.value.firstOrNull { it.browseId == browseId }
    }

    /**
     * Which playlists in this account's library the account actually made, by
     * browse id — see
     * [com.music.bitchord.data.innertube.InnertubeParser.parsePlaylistOwned].
     * An id absent from the map is one nothing has asked about yet, which is not
     * the same as a no.
     *
     * Observable, because the answer routinely arrives after whatever wanted it
     * is already on screen: a card's menu opens with nothing fetched, and the
     * rows that depend on this appear as [resolvePlaylistOwnership] answers.
     */
    private val _playlistOwned = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val playlistOwned: StateFlow<Map<String, Boolean>> = _playlistOwned.asStateFlow()

    /**
     * Finds out who made the playlist at [browseId], if it isn't already known.
     *
     * Only the playlist's own page states this, so a surface that has no page —
     * a card in the library, a search result — has to ask for one. Which is why
     * this is on demand rather than swept up front: the alternative is a request
     * per playlist every time the library loads, for a question most of them
     * will never be asked.
     *
     * Silent about anything that isn't a playlist in this account's library.
     * Nothing else can be renamed or deleted whatever the answer, so asking
     * would be a request spent to rule out what was never on offer.
     */
    fun resolvePlaylistOwnership(browseId: String?) {
        if (!_signedIn.value || browseId == null) return
        if (browseId in _playlistOwned.value || browseId in ownershipInFlight) return
        if (_playlists.value.none { it.browseId == browseId }) return
        ownershipInFlight += browseId
        viewModelScope.launch {
            YtMusicRepository.playlistOwned(browseId).onSuccess { owned ->
                if (owned != null) setPlaylistOwned(browseId, owned)
            }
            // Released either way. A failed lookup that stayed marked would
            // never be retried, leaving Rename off the user's own playlist for
            // the rest of the session over one dropped request.
            ownershipInFlight -= browseId
        }
    }

    /** Guards against a second lookup while the first is still out. */
    private val ownershipInFlight = mutableSetOf<String>()

    private fun setPlaylistOwned(browseId: String, owned: Boolean) {
        _playlistOwned.value = _playlistOwned.value + (browseId to owned)
    }


    /**
     * Guards every account write. All of them are signed-in-only, and the UI
     * hides them for guests — this is the backstop for a session that expired
     * between the menu opening and the tap.
     */
    private fun requireSignIn(): Boolean = _signedIn.value

    /**
     * Whether the library needs re-fetching. Set by every write above and
     * acted on when the tab is next opened, for the same reason [homeStale]
     * exists: rearranging a page under whoever is reading it is worse than
     * showing it a moment out of date.
     */
    private var libraryStale = false

    /** Call when the library tab becomes visible. */
    fun onLibraryShown() {
        loadPlaylists()
        if (_signedIn.value && _library.value is UiState.Loading && libraryLoadJob?.isActive != true) loadLibrary()
        if (!libraryStale) return
        libraryStale = false
        if (_library.value is UiState.Success) refresh(Feed.LIBRARY)
    }

    init {
        startSearchPipeline()
        startSuggestPipeline()
        startTypeaheadMediaPipeline()
        loadHome()
        // Explore, Library and the account menus are none of them on the first
        // screen, and each one starts network work. The two feeds load only
        // from the tab that shows them (onExploreShown/onLibraryShown), so a
        // launch does not spend requests on a page the listener never opens.
        // What is left here is the account identity and the once-a-day update
        // check — neither blocks or competes with Home's first row.
        viewModelScope.launch {
            delay(STARTUP_DEFER_MS)
            for (work in startupDeferredWork(_signedIn.value)) {
                when (work) {
                    StartupDeferredWork.ACCOUNT -> loadAccount()
                    StartupDeferredWork.UPDATE_CHECK -> {
                        // A leftover APK only means "Install Now" for the session
                        // that downloaded it ??see AppUpdateChecker.clearCache.
                        AppUpdateChecker.clearCache(getApplication())
                        // The GitHub round trip is not part of opening the app:
                        // ask at most once a day rather than on every cold start.
                        AppUpdateChecker.checkDaily(getApplication())
                    }
                }
            }
        }
        viewModelScope.launch {
            // drop(1): the current value is just the count so far, not a play.
            PlaybackTracker.registeredPlays.drop(1).collect { homeStale = true }
        }
        viewModelScope.launch {
            // Recents is the device's own record first, so a counted play
            // repaints the shelf from that record — no request and no polling.
            LocalPlayHistory.changes().collect {
                recentsLog("recents: event -> refresh")
                refreshRecentsFromLocal()
            }
        }
        viewModelScope.launch {
            // AppSettings has already reset the gate when the route changed;
            // ask once more through the new route and repaint both feeds
            // rather than leaving yesterday's GATED answer on screen.
            AppSettings.proxyRevision.drop(1).collect {
                // A failing re-check (proxy unreachable, wrong password) must
                // show as an error on Home, never take the app down.
                try {
                    YtMusicRepository.recheckGateNow()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    com.music.bitchord.data.DebugLog.w("BitChord", "proxy re-check failed: ${failure.message}")
                }
                // The answers this route gave before may be the ones it gated.
                invalidateSearchCaches()
                refresh(Feed.HOME, force = true, replaceShelves = true, reason = "proxy")
                refresh(Feed.EXPLORE)
            }
        }
        viewModelScope.launch {
            BoundedContextRequests.snapshot.collect { snapshot ->
                snapshot?.let(::applyBoundedContextSnapshot)
            }
        }
        viewModelScope.launch {
            AppSettings.filterNonMusicAudio.drop(1).collect {
                if (_detailStack.value.any { page -> page.browseId == "local:all" }) {
                    reloadLocalDetail("local:all")
                }
            }
        }
        viewModelScope.launch {
            // Emptied from Settings while the folder sits open underneath.
            AudioCache.contentsChanged.drop(1).collect {
                if (_detailStack.value.any { page -> page.browseId == CACHE_FOLDER_BROWSE_ID }) {
                    reloadLocalDetail(CACHE_FOLDER_BROWSE_ID)
                }
            }
        }
        viewModelScope.launch {
            AppSettings.webdavUrl.drop(1).collect {
                reloadRemoteDetail(com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID)
            }
        }
        viewModelScope.launch {
            combine(
                AppSettings.smbHost,
                AppSettings.smbShare,
                AppSettings.smbBasePath,
                AppSettings.smbUsername,
                AppSettings.smbPassword,
            ) { fields -> fields.toList() }
                .drop(1)
                .debounce(300)
                .collect { reloadRemoteDetail(com.music.bitchord.data.smb.SmbConfig.BROWSE_ID) }
        }
        viewModelScope.launch {
            // Moods and charts are the country's; a new one means a new page.
            AppSettings.contentCountry.drop(1).collect {
                if (_explore.value !is UiState.Loading) loadExplore()
            }
        }
        viewModelScope.launch {
            // Hiding or restoring a category repaints the grid from the last
            // answer rather than spending another network request.
            AppSettings.hiddenExploreCategories.drop(1).collect {
                if (rawExploreSections.isNotEmpty()) publishExplore()
            }
        }
    }

    /**
     * Whether a play has been registered since the home feed was last fetched.
     *
     * The feed leads with listening history, so it's out of date the moment a
     * track starts — but re-fetching there would rearrange the page under
     * whoever is reading it, and the tab is usually in the background anyway.
     * It's re-fetched when the tab is next opened instead.
     */
    private var homeStale = false

    /** Call when the home tab becomes visible. */
    fun onHomeShown() {
        if (!homeStale) return
        homeStale = false
        // A first load already in flight will pick the new play up by itself.
        if (_home.value is UiState.Success) refresh(Feed.HOME, reason = "tab")
    }

    /**
     * Call when the explore tab becomes visible.
     *
     * Its page is no longer fetched during init, so this is what makes the tab
     * show itself the first time. A page already loaded (or failing, which the
     * screen offers a retry for) is left alone.
     */
    fun onExploreShown() {
        if (_explore.value is UiState.Loading && exploreLoadJob?.isActive != true) loadExplore()
    }

    private fun loadAccount() {
        val identity = listenerKey()
        viewModelScope.launch {
            val account = YtMusicRepository.account().getOrNull()
            if (identity != listenerKey()) return@launch
            // A refused lookup (Korea, no Premium) keeps the header the
            // sign-in already set rather than blanking it.
            if (account == null) return@launch
            val mergedAccount = mergeAccountIdentity(
                _account.value ?: Account(name = "", email = "", thumbnailUrl = null),
                account,
            )
            _account.value = mergedAccount
            // A channel picked in the in-app browser arrives as ids and nothing
            // else — the page's `ytcfg` never says what the channel is called.
            // The account menu, asked *as* that channel, answers with its name,
            // which is what the settings row needs to show. It also fills in the
            // generic sign-in header once youtube.com can name the account.
            if (_selectedChannelKey.value != null) _selectedChannelName.value = mergedAccount.name
            val accountId = _activeAccountId.value ?: return@launch
            val profileId = _activeProfileId.value ?: return@launch
            val session = authStore.sessions.firstOrNull { it.accountId == accountId } ?: return@launch
            val profile = session.profiles.firstOrNull { it.profileId == profileId }
            val mergedSession = mergeAccountIdentity(
                Account(name = session.name, email = session.email, thumbnailUrl = null),
                account,
            )
            val mergedProfile = mergeAccountIdentity(
                profile?.let { Account(it.name, "", it.avatar, it.handle) }
                    ?: Account(name = "", email = "", thumbnailUrl = null),
                account.copy(handle = account.handle?.takeIf { it.isNotBlank() } ?: account.email),
            )
            val updated = session.copy(
                name = mergedSession.name,
                email = mergedSession.email,
                profiles = session.profiles.map { saved ->
                    if (saved.profileId == profileId) saved.copy(
                        name = mergedProfile.name,
                        handle = mergedProfile.handle.orEmpty(),
                        avatar = mergedProfile.thumbnailUrl,
                    ) else saved
                },
            )
            if (updated != session) {
                authStore.upsertSession(updated, activate = false)
                _googleAccounts.value = authStore.sessions
            }
        }
    }

    /**
     * A feed that can be pulled down to refresh. Tracked per feed rather than
     * as one flag: a pull on Library while Home is still refreshing in the
     * background shouldn't leave the wrong tab showing a loader.
     */
    enum class Feed { HOME, EXPLORE, LIBRARY }

    private val _refreshing = MutableStateFlow(emptySet<Feed>())
    val refreshing: StateFlow<Set<Feed>> = _refreshing.asStateFlow()

    /** The refresh in flight for each feed; a pull while one runs joins it. */
    private val refreshJobs = ConcurrentHashMap<Feed, Job>()
    private val moodGenreArtworkJobs = ConcurrentHashMap<String, Job>()
    private val refreshLock = Any()

    /**
     * Re-fetches [feed] in place. Unlike the `load*` entry points this leaves
     * the current content on screen rather than dropping back to the loading
     * state — a refresh that swapped the page for a spinner would be a worse
     * experience than the stale content it replaces.
     *
     * A second pull while the same feed is already refreshing joins that
     * request instead of starting another one, so two answers cannot race to
     * replace each other.
     *
     * [replaceShelves] drops every previous Home shelf even if the new feed
     * comes from the same source: the proxy changed the route out from under
     * them, so what was on screen is no longer that route's answer.
     */
    fun refresh(
        feed: Feed,
        force: Boolean = false,
        replaceShelves: Boolean = false,
        reason: String = "tab",
    ) {
        if (feed == Feed.LIBRARY && !_signedIn.value) return
        val identity = listenerKey()
        var started: Job? = null
        synchronized(refreshLock) {
            // A first-page load is already asking the same endpoints. Joining
            // it is what stops the launch load and the tab-shown refresh from
            // sending the core feed and the recently-played request twice.
            // A forced refresh (pull, or a proxy route change) still runs: it
            // exists to replace what the old route answered, not to join it.
            if (feed == Feed.HOME && !force && homeLoadJob?.isActive == true) return
            if (refreshJobs[feed]?.isCompleted == false) return
            _refreshing.value = _refreshing.value + feed
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    when (feed) {
                        Feed.HOME -> refreshHome(identity, force, replaceShelves, reason)
                        Feed.EXPLORE -> fetchExplore()
                        Feed.LIBRARY -> fetchLibrary(identity)
                    }
                } finally {
                    _refreshing.value = _refreshing.value - feed
                    coroutineContext[Job]?.let { refreshJobs.remove(feed, it) }
                }
            }
            refreshJobs[feed] = job
            started = job
        }
        started?.start()
    }

    fun loadExplore() {
        rawExploreSections = emptyList()
        _explore.value = UiState.Loading
        exploreLoadJob?.cancel()
        exploreLoadJob = viewModelScope.launch { fetchExplore() }
    }

    private suspend fun fetchExplore() {
        YtMusicRepository.moodAndGenres().fold(
            onSuccess = { sections ->
                rawExploreSections = sections
                publishExplore()
            },
            onFailure = {
                rawExploreSections = emptyList()
                _explore.value = UiState.Error(it.friendly())
            },
        )
    }

    /**
     * Applies the hidden-category filter to the last answer and appends the
     * charts card. Kept separate from the fetch so a change in Settings takes
     * effect at once without another network request.
     */
    private fun publishExplore() {
        val visible = ExploreCategoryFilter.visible(
            rawExploreSections,
            AppSettings.hiddenExploreCategories.value,
        )
        // The Music channel's own card closes the grid, after the curated
        // moods and genres, and stays even when everything above it is hidden.
        val charts = MoodGenre(
            title = text(R.string.charts),
            browseId = Innertube.CHARTS_BROWSE_ID,
            params = null,
            stripeColor = 0xFFE8304CL,
        )
        _explore.value = UiState.Success(
            visible + MoodGenreSection(text(R.string.charts), listOf(charts)),
        )
    }

    /**
     * Resolves the cover of one tile the grid has scrolled into view. A tile
     * already on screen keeps its one request, and the repository caps the
     * three-at-a-time budget; a settled cover is persisted for a day.
     */
    fun loadMoodGenreArtwork(item: MoodGenre) {
        if (item.thumbnailUrl != null) return
        val key = "${item.browseId}:${item.params.orEmpty()}"
        if (moodGenreArtworkJobs[key]?.isCompleted == false) return
        val job = viewModelScope.launch {
            val artwork = YtMusicRepository.moodGenreArtwork(item.browseId, item.params).getOrNull()
                ?: return@launch
            if (rawExploreSections.isEmpty()) return@launch
            rawExploreSections = rawExploreSections.map { section ->
                section.copy(items = section.items.map { currentItem ->
                    if (currentItem.browseId == item.browseId && currentItem.params == item.params) {
                        currentItem.copy(thumbnailUrl = artwork)
                    } else {
                        currentItem
                    }
                })
            }
            publishExplore()
        }
        moodGenreArtworkJobs[key] = job
        job.invokeOnCompletion { moodGenreArtworkJobs.remove(key, job) }
    }

    fun openMoodGenre(item: MoodGenre) {
        _selectedMoodGenre.value = item
        _moodGenreShelves.value = UiState.Loading
        chartsJob?.cancel()
        if (item.browseId == Innertube.CHARTS_BROWSE_ID) {
            loadCharts(AppSettings.chartsCountry.value)
            return
        }
        _chartCountries.value = null
        // Held like the charts' load, so a slow category answering after the
        // listener has moved on cannot paint over the page now open.
        chartsJob = viewModelScope.launch {
            _moodGenreShelves.value = YtMusicRepository.moodGenreShelves(item.browseId, item.params).fold(
                onSuccess = { shelves ->
                    if (shelves.isEmpty()) UiState.Error(text(R.string.nothing_to_explore))
                    else UiState.Success(shelves)
                },
                onFailure = { UiState.Error(it.friendly()) },
            )
        }
    }

    fun closeMoodGenre(): Boolean {
        if (_selectedMoodGenre.value == null) return false
        _selectedMoodGenre.value = null
        chartsJob?.cancel()
        _chartCountries.value = null
        return true
    }

    /** Shows the charts for [country] (a code the page offered) and remembers the pick. */
    fun selectChartsCountry(country: String) {
        if (_selectedMoodGenre.value?.browseId != Innertube.CHARTS_BROWSE_ID) return
        AppSettings.setChartsCountry(country)
        _moodGenreShelves.value = UiState.Loading
        loadCharts(country)
    }

    private fun loadCharts(country: String?) {
        chartsJob?.cancel()
        chartsJob = viewModelScope.launch {
            val result = YtMusicRepository.charts(country)
            result.getOrNull()?.let { page ->
                // Keep a picked country selected while the page leaves it unsaid.
                _chartCountries.value = (page.country ?: country) to page.countries
            }
            _moodGenreShelves.value = result.fold(
                onSuccess = { page ->
                    if (page.shelves.isEmpty()) UiState.Error(text(R.string.nothing_to_explore))
                    else UiState.Success(page.shelves)
                },
                onFailure = { UiState.Error(it.friendly()) },
            )
        }
    }

    /** Tapping a tab should leave any pushed page behind. */
    fun clearDetail() {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
        navigator.onDetailStackShrunk(0)
    }

    /** The Home error state's Retry button: a tab load, not a fresh launch. */
    fun retryHome() = loadHome("tab")

    /** A full load; [reason] names the trigger in the log — see [refreshHome]. */
    fun loadHome(reason: String = "startup") {
        com.music.bitchord.data.TrackLog.d("BitChord", "home: refresh by $reason")
        val identity = listenerKey()
        val generation = homeLoadGeneration.incrementAndGet()
        _home.value = UiState.Loading
        homeContinuation = null
        homeSeenTitles.clear()
        homeSource = null
        _homeLoadingMore.value = false
        _homeRecentlyPlayedLoading.value = true
        // The core feed plus one browse per supplement. Recently played is left
        // out: it has its own skeleton at the head of the page rather than the
        // one at the tail.
        _homePendingShelves.value = 1 + YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.size
        homeLoadJob = viewModelScope.launch {
            launch {
                try {
                    YtMusicRepository.home()
                        .onSuccess { feed ->
                            if (!isCurrentHomeLoad(identity, generation)) return@onSuccess
                            homeContinuation = feed.continuation
                            homeSource = feed.source
                            publishHomeShelves(feed.shelves)
                            logRecentsPublished("feed", feed.shelves)
                            // A gated feed can be ten minutes old, and its
                            // Recents must still lead with the latest local play.
                            refreshRecentsFromLocal()
                        }
                        .onFailure { failure ->
                            if (isCurrentHomeLoad(identity, generation) && _home.value !is UiState.Success) {
                                _home.value = UiState.Error(failure.friendly())
                            }
                        }
                } finally {
                    homeShelfRequestSettled(identity, generation)
                }
            }
            // Not signed-in gated: the local half of Recents needs no account.
            launch {
                YtMusicRepository.homeRecentlyPlayed()
                    .onSuccess { shelf ->
                        if (isCurrentHomeLoad(identity, generation)) {
                            _homeRecentlyPlayedLoading.value = false
                            shelf?.let {
                                publishHomeShelves(listOf(it), prepend = true)
                                logRecentsPublished("load", listOf(it))
                            }
                        }
                    }
                    .onFailure {
                        if (isCurrentHomeLoad(identity, generation)) _homeRecentlyPlayedLoading.value = false
                    }
            }
            YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.forEach { browseId ->
                launch {
                    try {
                        YtMusicRepository.homeSupplement(browseId).onSuccess { shelves ->
                            if (isCurrentHomeLoad(identity, generation)) publishHomeShelves(shelves)
                        }
                    } finally {
                        homeShelfRequestSettled(identity, generation)
                    }
                }
            }
        }
    }

    /**
     * One of the parallel first-page requests has finished. Ignored once a newer
     * load has taken over, which has already reset the count for its own fan-out.
     */
    private fun homeShelfRequestSettled(identity: String?, generation: Long) {
        if (!isCurrentHomeLoad(identity, generation)) return
        _homePendingShelves.value = (_homePendingShelves.value - 1).coerceAtLeast(0)
    }

    private fun isCurrentHomeLoad(identity: String?, generation: Long) =
        identity == listenerKey() && generation == homeLoadGeneration.get()

    /**
     * Repaints Recents from the device's own record after a counted play.
     *
     * The shelf is rebuilt from local plays only, with whatever account rows
     * are already on screen kept after them: no feed request, no cover lookup,
     * no polling. A page that has not loaded yet is left for its own load.
     */
    private suspend fun refreshRecentsFromLocal() {
        val state = _home.value
        if (state !is UiState.Success) {
            recentsLog("recents: refresh skipped, home not Success")
            return
        }
        val shown = state.data
        val index = shown.indexOfFirst { isRecentsTitle(it.title) }
        val previous = shown.getOrNull(index)?.items.orEmpty()
        val shelf = YtMusicRepository.localRecentShelf(previous)?.takeIf { it.items.isNotEmpty() }
        if (shelf == null) {
            recentsLog("recents: refresh skipped, empty local shelf")
            return
        }
        // Other shelves keep arriving while the page loads, so the page is
        // re-read rather than required to be unchanged: the local shelf does
        // not depend on them. Only a page that stopped being a page (sign-out,
        // a reload in progress) is left alone.
        val currentState = _home.value
        if (currentState !is UiState.Success) {
            recentsLog("recents: refresh skipped, page replaced")
            return
        }
        val current = currentState.data
        val top = shelf.items.firstOrNull()?.title.orEmpty()
        val currentIndex = current.indexOfFirst { isRecentsTitle(it.title) }
        if (currentIndex < 0) {
            homeSeenTitles.add(RECENTS_TITLE.lowercase(Locale.ROOT))
            _home.value = UiState.Success(listOf(shelf) + current)
            recentsLog("recents: shelf repainted top=$top index=0")
        } else {
            _home.value = UiState.Success(
                current.toMutableList().apply { set(currentIndex, shelf) },
            )
            recentsLog("recents: shelf repainted top=$top index=$currentIndex")
        }
    }

    /** The Recents trail, tagged so one exported log reads as a single sequence. */
    private fun recentsLog(message: String) {
        com.music.bitchord.data.DebugLog.i("BitChord", message)
    }

    /** Logs the Recents shelf a load, refresh, or the feed itself just published. */
    private fun logRecentsPublished(source: String, shelves: List<HomeShelf>) {
        val shelf = shelves.firstOrNull { isRecentsTitle(it.title) } ?: return
        recentsLog("recents: published by $source top=${shelf.items.firstOrNull()?.title.orEmpty()}")
    }

    private fun isRecentsTitle(title: String): Boolean =
        title.trim().equals(RECENTS_TITLE, ignoreCase = true)

    private fun publishHomeShelves(shelves: List<HomeShelf>, prepend: Boolean = false) {
        val existing = (_home.value as? UiState.Success)?.data.orEmpty()
        if (prepend) {
            // YouTube's core home can also contain a stale "Recently played"
            // shelf. The dedicated history endpoint wins and replaces it.
            val replacing = shelves.map { it.title.lowercase(Locale.ROOT) }.toSet()
            homeSeenTitles.addAll(replacing)
            _home.value = UiState.Success(shelves + existing.filterNot {
                it.title.lowercase(Locale.ROOT) in replacing
            })
            return
        }
        val added = shelves.withoutRepeatsOf(existing)
            .filter { homeSeenTitles.add(it.title.lowercase(Locale.ROOT)) }
        if (added.isNotEmpty()) _home.value = UiState.Success(existing + added)
    }

    /**
     * Refreshes the core feed without blanking the current Play page first.
     *
     * A refresh is smaller than the first load (no supplements) and the
     * youtube.com fallback can answer short when one of its requests fails, so
     * the answer is merged with what is already on screen: shelves it did not
     * return stay instead of disappearing until the next full load. The
     * continuation belongs to the previous feed, so it is dropped before the
     * fetch and only the new page's token is kept; a [loadMoreHome] already in
     * flight is discarded by the generation bump.
     *
     * [force] is set by pull-to-refresh only. The automatic refresh when the
     * tab is shown again leaves it false, so the ten-minute gated feed cache
     * still answers there.
     */
    private suspend fun refreshHome(
        identity: String?,
        force: Boolean = false,
        replaceShelves: Boolean = false,
        reason: String = "tab",
    ) = coroutineScope {
        com.music.bitchord.data.TrackLog.d("BitChord", "home: refresh by $reason")
        val generation = homeLoadGeneration.incrementAndGet()
        homeContinuation = null
        _homeLoadingMore.value = false
        // Any fan-out of a load this refresh superseded can no longer settle
        // (its generation check now fails), so their counters are reset here.
        _homePendingShelves.value = 0
        _homeRecentlyPlayedLoading.value = false
        val previous = (_home.value as? UiState.Success)?.data.orEmpty()
        val recent = async { YtMusicRepository.homeRecentlyPlayed() }
        YtMusicRepository.home(force = force).onSuccess { feed ->
            if (!isCurrentHomeLoad(identity, generation)) return@onSuccess
            val merge = mergeHomeShelves(
                previous = previous,
                fetched = feed.shelves.withoutRepeatsOf(emptyList()),
                source = feed.source,
                previousSource = homeSource.takeUnless { replaceShelves },
            )
            homeSource = feed.source
            homeContinuation = feed.continuation
            homeSeenTitles.clear()
            homeSeenTitles.addAll(merge.shelves.map { it.title.lowercase(Locale.ROOT) })
            if (merge.shelves.isNotEmpty()) _home.value = UiState.Success(merge.shelves)
            logRecentsPublished("feed", merge.shelves)
            // The gated feed may have answered from its cache; the local
            // record is newer and costs no request.
            refreshRecentsFromLocal()
            if (merge.kept.isNotEmpty()) {
                com.music.bitchord.data.DebugLog.i(
                    "BitChord",
                    "home refresh kept ${merge.kept.size} shelf(s) the fetch did not return: " +
                        merge.kept.joinToString(", "),
                )
            }
        }.onFailure { failure ->
            if (isCurrentHomeLoad(identity, generation) && previous.isNotEmpty()) {
                com.music.bitchord.data.DebugLog.w(
                    "BitChord",
                    "home refresh failed (${failure.message}); kept ${previous.size} shelf(s): " +
                        previous.joinToString(", ") { it.title },
                )
            }
        }
        recent.await().onSuccess { shelf ->
            if (isCurrentHomeLoad(identity, generation)) {
                shelf?.let {
                    publishHomeShelves(listOf(it), prepend = true)
                    logRecentsPublished("refresh", listOf(it))
                }
            }
        }
    }

    /**
     * Called as the Home list nears its end. A no-op while a page is already
     * in flight, once the feed is exhausted, or before the first page has
     * loaded — [homeContinuation] covers all three by construction.
     *
     * The generation is captured with the token: a page that lands after a
     * refresh (whose token this no longer is) is not appended.
     */
    fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (_homeLoadingMore.value) return
        val identity = listenerKey()
        val generation = homeLoadGeneration.get()
        _homeLoadingMore.value = true
        viewModelScope.launch {
            YtMusicRepository.moreHome(token).onSuccess { feed ->
                if (isCurrentHomeLoad(identity, generation)) {
                    val existing = (_home.value as? UiState.Success)?.data ?: emptyList()
                    val added = feed.shelves.withoutRepeatsOf(existing)
                        .filter { homeSeenTitles.add(it.title.lowercase(Locale.ROOT)) }
                    // A page with nothing new signals the feed has looped back on
                    // itself rather than run dry with a token still attached —
                    // treat it the same as exhausted so scrolling can't spin here.
                    homeContinuation = feed.continuation.takeIf { added.isNotEmpty() }
                    if (added.isNotEmpty()) _home.value = UiState.Success(existing + added)
                }
            }
            if (isCurrentHomeLoad(identity, generation)) _homeLoadingMore.value = false
        }
    }

    fun loadLibrary() {
        if (!_signedIn.value) return
        val identity = listenerKey()
        _library.value = UiState.Loading
        libraryLoadJob?.cancel()
        libraryLoadJob = viewModelScope.launch { fetchLibrary(identity) }
    }

    private suspend fun fetchLibrary(identity: String?) {
        val next = YtMusicRepository.library().fold(
            onSuccess = { page ->
                // Liked Music is published with just its first page on the tab;
                // the rest of the collection is synced into LikeState here, in
                // this ViewModel's scope, so it is cancelled with the screen and
                // a liked track past the first page still reads as liked.
                page.likedContinuation?.let { token -> syncLikedMusic(identity, token) }
                if (page.isEmpty) UiState.Error(text(R.string.library_empty))
                else UiState.Success(page.copy(likedContinuation = null))
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
        if (identity == listenerKey()) _library.value = next
    }

    /**
     * Follows Liked Music's continuation chain to exhaustion, seeding each
     * page's ids into [LikeState] so every liked track reads as liked.
     *
     * Scoped to [viewModelScope] — a re-fetch of the library cancels and
     * replaces it, and it dies with the screen. It only ever seeds ids, never
     * retaining the full songs for pages already behind the tab.
     *
     * [identity] is the listener this token belongs to, checked before every
     * page: [LikeState] is a single shared map, not scoped per account, so a
     * sync still in flight when the listener switches must stop rather than
     * go on seeding the old account's likes into the new one's session.
     */
    private fun syncLikedMusic(identity: String?, token: String) {
        likedSyncJob?.cancel()
        likedSyncJob = viewModelScope.launch {
            YtMusicRepository.syncLikedMusic(token) { next ->
                if (identity != listenerKey()) null else YtMusicRepository.moreSongs(next).getOrNull()
            }
        }
    }

    /**
     * The account's listening history.
     *
     * Loaded on each visit rather than cached: it is a page whose whole subject
     * is what happened most recently, and one that opened showing the state it
     * was in last time would be answering a different question. Guests get the
     * signed-out message straight away, since there is no account to have a
     * history on.
     */
    fun loadHistory() {
        if (!_signedIn.value) {
            _history.value = UiState.Error(text(R.string.history_sign_in_required))
            return
        }
        val identity = listenerKey()
        _history.value = UiState.Loading
        viewModelScope.launch {
            val next = YtMusicRepository.history().fold(
                // An empty history is a completed answer, not a failure: the
                // screen already knows how to say "nothing played yet" from a
                // successful empty list (see HistoryScreen).
                onSuccess = { songs -> historyPageState(songs) },
                onFailure = { UiState.Error(it.friendly()) },
            )
            if (identity == listenerKey()) _history.value = next
        }
    }

    /** Recent searches, kept on device as rich entity records. */
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = SearchHistory.recent

    /**
     * Records a full entity payload in the history — used when the user taps
     * a suggestion, result row, or typeahead card so the recents list shows
     * real artwork and metadata instead of raw text.
     */
    fun recordEntity(entity: SearchHistoryEntity) = SearchHistory.record(entity)

    /**
     * Records a track entity from a search hit.
     * Checks _typeaheadResults first (live media while typing), then _results
     * (committed search). Falls back to recording the raw query if no song data
     * is available.
     */
    fun recordSearch() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        // Prefer typeahead results — they are live and always available while typing.
        val fromTypeahead = _typeaheadResults.value.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (fromTypeahead is SearchResult.Track) {
            recordEntity(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        if (fromTypeahead is SearchResult.TopTrack) {
            recordEntity(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        // Fall back to committed search results.
        val topResult = (_results.value as? UiState.Success)?.data?.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (topResult is SearchResult.Track) {
            recordEntity(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else if (topResult is SearchResult.TopTrack) {
            recordEntity(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else {
            // Fallback: record as a raw-text entity for backwards compatibility
            recordEntity(SearchHistoryEntity(
                id = "q:$q",
                title = q,
                subtitle = "",
                artworkUrl = null,
                entityType = EntityType.TRACK,
            ))
        }
    }

    /**
     * Runs a term the user picked out of a list rather than typed — a recent
     * search, or one of [suggestions] — and floats it to the top of the
     * history. Picking is as deliberate as submitting, so it searches on the
     * spot.
     *
     * Recording happens downstream via [recordSearch] when the user actually
     * interacts with a result (taps a song, album, or artist card), which
     * ensures the saved entity carries real artwork and metadata instead of
     * a blank placeholder.
     */
    fun searchFor(term: String) {
        _query.value = term
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        // Picking a suggestion or history item is deliberate, but it must NOT
        // pollute the user's YTMusic server-side search history — only an
        // explicit keyboard submission should do that.  Use the anonymous
        // endpoint so YouTube does not log this query against the account.
        runSearch(anonymous = true)
    }

    fun removeSearch(id: String) = SearchHistory.remove(id)

    fun clearSearchHistory() = SearchHistory.clear()

    fun onQueryChange(newValue: String) {
        _query.value = newValue
        if (newValue.isBlank()) {
            searchSubmitted = false
            _suggestions.value = emptyList()
            _typeaheadResults.value = emptyList()
            _results.value = null
            addonSearchJob?.cancel()
            _addonResults.value = null
            // The list is about to switch from search results to recent
            // searches (or the empty state) — without this it can keep
            // whatever scroll offset the results list was left at, landing
            // the new, much shorter list somewhere other than the top.
            _searchScrollReset.value += 1
            return
        }
        // Reset the submission gate so typeahead pipelines fire again.
        searchSubmitted = false
        // The Library source answers from the device on every keystroke (see
        // [libraryResults]); YouTube's completions have nothing to add to it.
        if (_searchSource.value != SearchSource.YOUTUBE) return
        // While typing, surface text completions — the pipeline already feeds
        // them through [suggestRequests] and publishes results via typeahead.
        suggestRequests.tryEmit(newValue)
    }

    /**
     * Commits the current query text: clears suggestions/typeahead, runs the
     * full search, and records the term in history for future recall.
     */
    fun submitSearch() {
        val q = _query.value.trim()
        if (q.isEmpty() || _searchSource.value == SearchSource.LIBRARY) return
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        if (_searchSource.value == SearchSource.ADDONS) {
            runAddonSearch(q)
            return
        }
        runSearch(anonymous = false)
    }

    /**
     * Re-runs the search that is on screen after it failed.
     *
     * The retry has to follow the source that answered: a YouTube retry must
     * not silently switch an Addons or Library search over to YouTube, and a
     * Library retry has to re-read the folder rather than re-filter the list it
     * already had. It deliberately does not record the query again — the
     * listener is repairing a request, not making a new one.
     */
    fun retrySearch() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        when (_searchSource.value) {
            SearchSource.LIBRARY -> loadLibrarySongs()
            SearchSource.ADDONS -> runAddonSearch(q, record = false)
            SearchSource.YOUTUBE -> {
                searchSubmitted = true
                _suggestions.value = emptyList()
                _typeaheadResults.value = emptyList()
                // A retry exists to replace the page that is on screen, so it
                // must not be answered from the cache: reusing a cached empty
                // page was why the "no results" retry did nothing at all.
                runSearch(anonymous = lastSearchAnonymous, forceNetwork = true)
            }
        }
    }

    /**
     * Asks every enabled addon for [q] in parallel. A slow or failing addon
     * only costs its own rows; the list is the rest of them, round-robin so
     * no single catalogue buries the others.
     */
    private fun runAddonSearch(q: String, record: Boolean = true) {
        // A retry repairs the query already in the list; re-recording it would
        // move it to the top as if the listener had searched again.
        if (record) SearchHistory.recordAddonQuery(q)
        addonSearchJob?.cancel()
        _searchScrollReset.value += 1
        val addons = SourceRegistry.active().filterIsInstance<AddonSource>()
        if (addons.isEmpty()) {
            _addonResults.value = UiState.Error(text(R.string.search_addons_none))
            return
        }
        _addonResults.value = UiState.Loading
        addonSearchJob = viewModelScope.launch {
            val perAddon = coroutineScope {
                addons.map { addon ->
                    async {
                        withTimeoutOrNull(ADDON_SEARCH_TIMEOUT_MS) {
                            runCatching { addon.search(q, limit = ADDON_SEARCH_LIMIT) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                }.map { it.await() }
            }
            val merged = buildList {
                val longest = perAddon.maxOfOrNull { it.size } ?: 0
                for (i in 0 until longest) perAddon.forEach { rows -> rows.getOrNull(i)?.let(::add) }
            }
            _addonResults.value = UiState.Success(merged)
        }
    }

    /**
     * Points the Search tab at YouTube Music or at the Local Music folder.
     *
     * The query carries over: whatever is in the field is answered again by
     * the source just picked, so flipping is a way to compare the two rather
     * than a reset.
     */
    fun setSearchSource(source: SearchSource) {
        if (_searchSource.value == source) return
        _searchSource.value = source
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        _searchScrollReset.value += 1
        when (source) {
            SearchSource.LIBRARY -> loadLibrarySongs()
            SearchSource.YOUTUBE -> submitSearch()
            SearchSource.ADDONS -> if (_query.value.isNotBlank()) submitSearch()
        }
    }

    /**
     * Reads the Local Music folder for the Library source. Re-read on every
     * switch to it, so files added since — or a permission granted since —
     * are in the next answer; a list already on screen stays up meanwhile.
     */
    fun loadLibrarySongs() {
        // Retrying used to start a second full media scan beside the first.
        // There can only be one answer to "what is in the folder now", so the
        // previous scan is replaced rather than raced. An error is not a list:
        // show the skeleton while the retry runs instead of leaving the stale
        // message and its retry button up over the new request.
        localMusicLoadJob?.cancel()
        _librarySongs.value = localMusicLoadState(_librarySongs.value)
        localMusicLoadJob = viewModelScope.launch {
            val context = getApplication<Application>()
            _librarySongs.value = if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                // Do not fold a cancellation into the failure branch: a newer
                // scan already owns this state, and writing the old error here
                // would overwrite its Loading/Success.
                val songs = try {
                    LocalMediaRepository.getLocalMusic(context)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    null
                }
                when {
                    songs == null -> UiState.Error(text(R.string.no_local_audio_found))
                    songs.isEmpty() -> UiState.Error(text(R.string.no_local_audio_found))
                    else -> UiState.Success(songs)
                }
            }
        }
    }

    fun onFilterChange(value: SearchFilter) {
        if (_filter.value == value) return
        _filter.value = value
        searchSubmitted = true
        runSearch()
    }

    /**
     * A search asked for, as a request the pipeline below decides what to do
     * with.
     *
     * [requestId] is what makes a late answer harmless: a response is only
     * written to the screen if its id is still the newest one asked for.
     */
    private data class SearchRequest(
        val query: String,
        val filter: SearchFilter,
        val requestId: Long,
        val anonymous: Boolean = true,
        val forceNetwork: Boolean = false,
    )

    private fun cacheKey(query: String, filter: SearchFilter) = "${filter.name}:$query"

    /**
     * The results of the longest earlier query this one starts with — near
     * enough to leave up while the narrower search runs.
     */
    private fun prefixMatch(query: String, filter: SearchFilter): List<SearchResult>? {
        val prefix = "${filter.name}:"
        return searchCache.snapshot()
            .filterKeys { it.startsWith(prefix) && query.startsWith(it.removePrefix(prefix), true) }
            .maxByOrNull { it.key.length }
            ?.value
            ?.rows
    }

    private fun runSearch(anonymous: Boolean = true, forceNetwork: Boolean = false) {
        val query = _query.value
        if (query.isBlank()) {
            // Nothing in flight can still be waiting to overwrite this: the
            // id it would be checked against has already moved past it.
            newestRequestId.incrementAndGet()
            searchSession = null
            _searchLoadingMore.value = false
            _results.value = null
            return
        }
        val id = newestRequestId.incrementAndGet()
        lastSearchAnonymous = anonymous
        _searchScrollReset.value += 1
        searchSession = null
        _searchLoadingMore.value = false
        searchRequests.tryEmit(SearchRequest(query, _filter.value, id, anonymous, forceNetwork))
    }

    /**
     * The search pipeline, started once and left running for the lifetime of
     * the view model.
     *
     * The point of it being one long-lived collector is that a new search no
     * longer cancels the request before it out of a fresh coroutine.
     * Cancelling a call mid-flight tears down its socket, and on a pooled HTTP
     * client that is felt by whatever picks that connection up next — which is
     * how one search could end in "Software caused connection abort" for a
     * request that was never itself in any trouble.
     *
     * There is no debounce here any more, and nothing to absorb: a search is
     * only ever asked for by a deliberate act — the search button, a
     * suggestion or history row, a filter tab — so the request that arrives is
     * already the one the user meant, and making them wait out a timer for it
     * would be a delay with nothing behind it. Typing asks
     * [startSuggestPipeline] for completions instead and leaves the results
     * alone.
     */
    private fun startSearchPipeline() = viewModelScope.launch {
        searchRequests
            .collectLatest { request ->
                val key = cacheKey(request.query, request.filter)
                // Something to look at immediately: the exact answer if this
                // query has been run before, otherwise the closest earlier
                // one. Only fall back to a spinner with neither.
                val exact = searchCache.get(key)
                // A retry starts from the spinner rather than the stale page
                // it is replacing; a normal lookup keeps the cached/prefix
                // answer up while the network call runs.
                val cached = if (request.forceNetwork) {
                    null
                } else {
                    exact?.rows ?: prefixMatch(request.query, request.filter)
                }
                _results.value = cached?.let { UiState.Success(it) } ?: UiState.Loading
                // An explicit submit still has to reach the server even when
                // an anonymous lookup cached the same query: that call is what
                // records it in the account's history. A retry also bypasses
                // the cache, or an empty cached page made it a no-op.
                if (reuseCachedSearchPage(request.anonymous, request.forceNetwork, exact != null) && exact != null) {
                    searchSession = SearchSession(key, request.requestId, request.filter, exact.continuation)
                    return@collectLatest
                }

                // Search is YouTube's alone. A module is a *substitution*
                // layer, not a catalogue to browse: it never has cover art,
                // radio, related tracks or an album page, so its rows arrived
                // in the results list looking like YouTube's and then behaved
                // nothing like them. Every track found here takes the ordinary
                // YouTube path and is handed to the module at playback time —
                // see [SourceResolver.substituteForYouTube] — which upgrades
                // the ones it holds without any of them having to be a
                // separate row to pick between.
                val result = YtMusicRepository.searchPage(request.query, request.filter, request.anonymous)
                // A search that has been superseded shouldn't land on screen,
                // whether it succeeded or failed.
                if (request.requestId != newestRequestId.get()) return@collectLatest
                _results.value = result.fold(
                    onSuccess = { page -> published(page, key, request.requestId) },
                    onFailure = { failure -> UiState.Error(failure.friendly()) },
                )
            }
    }

    /**
     * Continues the visible search only when the list reaches its end. This is
     * deliberately separate from the first-page request: waiting for every
     * continuation was the reason a search sat on a spinner for seconds.
     */
    fun loadMoreSearchResults() {
        val session = searchSession ?: return
        val token = session.continuation ?: return
        if (_searchLoadingMore.value) return
        _searchLoadingMore.value = true
        viewModelScope.launch {
            try {
                val next = YtMusicRepository.searchContinuation(token, session.filter)
                val stillCurrent = searchSession == session && session.requestId == newestRequestId.get()
                if (stillCurrent) {
                    next.onSuccess { page ->
                        val current = (_results.value as? UiState.Success)?.data.orEmpty()
                        val merged = (current + page.rows).distinctBy(::searchResultKey)
                        searchCache.put(session.key, SearchCacheEntry(merged, page.continuation))
                        searchSession = session.copy(continuation = page.continuation)
                        _results.value = UiState.Success(merged)
                    }
                }
            } finally {
                // Cleared even when a newer search replaced this one: the flag
                // gates the *next* continuation, which belongs to that search.
                _searchLoadingMore.value = false
            }
        }
    }

    /**
     * The typeahead pipeline, alongside [startSearchPipeline] and for the same
     * structural reason — one long-lived collector rather than a coroutine per
     * keystroke, so a lookup the user has typed past doesn't take a pooled
     * socket down with it.
     *
     * This one *does* debounce, and that isn't the timer that was taken off the
     * search. It's two orders of magnitude shorter, and it's paid for by the
     * request behind it being a few hundred bytes rather than a full page of
     * results — a burst of keystrokes shouldn't each cost a round trip, but the
     * gap has to be short enough that the list is up before the next letter is
     * typed. Nothing is waiting on it either way: the row the user typed is
     * already on screen from the keystroke itself.
     *
     * A failure is left on the floor. There is no worthwhile way to report
     * "couldn't suggest anything" in a list of suggestions, and the typed text
     * is standing there as a working first row regardless.
     */
    @OptIn(FlowPreview::class)
    private fun startSuggestPipeline() = viewModelScope.launch {
        suggestRequests
            .debounce(TYPEAHEAD_DEBOUNCE_MS)
            .collectLatest { input ->
                // The field moves while a keystroke is waiting out the timer;
                // only the text still on screen is worth asking about.
                val target = typeaheadLookupTarget(input, _query.value, searchSubmitted)
                    ?: return@collectLatest
                val key = typeaheadCacheKey(target)
                synchronized(suggestionCache) { suggestionCache[key] }?.let { cached ->
                    _suggestions.value = cached
                    return@collectLatest
                }
                val fetched = YtMusicRepository.searchSuggestions(target).getOrNull()
                    ?: return@collectLatest
                // Asked again on the way back; the field is live throughout.
                if (typeaheadLookupTarget(input, _query.value, searchSubmitted) == null) {
                    return@collectLatest
                }
                val rows = listOf(target) +
                    fetched.filterNot { it.equals(target, ignoreCase = true) }
                synchronized(suggestionCache) { suggestionCache[key] = rows }
                _suggestions.value = rows
            }
    }

    /**
     * Parallel pipeline that fetches live media results (tracks, artists,
     * albums) for the current query text. Runs alongside [startSuggestPipeline]
     * with the same debounce, so a pause in typing sends one of each rather
     * than one per keystroke.
     */
    @OptIn(FlowPreview::class)
    private fun startTypeaheadMediaPipeline() = viewModelScope.launch {
        suggestRequests
            .debounce(TYPEAHEAD_DEBOUNCE_MS)
            .collectLatest { input ->
                if (input.isBlank()) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                // Only show media results while the user is still typing — not
                // reading committed search results. The query-text check on its
                // own isn't enough: a late suggestion callback can repopulate
                // _suggestions after submission, and we must not re-open the
                // typeahead dropdown under an already-committed search.
                val target = typeaheadLookupTarget(input, _query.value, searchSubmitted)
                if (target == null) {
                    if (searchSubmitted) _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                val key = typeaheadCacheKey(target)
                synchronized(typeaheadMediaCache) { typeaheadMediaCache[key] }?.let { cached ->
                    _typeaheadResults.value = cached
                    return@collectLatest
                }
                val result = YtMusicRepository.searchTypeahead(target).getOrNull()
                // If the field moved on, drop the result silently.
                if (typeaheadLookupTarget(input, _query.value, searchSubmitted) == null) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                // Cap results so the dropdown doesn't grow unbounded.
                val rows = result?.rows.orEmpty().take(TYPEAHEAD_MAX_RESULTS)
                synchronized(typeaheadMediaCache) { typeaheadMediaCache[key] = rows }
                _typeaheadResults.value = rows
            }
    }

    /** Caches and publishes the initial result page without waiting for later pages. */
    private fun published(
        page: YtMusicRepository.SearchPage,
        key: String,
        requestId: Long,
    ): UiState<List<SearchResult>> {
        val rows = page.rows
        searchCache.put(key, SearchCacheEntry(rows, page.continuation))
        // "Nothing matched" is a completed answer. Sending it down the Error
        // branch made the screen offer a retry for a request that had already
        // succeeded, and made SearchScreen's own empty state unreachable.
        if (rows.isNotEmpty()) {
            searchSession = SearchSession(key, requestId, _filter.value, page.continuation)
            prefetchTopResult(rows)
        } else {
            searchSession = null
        }
        return searchPageState(rows)
    }

    private fun searchResultKey(row: SearchResult): String = when (row) {
        is SearchResult.TopTrack -> "v:${row.song.videoId}"
        is SearchResult.Track -> "v:${row.song.videoId}"
        is SearchResult.Browse -> "b:${row.item.browseId}"
    }

    /**
     * Warms the stream URL for the top song result the instant results land,
     * not when it's tapped. [AudioCache] gives a head start to whatever's
     * already queued; a fresh search has nothing queued yet, and the top
     * result is overwhelmingly what gets tapped — see [play][MainActivity.play].
     * The exact search result is warmed because video tracks now play their
     * own audio by default; catalogue matching is only requested manually.
     */
    private fun prefetchTopResult(rows: List<SearchResult>) {
        val song = rows.firstNotNullOfOrNull {
            when (it) {
                is SearchResult.TopTrack -> it.song
                is SearchResult.Track -> it.song
                is SearchResult.Browse -> null
            }
        } ?: return
        viewModelScope.launch {
            runCatching {
                val audio = song
                // A source-backed row resolves through its own source already
                // and never takes the YouTube path — warming either half of
                // this for one would be work nothing asks for.
                if (SourceRegistry.parseTrackKey(audio.videoId) != null) return@runCatching
                // JioSaavn first, on the same reasoning as the queue's
                // read-ahead: it is the copy that will actually be played if it
                // has the track, so warming YouTube's URL instead warms the one
                // that loses. Pinned through [StreamChoice] so playback opens
                // this very stream rather than racing for it again — see
                // [SourceResolver.prefetchSubstitute], which requires it.
                val version = YtMusicRepository.cachedSongVersionFor(audio)
                // A video whose song version is not known yet has no correct
                // reference to compare against; warm YouTube instead of pinning
                // a substitute to the video's clock.
                if (audio.isVideo && version == null) {
                    StreamResolver.resolve(audio.videoId)
                    return@runCatching
                }
                val playSong = version ?: audio
                val target = TrackMatcher.targetOf(playSong)
                // The same sound check the queue's read-ahead runs, applied
                // inside the race so a refused cover does not hide a slower
                // source's real recording.
                val warmed = SourceResolver.prefetchSubstitute(target, remember = false) { _, candidate ->
                    com.music.bitchord.playback.smart.HqAudioVerify
                        .verify(getApplication(), playSong.videoId, target, candidate) != null
                }
                if (warmed != null) {
                    SourceResolver.rememberOriginal(target, warmed)
                    StreamChoice.remember(playSong.videoId, warmed, substituted = true)
                    return@runCatching
                }
                // Disabled, or hasn't got it: the tap path falls back to
                // YouTube, so that is what is worth having ready.
                StreamResolver.resolve(playSong.videoId)
            }
        }
    }

    companion object {
        /** The app's own Recents key, as the feed and the shelf-title localiser use it. */
        private const val RECENTS_TITLE = "Recents"

        /** How long startup has to settle before the non-start tabs are fetched. */
        private const val STARTUP_DEFER_MS = 2_000L

        /** Per-addon budget for the Addons search tab; a slow addon is left out, not waited on. */
        private const val ADDON_SEARCH_TIMEOUT_MS = 12_000L
        private const val ADDON_SEARCH_LIMIT = 20

        /** How many Spotify tracks are looked up on YouTube Music at once. */
        private const val SPOTIFY_MATCH_PARALLELISM = 6

        /**
         * Maximum number of live media results shown in the typeahead dropdown.
         * Enough to give variety without making the list unscrollable.
         */
        const val TYPEAHEAD_MAX_RESULTS = 15

        const val SEARCH_CACHE_ENTRIES = 100

        /**
         * What a page with an empty listing says.
         *
         * Named because it is a state one can be got *out* of, not just a
         * message: an own playlist with nothing in it lands here, and adding the
         * first track to it has to be able to tell "this page is empty" apart
         * from "this page failed to load" — see [appendToOpenPlaylist].
         */

        /**
         * What a downloaded playlist's page says once the files under it are
         * gone.
         *
         * A record here outlives the folder it names — the user is expected to
         * manage Downloads with a file manager — so this is a state its page has
         * to be able to reach, not an error. Named because three places say it:
         * the page, its refresh, and the long-press menu that queues it without
         * opening it.
         */

        fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
            // Built for playlists, but an album downloaded whole opens through
            // the same prefix, and its page should read as the album it is.
            browseId.startsWith(Downloads.PLAYLIST_PREFIX) ->
                Downloads.recordIdOf(browseId)
                    ?.let { Downloads.collections.value[it] }
                    ?.takeIf { !it.playlist }
                    ?.let { BrowseType.ALBUM }
                    ?: BrowseType.PLAYLIST
            browseId.startsWith(LocalGroups.PREFIX) -> LocalGroups.typeOf(browseId) ?: fallback
            browseId.startsWith("UC") -> BrowseType.ARTIST
            browseId.startsWith("MPREb") || browseId.startsWith("VLOLAK") || browseId.startsWith("OLAK") -> BrowseType.ALBUM
            browseId.startsWith("VL") || browseId.startsWith("PL") -> BrowseType.PLAYLIST
            else -> fallback
        }
    }

    /**
     * A remote file library behind a `local:` page: how to list it and what
     * an empty listing says. One entry per library keeps the detail loader,
     * the refresher and the queue collector from each repeating the switch —
     * a third library adds one line here and nothing anywhere else.
     */
    private data class RemoteLibrary(
        val emptyRes: Int,
        val songs: suspend () -> List<Song>,
    )

    private fun remoteLibrary(browseId: String): RemoteLibrary? = when (browseId) {
        com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.webdav_empty,
                com.music.bitchord.data.webdav.WebDavRepository::getSongs,
            )
        com.music.bitchord.data.smb.SmbConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.smb_empty,
                com.music.bitchord.data.smb.SmbRepository::getSongs,
            )
        else -> null
    }

    private suspend fun remoteSongsState(remote: RemoteLibrary): UiState<List<Song>> =
        com.music.bitchord.data.remote.RemoteListing.state(runCatching { remote.songs() }, text(remote.emptyRes))

    /**
     * Re-reads an open remote-library page after its server settings change.
     * A no-op when the page isn't open — the next visit lists fresh anyway.
     */
    private fun reloadRemoteDetail(browseId: String) {
        if (_detailStack.value.any { page -> page.browseId == browseId }) {
            reloadLocalDetail(browseId)
        }
    }

    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String = "",
        thumbnailUrl: String? = null,
        type: BrowseType = BrowseType.OTHER,
    ) {
        // A fast double tap used to push two identical loading pages and launch
        // two identical browse requests. Besides wasting the connection, both
        // completions then raced to update every matching browse id in the
        // stack. The page is pushed synchronously, so this closes that window
        // without suppressing a deliberate revisit after the first page loads.
        if (_detailStack.value.lastOrNull()?.let {
                it.browseId == browseId && it.songs is UiState.Loading
            } == true
        ) return
        if (browseId.startsWith(SPOTIFY_PAGE_PREFIX)) {
            openSpotifyPage(browseId, title, subtitle, thumbnailUrl)
            return
        }
        val resolved = browseTypeOf(browseId, type)
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = resolved,
        )
        viewModelScope.launch {
            var sections = emptyList<HomeShelf>()
            // Callers that open an artist from a track — the player, the
            // long-press menu — only have that track's cover art and its full
            // credit ("A, B & C") to hand, so the page swaps in the artist's
            // own picture and name once they arrive.
            var artwork: String? = null
            var name: String? = null
            /**
             * The credit line, when the page had to supply its own.
             *
             * Only a link tapped outside the app arrives with neither — see
             * [com.music.bitchord.playback.MusicLink]. Every other caller was
             * looking at a card that already said this.
             */
            var credit: String? = null
            /** Set when the track list carries on past its first response. */
            var more: String? = null
            /** Tracks YouTube offers to round the playlist out — see [DetailPage.suggestedSongs]. */
            var suggested: List<Song> = emptyList()
            /** Liked Music's video rows — see [DetailPage.hiddenSongs]. */
            var hidden: List<Song> = emptyList()
            /** Whether this release is already saved — see [DetailPage.library]. */
            var library: LibraryState? = null
            /** YouTube's own "About" blurb — see [DetailPage.description]. */
            var description: String? = null
            /** Artist header stats — see [DetailPage.subscriberCountText]. */
            var subscriberCountText: String? = null
            var monthlyListenerCount: String? = null
            /** Whether this artist is subscribed to — see [DetailPage.subscription]. */
            var subscription: SubscriptionState? = null
            val localPlaylist = com.music.bitchord.data.spotify.LocalPlaylistStore.getPlaylist(browseId)
            val remote = remoteLibrary(browseId)
            // A release downloaded whole and opened by its YouTube id — the
            // Playlists shelf, a search hit — used to wait on the network for
            // tracks already on the device, and showed an error with no
            // connection at all. Its downloaded copy goes up first; the
            // online listing replaces it if and when that arrives.
            val onDevice = if (localPlaylist == null && remote == null && !browseId.startsWith("local:")) {
                downloadedCopyOf(browseId)
            } else {
                emptyList()
            }
            if (onDevice.isNotEmpty()) {
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId && it.songs is UiState.Loading) {
                        it.copy(songs = UiState.Success(onDevice), songsArePreview = true)
                    } else {
                        it
                    }
                }
            }
            if (localPlaylist != null) {
                name = localPlaylist.title
                credit = getApplication<Application>().getString(R.string.local_playlist_subtitle, localPlaylist.songs.size)
                artwork = localPlaylist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
            }
            val local = localPageState(browseId)
            val state = when {
                local != null -> {
                    // Smart playlists are not a page of their own file read, so
                    // their cover still comes from the songs the state carries.
                    if (SmartPlaylist.of(browseId) != null) {
                        artwork = (local as? UiState.Success)?.data
                            ?.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
                    }
                    local
                }
                resolved == BrowseType.ARTIST -> {
                    YtMusicRepository.artistPage(browseId).fold(
                        onSuccess = { page ->
                            sections = page.sections
                            artwork = page.thumbnailUrl
                            name = page.name
                            description = page.description
                            subscriberCountText = page.subscriberCountText
                            monthlyListenerCount = page.monthlyListenerCount
                            subscription = page.subscription
                            // "No top songs" is not an empty artist page: the
                            // albums and singles shelves below are the page.
                            // Only a page with neither has nothing to show.
                            if (page.songs.isEmpty() && page.sections.isEmpty()) {
                                UiState.Error(text(R.string.no_tracks_here))
                            } else {
                                UiState.Success(page.songs.withArtwork(thumbnailUrl ?: artwork))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
                else -> {
                    YtMusicRepository.browseSongs(browseId).fold(
                        onSuccess = { page ->
                            // Free here — the page that returned these rows is
                            // the one thing that states who made the playlist,
                            // so its own menu never has to go and ask. Recorded
                            // even when the listing came back empty.
                            page.owned?.let { setPlaylistOwned(browseId, it) }
                            // Only for the caller that had nothing: a card's own
                            // title is what the user just tapped, and must not
                            // be swapped for the header's wording underneath them.
                            page.header?.let { header ->
                                if (title.isBlank()) name = header.title
                                // Album cards often only carry the artist, while
                                // the page header also carries the release year.
                                // Prefer that richer line so the year appears
                                // directly below the artist on the album page.
                                if (resolved == BrowseType.ALBUM && header.subtitle.isNotBlank()) {
                                    credit = header.subtitle
                                } else if (subtitle.isBlank()) {
                                    credit = header.subtitle
                                }
                                if (thumbnailUrl == null) artwork = header.thumbnailUrl
                            }
                            description = page.description
                            if (page.songs.isEmpty()) {
                                UiState.Error(text(R.string.no_tracks_here))
                            } else {
                                more = page.continuation
                                suggested = page.suggested.withArtwork(thumbnailUrl ?: artwork)
                                hidden = page.hiddenSongs.withArtwork(thumbnailUrl ?: artwork)
                                library = page.library
                                UiState.Success(page.songs.withArtwork(thumbnailUrl ?: artwork))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
            }
            // Update by id — the user may have pushed another page meanwhile.
            // A page showing its downloaded copy takes only a real listing: a
            // failed or empty fetch leaves the downloaded tracks up.
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId &&
                    (it.songs is UiState.Loading || (onDevice.isNotEmpty() && state is UiState.Success))
                ) {
                    it.copy(
                        songs = state,
                        songsArePreview = false,
                        sections = sections,
                        thumbnailUrl = artwork ?: it.thumbnailUrl,
                        title = name ?: it.title,
                        subtitle = credit ?: it.subtitle,
                        suggestedSongs = suggested,
                        hiddenSongs = hidden,
                        continuation = more,
                        library = library,
                        description = description,
                        subscriberCountText = subscriberCountText,
                        monthlyListenerCount = monthlyListenerCount,
                        subscription = subscription,
                    )
                } else {
                    it
                }
            }
            // The rows are already on screen. Their video frames are square-
            // cropped in the meantime; square album art is filled in later,
            // after this page starts playing and only for the rows in view.
            // Only once the first page is on screen: [fillIn] appends to it,
            // and has nothing to append to before this.
            more?.let { fillIn(browseId, it, thumbnailUrl ?: artwork) }
        }
    }

    /**
     * A Spotify playlist opened as an ordinary playlist page.
     *
     * Spotify's rows go up as soon as they are read, each marked as still
     * waiting on its YouTube Music version (see [isMatchPending]); the matches
     * then arrive in order and swap the rows in place, so the page is usable
     * before the last song has been found.
     */
    private fun openSpotifyPage(browseId: String, title: String, subtitle: String, thumbnailUrl: String?) {
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = BrowseType.PLAYLIST,
        )
        viewModelScope.launch {
            val playlistId = browseId.removePrefix(SPOTIFY_PAGE_PREFIX)
            fun open() = _detailStack.value.any { it.browseId == browseId }
            fun setSongs(songs: UiState<List<Song>>) {
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId) it.copy(songs = songs) else it
                }
            }
            // The list only had a thumbnail; the full-size cover replaces it
            // once this page has asked for it.
            launch {
                val cover = runCatching { SpotifyLibrary.cover(playlistId) }.getOrNull() ?: return@launch
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId) it.copy(thumbnailUrl = cover) else it
                }
            }
            val tracks = runCatching {
                SpotifyLibrary.tracks(playlistId) { soFar ->
                    setSongs(UiState.Success(soFar.map { it.asPendingSong() }))
                }
            }.getOrElse {
                setSongs(UiState.Error(it.message ?: text(R.string.failed)))
                return@launch
            }
            if (tracks.isEmpty()) {
                setSongs(UiState.Error(text(R.string.spotify_empty_tracks)))
                return@launch
            }
            val gate = Semaphore(SPOTIFY_MATCH_PARALLELISM)
            coroutineScope {
                tracks.forEachIndexed { index, track ->
                    launch {
                        gate.withPermit {
                            if (!open()) return@withPermit
                            val match = runCatching { SpotifyImporter.matchTrack(track) }.getOrNull()
                            val found = match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.imageUrl)
                                ?: track.asPendingSong().let {
                                    it.copy(videoId = SPOTIFY_MISSING_PREFIX + track.id)
                                }
                            _detailStack.value = _detailStack.value.map { page ->
                                val list = (page.songs as? UiState.Success<List<Song>>)?.data
                                if (page.browseId == browseId && list != null && index < list.size) {
                                    page.copy(songs = UiState.Success(list.toMutableList().also { it[index] = found }))
                                } else page
                            }
                        }
                    }
                }
            }
        }
    }

    private fun SpotifyTrack.asPendingSong() = Song(
        videoId = SPOTIFY_PENDING_PREFIX + id,
        title = title,
        artist = artist,
        thumbnailUrl = imageUrl,
        durationText = durationMs.takeIf { it > 0 }?.let { ms ->
            "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        },
        albumName = album,
    )

    /**
     * Pushes one album, playlist or artist out of a device folder as a page of
     * its own, with the rows the folder already had in hand — so it opens on
     * its tracks rather than on a skeleton while the folder is read again.
     * [reloadLocalDetail] keeps it current from there, like any `local:` page.
     */
    fun openLocalGroup(group: LocalGroup) {
        if (_detailStack.value.lastOrNull()?.browseId == group.browseId) return
        _detailStack.value += DetailPage(
            browseId = group.browseId,
            title = group.title,
            subtitle = group.subtitle,
            thumbnailUrl = group.thumbnailUrl,
            songs = if (group.songs.isEmpty()) UiState.Error(text(R.string.no_tracks_here)) else UiState.Success(group.songs),
            type = group.type,
        )
    }

    fun reloadLocalDetail(browseId: String) {
        viewModelScope.launch {
            val state = localPageState(browseId) ?: return@launch
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) {
                    it.copy(songs = state)
                } else it
            }
        }
    }

    /**
     * The listing behind a page whose tracks come from this device or a file
     * server rather than YouTube — a device folder, a release downloaded whole,
     * a playlist in the app's own store, a remote library, or one album or
     * artist grouped out of a folder — or null for every other page.
     *
     * The one place each of those is read, so opening a page, refreshing it
     * and queueing it from a menu can't disagree about what is on it.
     */
    private suspend fun localPageState(browseId: String): UiState<List<Song>>? {
        val context = getApplication<Application>()
        fun nonEmpty(songs: List<Song>, empty: () -> String): UiState<List<Song>> =
            if (songs.isEmpty()) UiState.Error(empty()) else UiState.Success(songs)

        com.music.bitchord.data.spotify.LocalPlaylistStore.getPlaylist(browseId)?.let { playlist ->
            return nonEmpty(playlist.songs) { text(R.string.spotify_import_empty_playlist) }
        }
        remoteLibrary(browseId)?.let { return remoteSongsState(it) }
        SmartPlaylist.of(browseId)?.let { return smartPlaylistState(it) }
        LocalGroups.folderOf(browseId)?.let { folderId ->
            // Read off a fresh listing of the folder it came from: a grouping
            // has nothing of its own to read, and whatever the folder says
            // when it can't be listed is what this page says too.
            return when (val folder = localPageState(folderId)) {
                is UiState.Success -> nonEmpty(LocalGroups.songsOf(browseId, folder.data)) { text(R.string.no_tracks_here) }
                null -> UiState.Error(text(R.string.no_tracks_here))
                else -> folder
            }
        }
        return when {
            Downloads.recordIdOf(browseId) != null ->
                nonEmpty(downloadedPlaylist(browseId)) { text(R.string.downloaded_playlist_empty) }
            browseId == "local:downloads" ->
                nonEmpty(Downloads.getDownloadedSongs(context)) { text(R.string.no_downloaded_tracks) }
            browseId == CACHE_FOLDER_BROWSE_ID -> cachedSongsState()
            browseId == "local:all" -> if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                nonEmpty(LocalMediaRepository.getLocalMusic(context)) { text(R.string.no_local_audio_found) }
            }
            else -> null
        }
    }

    /**
     * The tracks of the downloaded playlist [browseId] names that are still on
     * disk, in the order the playlist had.
     *
     * The download record already names those files, so opening this page must
     * not scan every unrelated download first. [Downloads.getCollectionSongs]
     * verifies only this collection and still collapses aliases that point to
     * the same saved file.
     *
     * Empty is the honest answer for a record whose files have all been deleted
     * from under it, and callers turn that into an empty-state message rather than
     * into a blank page.
     */
    private suspend fun downloadedPlaylist(browseId: String): List<Song> {
        val id = Downloads.recordIdOf(browseId) ?: return emptyList()
        return Downloads.getCollectionSongs(getApplication(), id)
    }

    /**
     * The downloaded copy of the release YouTube calls [browseId], if there
     * is one. A playlist is filed under whichever id it was downloaded from,
     * which may or may not carry the `VL` its page id does.
     */
    private suspend fun downloadedCopyOf(browseId: String): List<Song> {
        val bare = browseId.removePrefix("VL")
        Downloads.ensureOpen()
        val id = Downloads.collections.value.keys.firstOrNull { it.removePrefix("VL") == bare } ?: return emptyList()
        return Downloads.getCollectionSongs(getApplication(), id)
    }

    /**
     * One of the playlists made from this device's listening. The liked list
     * is read whole from YouTube when it can be, so a long Liked Music still
     * finds its unplayed songs; the library's first page stands in offline.
     */
    private suspend fun smartPlaylistState(kind: SmartPlaylist): UiState<List<Song>> {
        val liked = if (kind == SmartPlaylist.LIKED_UNPLAYED) {
            if (!_signedIn.value) return UiState.Error(text(R.string.smart_playlist_sign_in))
            YtMusicRepository.allSongs(YtMusicRepository.LIKED_MUSIC).getOrNull()
                ?: (_library.value as? UiState.Success)?.data?.likedSongs.orEmpty()
        } else {
            emptyList()
        }
        val songs = runCatching { SmartPlaylists.songs(kind, liked) }.getOrDefault(emptyList())
        return if (songs.isEmpty()) UiState.Error(text(R.string.smart_playlist_empty)) else UiState.Success(songs)
    }

    /**
     * The Cached songs folder: the YouTube and JioSaavn tracks the song cache
     * is holding, most recently played first. See [AudioCache.cachedSongs].
     */
    private suspend fun cachedSongsState(): UiState<List<Song>> {
        val songs = AudioCache.cachedSongs().map { it.song }
        return if (songs.isEmpty()) UiState.Error(text(R.string.cached_songs_empty)) else UiState.Success(songs)
    }

    /**
     * Follows a detail page's continuations in the background, appending each
     * page to what is already being read.
     *
     * A playlist of a few hundred tracks is several round trips, and taking
     * them before showing anything meant a spinner for all of them. Growing
     * the list underneath the reader is also what makes it safe to keep
     * following continuations however deep the playlist runs — nobody is
     * waiting on the last one — so a playlist past YouTube's ~1000-track,
     * ten-page shelf still loads to the end instead of stopping there.
     *
     * Stops the moment the page leaves the stack: there is no one to append
     * for. A page that adds nothing new (below) is the other exit, for a feed
     * that loops back on itself instead of running dry.
     */
    private fun fillIn(browseId: String, token: String, artworkFallback: String?) {
        fillInJobs[browseId]?.cancel()
        fillInJobs[browseId] = viewModelScope.launch {
            // Publish and draw the first response before spending the shared
            // connection on page two. On a long playlist the continuation can
            // otherwise start in the same main-loop turn as the state update,
            // making a ready first screen feel as if it were still loading.
            delay(150)
            var next: String? = token
            while (next != null) {
                val fetched = YtMusicRepository.moreSongs(next).getOrNull() ?: return@launch
                // Repository calls may finish after cancellation. Do not let
                // an in-flight UI page append after the playback service has
                // taken the continuation over.
                currentCoroutineContext().ensureActive()
                // The same page can be on the stack twice (opened again from
                // inside itself). One walk per browseId grows every copy; a
                // second walk replaces the first, so each copy merges by key
                // and only takes a token from a page that taught it something.
                val stack = _detailStack.value
                if (stack.none { it.browseId == browseId }) return@launch
                var progressed = false
                val updated = stack.map { current ->
                    if (current.browseId != browseId) return@map current
                    val existing = (current.songs as? UiState.Success)?.data ?: return@map current
                    val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
                    val added = fetched.songs
                        .filter { known.add(it.contextEntryKey()) }
                        .withArtwork(artworkFallback)
                    val knownVideoIds = (existing + added).mapTo(HashSet()) { it.videoId }
                    // Suggestions can arrive on a later page than the real
                    // tracks, once the playlist's own continuation runs dry —
                    // see parsePlaylistShelf — so they're tracked separately
                    // rather than folded into [known].
                    val knownSuggested = current.suggestedSongs.mapTo(HashSet()) { it.videoId }
                    val addedSuggested = fetched.suggested
                        .filter { it.videoId !in knownVideoIds && knownSuggested.add(it.videoId) }
                        .withArtwork(artworkFallback)
                    // Liked Music's video rows can arrive on any page; they are
                    // counted and revealed together with the first page's.
                    val knownHidden = current.hiddenSongs.mapTo(HashSet()) { it.videoId }
                    val addedHidden = fetched.hiddenSongs
                        .filter { it.videoId !in knownVideoIds && knownHidden.add(it.videoId) }
                        .withArtwork(artworkFallback)
                    if (added.isEmpty() && addedSuggested.isEmpty() && addedHidden.isEmpty()) return@map current
                    progressed = true
                    current.copy(
                        songs = UiState.Success(existing + added),
                        suggestedSongs = current.suggestedSongs + addedSuggested,
                        hiddenSongs = current.hiddenSongs + addedHidden,
                        continuation = fetched.continuation,
                    )
                }
                // A page with nothing new for any copy means the feed has looped
                // back rather than run dry with a token still attached.
                if (!progressed) return@launch
                _detailStack.value = updated
                next = fetched.continuation
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (fillInJobs[browseId] === job) fillInJobs.remove(browseId)
            }
        }
    }

    /**
     * Applies the service-owned canonical context to its matching open page.
     *
     * The service only materialises a small queue window, while its registry
     * keeps the complete canonical list. Mirroring that registry here avoids a
     * page that appears to stop growing as soon as playback starts.
     */
    private fun applyBoundedContextSnapshot(snapshot: BoundedContextRequests.Snapshot) {
        val browseId = snapshot.request.source.id ?: return
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return
        val page = stack[index]
        if (page.type != BrowseType.ALBUM && page.type != BrowseType.PLAYLIST) return
        // Exactly one loader walks the continuation. While the service owns
        // it the page's own fill-in stops; when the service gives up (or never
        // took it — a party, a Cast race) the page resumes from the newest token.
        if (snapshot.serviceOwned) {
            if (snapshot.continuationToken != null) fillInJobs.remove(browseId)?.cancel()
        } else if (snapshot.released && snapshot.continuationToken != null &&
            fillInJobs[browseId]?.isActive != true
        ) {
            fillIn(browseId, snapshot.continuationToken, page.thumbnailUrl)
        }
        val existing = (page.songs as? UiState.Success)?.data ?: return
        val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
        val added = snapshot.songs
            .filter { known.add(it.contextEntryKey()) }
            .withArtwork(page.thumbnailUrl)
        // Never move the page's token backwards: a page whose own fill-in is
        // ahead of the service would otherwise hand a later tap a token for
        // songs it already has, which the registry rejects as a duplicate page.
        val continuation = if (snapshot.songs.size >= existing.size) snapshot.continuationToken else page.continuation
        if (added.isEmpty() && page.continuation == continuation) return
        _detailStack.value = stack.toMutableList().also {
            it[index] = page.copy(
                songs = UiState.Success(existing + added),
                continuation = continuation,
            )
        }
    }

    /**
     * Fills square covers for the rows currently in view, once playback from
     * this page has actually started. The resolver's shared cache and request
     * gate keep repeat calls cheap and the number of lookups bounded.
     */
    fun enrichVisibleDetailCovers(browseId: String, songs: List<Song>) {
        val pending = songs.filter { song ->
            YtMusicRepository.needsDetailCover(song) &&
                detailCoverLookups.add("$browseId|${song.contextEntryKey()}")
        }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            try {
                val enriched = YtMusicRepository.enrichDetailCovers(pending, pending.size)
                if (enriched == pending) return@launch
                val byKey = enriched.associateBy { it.contextEntryKey() }
                _detailStack.value = _detailStack.value.map { page ->
                    if (page.browseId != browseId) return@map page
                    val current = (page.songs as? UiState.Success<List<Song>>)?.data
                        ?: return@map page
                    page.copy(
                        songs = UiState.Success(
                            current.map { song -> byKey[song.contextEntryKey()] ?: song },
                        ),
                    )
                }
            } finally {
                pending.forEach {
                    detailCoverLookups.remove("$browseId|${it.contextEntryKey()}")
                }
            }
        }
    }

    /**
     * Returns the newest page snapshot for a bounded playback handoff. The
     * page's own fill-in keeps running until the service reports that it has
     * taken the continuation over (see [applyBoundedContextSnapshot]); the
     * tap may yet go down another path, and cancelling here left the page
     * permanently cut short whenever it did.
     */
    fun takePlaybackContext(browseId: String): DetailPage? {
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return null
        val mirrored = BoundedContextRequests.snapshot.value
            ?.takeIf { it.request.source.id == browseId }
        if (mirrored != null) {
            applyBoundedContextSnapshot(mirrored)
            return _detailStack.value.getOrNull(index)
        }
        return stack[index]
    }

    /**
     * The newest snapshot of [browseId] once its real track listing has landed.
     *
     * A playlist opened with a downloaded copy on screen shows that copy before
     * the network response; playing from it queued local/preview rows that
     * carried no upload type at all. Waits on the stack flow instead of polling
     * — the page updates exactly once when the response (or its error) arrives.
     * On timeout the caller gets whatever is on screen and marks those rows as
     * type-unknown, so the song-version lookup still asks about them.
     */
    suspend fun awaitDetailListing(browseId: String, timeoutMs: Long = 3_000L): DetailPage? {
        fun latest(): DetailPage? = _detailStack.value.lastOrNull { it.browseId == browseId }
        val current = latest() ?: return null
        if (!current.songsArePreview && current.songs !is UiState.Loading) return current
        withTimeoutOrNull(timeoutMs) {
            _detailStack.first { stack ->
                stack.lastOrNull { it.browseId == browseId }?.let {
                    !it.songsArePreview && it.songs !is UiState.Loading
                } == true
            }
        }
        return latest() ?: current
    }

    /**
     * An album's track listing doesn't repeat the cover on every row — the
     * page carries it once — so rows arrive with no artwork and stay blank
     * through to the queue and the notification. Fall back to the page's.
     */
    private fun List<Song>.withArtwork(fallback: String?): List<Song> {
        if (fallback == null) return this
        return map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = fallback) else it }
    }

    /**
     * Home and Explore cards don't say what they point at, and an artist
     * fetched as an album only yields the five songs on its landing page.
     * YouTube's browse ids are prefixed by kind, so use that.
     *
     * Public because the long-press menus ask the same question of a card
     * before offering to queue what is behind it — an artist is not a running
     * order, so it gets no queue actions.
     */
    fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType =
        Companion.browseTypeOf(browseId, fallback)

    /**
     * Every track behind an album or playlist, handed to [onResult] once it is
     * all in.
     *
     * What a long-press on a card is acting on. A card has nothing but a browse
     * id — its page was never opened, so there is no track list anywhere to
     * read — and "add this album to the queue" means the whole album, so this
     * follows continuations to the end rather than taking the first page.
     *
     * Runs in [viewModelScope], not the caller's: the sheet the tap came from
     * closes immediately, and a three-hundred-track playlist must not be
     * abandoned halfway because of it.
     */
    fun collectSongs(
        browseId: String,
        artworkFallback: String? = null,
        onResult: (Result<List<Song>>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = when (val local = localPageState(browseId)) {
                is UiState.Success -> Result.success(local.data)
                is UiState.Error -> Result.failure(IllegalStateException(local.message))
                UiState.Loading -> Result.failure(IllegalStateException(text(R.string.no_tracks_here)))
                null -> YtMusicRepository.allSongs(browseId)
            }
            onResult(result.map { it.withArtwork(artworkFallback) })
        }
    }

    /** Pops one page; returns false when there was nothing to pop. */
    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _detailStack.value = stack.dropLast(1)
        navigator.onDetailStackShrunk(_detailStack.value.size)
        return true
    }

    /**
     * Loads the channel list, unless it is already in hand.
     *
     * @param force refetch even if it is ??after a switch, since the list
     *   itself reports which channel is active. A load still running belongs
     *   to the identity being left, so it is cancelled and replaced rather
     *   than allowed to answer after the new one.
     */
    fun loadChannels(force: Boolean = false) {
        if (!_signedIn.value) return
        if (force) {
            // After a switch the list itself is the question, so a load still
            // running under the identity being left is replaced, not dropped.
            channelsJob?.cancel()
        } else {
            if (_channelsLoading.value) return
            if (_channels.value.isNotEmpty()) return
        }
        channelsJob = viewModelScope.launch {
            _channelsLoading.value = true
            try {
                YtMusicRepository.accountChannels()
                    .onSuccess {
                        _channels.value = it
                        persistDetectedProfiles(it)
                    }
            } finally {
                // Only this job may clear it; a cancelled predecessor must not
                // clear the spinner of the load that replaced it.
                if (channelsJob === currentCoroutineContext()[Job]) _channelsLoading.value = false
            }
        }
    }

    /**
     * Acts as [channel] from here on.
     *
     * Everything already on screen belongs to the channel being left — its
     * library, its hearts, its playlists — so the switch clears them and
     * refetches rather than letting the new identity's pages arrive one at a
     * time on top of the old one's.
     */
    fun selectChannel(channel: AccountChannel) {
        val accountId = _activeAccountId.value ?: return
        val profile = YouTubeProfile(
            profileId = profileId(channel.pageId, channel.dataSyncId, channel.name),
            name = channel.name, handle = channel.subtitle, avatar = channel.thumbnailUrl,
            pageId = channel.pageId, dataSyncId = channel.dataSyncId,
            isBrandAccount = channel.pageId != null,
        )
        selectProfile(accountId, profile.profileId, profile)
    }

    /**
     * A session lifted out of the in-app browser — a fresh sign-in, or the same
     * login now pointed at a different channel.
     *
     * One path for both because they differ in exactly one thing: whether the
     * cookie is new. What follows — adopt the identity the captured page
     * reported, remember it, and refetch everything that belongs to a listener
     * — is the same work either way.
     */
    fun onWebSession(
        session: CapturedSession,
        mode: WebSessionMode,
        onComplete: (Boolean) -> Unit = {},
    ) {
        // A page without ytcfg is not a failed login: the cookie jar can be
        // complete while the WebView sits on YouTube's Premium paywall or a
        // plain home page. Only a jar with no signing secret is unusable.
        if (!session.loggedIn && !session.hasSessionCookie) {
            onComplete(false)
            return
        }
        val oldAccountId = _activeAccountId.value
        val oldProfileId = _activeProfileId.value
        viewModelScope.launch {
            // Validate the exact cookie/profile pair before writing any of it.
            // The old implementation persisted first and unconditionally set
            // signedIn=true; a half-finished channel chooser therefore became
            // a durable broken "Personal" account.
            Innertube.cookie = session.cookie
            // A page that published a signed-in ytcfg is authoritative: it is
            // the only thing that knows which brand channel the listener has
            // selected. A paywall or plain home page is just a landing over a
            // valid jar, so the desktop shell is asked with those cookies and
            // the answer is used below.
            val pageHasIdentity = session.loggedIn &&
                (session.pageId != null || session.dataSyncId != null)
            if (pageHasIdentity) {
                Innertube.adoptSessionScope(
                    pageId = session.pageId,
                    dataSyncId = session.dataSyncId,
                    authUser = session.authUser,
                    visitorData = session.visitorData,
                    clientVersion = session.clientVersion,
                    loggedIn = true,
                )
            } else {
                Innertube.ensureSessionScope()
                if (Innertube.currentSession() == null && session.loggedIn) {
                    // The shell could not be read; keep the page's own visitor
                    // and client version rather than discarding a real login.
                    Innertube.adoptSessionScope(
                        pageId = session.pageId,
                        dataSyncId = session.dataSyncId,
                        authUser = session.authUser,
                        visitorData = session.visitorData,
                        clientVersion = session.clientVersion,
                        loggedIn = true,
                    )
                }
            }
            val resolved = if (pageHasIdentity) null else Innertube.currentSession()
            val pageId = session.pageId ?: resolved?.pageId
            val dataSyncId = session.dataSyncId ?: resolved?.dataSyncId
            val authUser = session.authUser ?: resolved?.authUser
            Innertube.selectChannel(pageId, dataSyncId, authUser)

            val account = withTimeoutOrNull(20_000L) {
                var result = YtMusicRepository.account()
                if (result.isFailure) {
                    delay(750L)
                    result = YtMusicRepository.account()
                }
                result.getOrNull()
            } ?: if (mode == WebSessionMode.SIGN_IN && session.hasSessionCookie) {
                // Every identity endpoint refused, but the jar holds a
                // complete Google login (the WebView itself is signed in).
                // Keep it under a generic name instead of discarding it:
                // without Premium in Korea Music answers account_menu 404,
                // and that alone used to make sign-in impossible.
                com.music.bitchord.data.DebugLog.w("BitChord", "account: no identity endpoint answered; keeping the login")
                Account(name = GENERIC_ACCOUNT_NAME, email = "", thumbnailUrl = null)
            } else null
            if (account == null) {
                restoreActiveSession(oldAccountId, oldProfileId)
                onComplete(false)
                return@launch
            }

            // A channel switch is another identity under the same Google
            // login, never a new Google account. For a fresh sign-in, matching
            // a non-empty email upgrades the existing entry instead of adding
            // a duplicate after cookies rotate.
            val accountId = when (mode) {
                WebSessionMode.SWITCH_CHANNEL -> oldAccountId
                WebSessionMode.SIGN_IN -> authStore.sessions.firstOrNull {
                    account.email.isNotBlank() && it.email.equals(account.email, ignoreCase = true)
                }?.accountId ?: sessionId(session.cookie, null)
            }
            if (accountId == null) {
                restoreActiveSession(oldAccountId, oldProfileId)
                onComplete(false)
                return@launch
            }
            val previous = authStore.sessions.firstOrNull { it.accountId == accountId }
            // A field the page could not report is silence, not a correction:
            // keep the identity already stored under this account rather than
            // replacing a real email, handle, name or avatar with an empty one.
            val mergedSession = mergeAccountIdentity(
                Account(
                    name = previous?.name.orEmpty(),
                    email = previous?.email.orEmpty(),
                    thumbnailUrl = null,
                ),
                account,
            )
            val selectedProfileId = profileId(pageId, dataSyncId, mergedSession.name)
            val previousProfile = previous?.profiles?.firstOrNull { it.profileId == selectedProfileId }
            val mergedProfile = mergeAccountIdentity(
                previousProfile?.let { Account(it.name, "", it.avatar, it.handle) }
                    ?: Account(name = "", email = "", thumbnailUrl = null),
                account.copy(handle = account.handle?.takeIf { it.isNotBlank() } ?: account.email),
            )
            val profile = YouTubeProfile(
                profileId = selectedProfileId,
                name = mergedProfile.name,
                handle = mergedProfile.handle.orEmpty(),
                avatar = mergedProfile.thumbnailUrl,
                pageId = pageId,
                dataSyncId = dataSyncId,
                authUser = authUser,
                isBrandAccount = pageId != null,
            )
            val hasRealIdentity = profile.pageId != null || profile.dataSyncId != null
            val profiles = previous?.profiles.orEmpty().filterNot { known ->
                known.profileId == profile.profileId ||
                    (hasRealIdentity && known.profileId.startsWith("profile:"))
            } + profile
            val stored = GoogleAccountSession(
                accountId = accountId,
                cookie = session.cookie,
                name = mergedSession.name,
                email = mergedSession.email,
                profiles = profiles,
                activeProfileId = profile.profileId,
            )

            val wasSignedIn = _signedIn.value
            if (wasSignedIn) cacheCurrentListener()
            authStore.upsertSession(stored)
            authStore.cookie = session.cookie // legacy compatibility only
            _googleAccounts.value = authStore.sessions
            _activeAccountId.value = accountId
            _activeProfileId.value = profile.profileId
            _selectedChannelKey.value = profile.profileId
            _selectedChannelName.value = mergedProfile.name
            _account.value = mergeAccountIdentity(
                Account(
                    name = previous?.name.orEmpty(),
                    email = previous?.email.orEmpty(),
                    thumbnailUrl = previousProfile?.avatar,
                    handle = previousProfile?.handle,
                ),
                account,
            )
            _channels.value = emptyList()
            _signedIn.value = true

            // Every resolver verdict and personalised page belongs to the
            // identity that was active before validation succeeded.
            StreamResolver.onSessionChanged()
            invalidateSearchCaches()
            if (wasSignedIn) clearListenerState()
            reloadForAccount()
            loadChannels(force = true)
            onComplete(true)
        }
    }

    /** Put request signing back exactly as it was after a rejected candidate. */
    private fun restoreActiveSession(accountId: String?, selectedProfileId: String?) {
        val account = authStore.sessions.firstOrNull { it.accountId == accountId }
        val profile = account?.profiles?.firstOrNull { it.profileId == selectedProfileId }
        Innertube.cookie = account?.cookie
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
    }

    /** Selects an identity without ever allowing a response to replace it. */
    fun selectProfile(accountId: String, selectedProfileId: String, supplied: YouTubeProfile? = null) {
        val source = _googleAccounts.value.firstOrNull { it.accountId == accountId } ?: return
        val profile = supplied ?: source.profiles.firstOrNull { it.profileId == selectedProfileId } ?: return
        if (accountId == _activeAccountId.value && profile.profileId == _activeProfileId.value) return
        cacheCurrentListener()
        val index = source.profiles.indexOfFirst { it.profileId == profile.profileId }
        val updatedProfiles = if (index >= 0) {
            source.profiles.toMutableList().apply { set(index, profile) }
        } else {
            source.profiles + profile
        }
        val updated = source.copy(
            profiles = updatedProfiles,
            activeProfileId = profile.profileId,
        )
        authStore.upsertSession(updated)
        authStore.select(accountId, profile.profileId)
        _googleAccounts.value = authStore.sessions
        _activeAccountId.value = accountId
        _activeProfileId.value = profile.profileId
        authStore.cookie = updated.cookie
        Innertube.cookie = updated.cookie
        Innertube.selectChannel(profile.pageId, profile.dataSyncId, profile.authUser)
        _selectedChannelKey.value = profile.profileId
        _selectedChannelName.value = profile.name
        StreamResolver.onSessionChanged()
        clearListenerState(restoreCached = true)
        reloadForAccount()
    }

    /** Returns false at an edge, allowing the avatar to play its elastic cue. */
    fun stepProfile(forward: Boolean): Boolean {
        val target = adjacentProfile(_googleAccounts.value, _activeAccountId.value, _activeProfileId.value, forward)
            ?: return false
        selectProfile(target.first, target.second)
        return true
    }

    fun removeAccount(accountId: String) {
        // Removing an account the app was not acting as is only a list edit:
        // the identity on screen stays, and its pages stay with it.
        val wasActive = accountId == _activeAccountId.value
        val fallback = authStore.removeAccount(accountId)
        _googleAccounts.value = authStore.sessions
        if (!wasActive) return
        if (fallback == null) { signOut(); return }
        selectProfile(fallback.accountId, fallback.activeProfileId ?: fallback.profiles.firstOrNull()?.profileId ?: return)
    }

    private fun persistDetectedProfiles(channels: List<AccountChannel>) {
        val accountId = _activeAccountId.value ?: return
        val current = authStore.sessions.firstOrNull { it.accountId == accountId } ?: return
        val detected = channels.map { channel -> YouTubeProfile(
            profileId(channel.pageId, channel.dataSyncId, channel.name), channel.name, channel.subtitle,
            channel.thumbnailUrl, channel.pageId, channel.dataSyncId,
            isBrandAccount = channel.pageId != null,
        ) }
        // A profile captured before its channel existed carries a provisional,
        // name-hash id (see profileId()) rather than the pageId/dataSyncId Google
        // reports here once the channel is live. Once Google actually reports a
        // channel, that placeholder is stale — it would have been listed here
        // too if it still lacked real ids — and it's dropped rather than kept
        // alongside the identity it was standing in for. An empty response
        // (channel not created yet, or a transient miss) must never drop it.
        val stalePlaceholder = if (detected.isEmpty()) emptySet() else current.profiles
            .filter { it.profileId.startsWith("profile:") }
            .map { it.profileId }
            .toSet()
        // Keep the captured active identity if Google's endpoint temporarily
        // omits it; an empty/partial response must never erase a selection.
        val profiles = (current.profiles.filter { known ->
            known.profileId !in stalePlaceholder && detected.none { it.profileId == known.profileId }
        } + detected)
        val selected = when {
            current.activeProfileId != null && current.activeProfileId !in stalePlaceholder -> current.activeProfileId
            current.activeProfileId in stalePlaceholder -> detected.singleOrNull()?.profileId
            else -> null
        } ?: profiles.firstOrNull()?.profileId
        authStore.upsertSession(current.copy(profiles = profiles, activeProfileId = selected), activate = false)
        _googleAccounts.value = authStore.sessions
    }

    /**
     * Drops everything on screen that belongs to the identity being left.
     *
     * The hearts, the playlists and the library are all answers to "who is
     * asking", so keeping them across a switch shows the new channel the old
     * one's music until each page happens to be refetched.
     */
    private fun cacheCurrentListener() {
        val key = listenerKey() ?: return
        listenerCache[key] = ListenerSnapshot(
            _account.value, _library.value, _history.value, _playlists.value, _playlistOwned.value,
        )
    }

    private fun listenerKey(): String? = _activeAccountId.value?.let { accountId ->
        _activeProfileId.value?.let { profileId -> "$accountId:$profileId" }
    }

    private fun clearListenerState(restoreCached: Boolean = false) {
        _account.value = null
        likedSyncJob?.cancel()
        LikeState.clear()
        _playlistsLoading.value = false
        _playlists.value = emptyList()
        _playlistOwned.value = emptyMap()
        ownershipInFlight.clear()
        _songMenu.value = null
        _library.value = UiState.Loading
        _history.value = UiState.Loading
        // Pushed pages and the saved badges on their cards were answered for
        // the listener being left, so they go with the rest of their state.
        clearDetail()
        _releaseLibrary.value = emptyMap()
        invalidateSearchCaches()
        if (restoreCached) listenerCache[listenerKey()]?.let { cached ->
            _account.value = cached.account
            _library.value = cached.library
            _history.value = cached.history
            _playlists.value = cached.playlists
            _playlistOwned.value = cached.owned
        }
    }

    /**
     * Search answers and completions belong to the identity — and the route —
     * that asked for them. A query repeated after a switch or a proxy change
     * has to be asked again rather than answered from the old session.
     */
    private fun invalidateSearchCaches() {
        searchCache.evictAll()
        synchronized(suggestionCache) { suggestionCache.clear() }
        synchronized(typeaheadMediaCache) { typeaheadMediaCache.clear() }
        searchSession = null
    }

    /**
     * Everything that is "the signed-in listener's", refetched.
     *
     * The scope comes first, and inside one coroutine rather than beside them:
     * which channel the session acts as decides what "the library" and "the
     * history" even refer to, so loading them first and scoping second shows
     * the listener the wrong account's music and then silently disagrees with
     * itself.
     */
    private fun reloadForAccount() {
        viewModelScope.launch {
            Innertube.ensureSessionScope()
            loadHome("session")
            loadLibrary()
            loadAccount()
            loadPlaylists()
        }
    }

    fun signOut() {
        // In a multi-account install, sign out removes only the current Google
        // session and restores another one. A legacy single-account install
        // retains the familiar full sign-out behaviour.
        val current = _activeAccountId.value
        if (current != null && authStore.sessions.size > 1) {
            removeAccount(current)
            return
        }
        authStore.signOut()
        Innertube.cookie = null
        // The mirror image: verdicts reached with a session in hand say nothing
        // about what an anonymous walk will be told, and the clients stood down
        // for refusing the session deserve a fresh hearing without it.
        StreamResolver.onSessionChanged()
        _signedIn.value = false
        _account.value = null
        Innertube.selectChannel(null, null)
        _channels.value = emptyList()
        _selectedChannelKey.value = null
        _selectedChannelName.value = null
        _googleAccounts.value = emptyList()
        _activeAccountId.value = null
        _activeProfileId.value = null
        _library.value = UiState.Loading
        // Ratings and playlists belong to the account that just left; keeping
        // them would show the next signed-in user someone else's hearts.
        likedSyncJob?.cancel()
        LikeState.clear()
        _playlists.value = emptyList()
        _playlistOwned.value = emptyMap()
        ownershipInFlight.clear()
        _songMenu.value = null
        // The stack and the saved badges are the departed listener's pages.
        clearDetail()
        _releaseLibrary.value = emptyMap()
        invalidateSearchCaches()
        loadHome("session")
    }

    private fun Throwable.friendly(): String =
        FriendlyError.message(getApplication(), this)

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun quantity(id: Int, count: Int): String =
        getApplication<Application>().resources.getQuantityString(id, count, count)
}

/**
 * Whether the cached page for a query may answer a request without a network
 * round trip.
 *
 * Anonymous lookups reuse the cache — that is what keeps a filter tab and a
 * suggestion tap instant — while a submitted search must reach the server
 * because that call is what records it in the account's history. A retry never
 * reuses the cache: the cached page is exactly the answer the listener asked to
 * replace, and reusing an empty one made the "no results" retry do nothing.
 */
internal fun reuseCachedSearchPage(
    anonymous: Boolean,
    forceNetwork: Boolean,
    hasCachedPage: Boolean,
): Boolean = hasCachedPage && anonymous && !forceNetwork

/**
 * A completed YouTube search page. An empty page is still a completed answer:
 * the screen renders it as "nothing matched", with its own retry, rather than
 * as a failed request.
 */
internal fun searchPageState(rows: List<SearchResult>): UiState<List<SearchResult>> =
    UiState.Success(rows)

/** A completed history page. An empty page is the account's real state. */
internal fun historyPageState(songs: List<Song>): UiState<List<Song>> =
    UiState.Success(songs)

/**
 * What the Local Music tab shows while a (re)scan is starting. A list already
 * on screen stays there; an error does not, or its stale retry button would sit
 * over the request that is already running.
 */
internal fun localMusicLoadState(current: UiState<List<Song>>): UiState<List<Song>> =
    if (current is UiState.Success) current else UiState.Loading
