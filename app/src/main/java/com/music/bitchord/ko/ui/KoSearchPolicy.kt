package com.music.bitchord.ko.ui

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.SearchSource

/** Work that may run after the first screen has had time to settle. */
internal enum class StartupDeferredWork { ACCOUNT, LIKED_IDS, UPDATE_CHECK }

/**
 * The only launch work allowed behind the start-up delay. Feeds are absent on
 * purpose: Explore and Library are fetched by the tab that shows them, never
 * by a timer racing Home's first request.
 *
 * LIKED_IDS is not a feed: it reads Liked Music only to seed which ids are
 * liked, nothing is published to a tab. Without it every heart read as empty
 * until the Library tab was opened, and the first tap on a liked song sent
 * LIKE again instead of unliking it (review F2b-1).
 */
internal fun startupDeferredWork(signedIn: Boolean): List<StartupDeferredWork> = buildList {
    if (signedIn) add(StartupDeferredWork.ACCOUNT)
    if (signedIn) add(StartupDeferredWork.LIKED_IDS)
    add(StartupDeferredWork.UPDATE_CHECK)
}

/**
 * Whether retrySearch has a request to repair.
 *
 * Library search is the one source that answers a blank query: it lists the
 * device folder rather than searching text. Its empty state and permission
 * error therefore carry a retry button with no query, and refusing that retry
 * left the button visible but inert.
 */
internal fun retrySearchAllowed(source: SearchSource, query: String): Boolean =
    query.isNotBlank() || source == SearchSource.LIBRARY

/**
 * Whether the cached page for a query may answer a request without a network
 * round trip.
 *
 * Any lookup reuses the cache — that keeps a filter tab, a suggestion tap and
 * a submit instant. A submit used to bypass it so the call reached the
 * account's history, but every search now leaves without the account
 * ([com.music.bitchord.ko.KoInnertube.musicRequestIsAnonymous], checked in
 * `Innertube.postMusic`), so that bypass only cost a request and a flicker
 * (review F2). A retry never reuses the cache: the cached page is exactly the
 * answer the listener asked to replace, and reusing an empty one made the
 * "no results" retry do nothing.
 */
internal fun reuseCachedSearchPage(
    forceNetwork: Boolean,
    hasCachedPage: Boolean,
): Boolean = hasCachedPage && !forceNetwork

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
