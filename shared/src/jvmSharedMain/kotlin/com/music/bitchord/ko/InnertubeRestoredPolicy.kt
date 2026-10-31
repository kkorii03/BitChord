package com.music.bitchord.ko

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.innertube.SearchKind

/*
 * Korean-fork request policy restored after the v1.9 merge.
 *
 * v1.9 has no Korea gate and no proxy playback, so our anonymous-request and
 * anonymous-search rules (and the log lines that make them observable) live
 * here. Innertube keeps one-line member delegates so existing callers and the
 * privacy tests keep working.
 */

internal fun musicRequestIsAnonymous(
    endpoint: String,
    browseId: String? = null,
    forceAnonymous: Boolean = false,
    forceSigned: Boolean = false,
): Boolean = !forceSigned &&
    (forceAnonymous || Innertube.isAnonymousRequest(endpoint, browseId) ||
        (MusicGateFallback.isActive() && !isAccountWrite(endpoint)))

/** Endpoints that carry a search query, on either host. */
internal fun isSearchEndpoint(endpoint: String): Boolean =
    endpoint == "search" || endpoint == "music/get_search_suggestions"

/** The kind logged when a caller did not state why it searched. */
internal fun defaultSearchKind(endpoint: String): SearchKind =
    if (endpoint == "music/get_search_suggestions") SearchKind.SUGGEST else SearchKind.TYPED

/**
 * The one line every search request writes.
 *
 * The query never appears here — only its length — and `account=no` is not
 * a parameter: a search request that could say yes is the bug this rule
 * exists to prevent, so the logger cannot record one.
 */
internal fun searchLogLine(
    host: String,
    endpoint: String,
    kind: SearchKind,
    queryLength: Int?,
): String = buildString {
    append("search: ").append(host).append('/').append(endpoint)
    append(" kind=").append(kind.label)
    append(" account=no")
    queryLength?.let { append(" length=").append(it) }
}

/**
 * Whether a youtube.com request is anonymous, including an explicit
 * request for the session on a search endpoint.
 */
internal fun isAnonymousYouTubeRequest(endpoint: String, anonymous: Boolean): Boolean =
    anonymous || Innertube.isAnonymousRequest(endpoint)

/**
 * Browse pages that are not personal: the mood/genre grid and its
 * categories, new releases, charts (params select the country) and
 * Explore. Home, library, history, likes, playlists and radio are not
 * here and keep the session.
 */
internal fun isAnonymousBrowseId(browseId: String): Boolean =
    browseId == "FEmusic_moods_and_genres" ||
        browseId.startsWith("FEmusic_moods_and_genres_category") ||
        browseId.startsWith("FEmusic_new_releases") ||
        browseId == Innertube.CHARTS_BROWSE_ID ||
        browseId == "FEmusic_explore"

/** Endpoints that change the account; never sent signed out. */
internal fun isAccountWrite(endpoint: String): Boolean =
    endpoint.startsWith("like/") || endpoint.startsWith("playlist/") ||
        endpoint.startsWith("browse/edit_playlist") || endpoint.startsWith("account/") ||
        endpoint.startsWith("subscription/") || endpoint.startsWith("feedback")

/**
 * The visitor id a request may carry: the account-free one for a search,
 * the session's one for everything else.
 */
internal fun visitorIdFor(
    endpoint: String,
    sessionVisitorData: String?,
    anonymousVisitorData: String?,
): String? = if (Innertube.isAnonymousRequest(endpoint)) anonymousVisitorData else sessionVisitorData

/** The `onBehalfOfUser` a request may name — never one for a search. */
internal fun dataSyncIdForRequest(endpoint: String, dataSyncId: String?): String? =
    dataSyncId.takeUnless { Innertube.isAnonymousRequest(endpoint) }

/**
 * The account-specific headers a request to [endpoint] may carry, as a pure
 * decision so it can be tested without a socket.
 */
internal fun accountHeadersFor(
    endpoint: String,
    cookie: String?,
    authUser: String,
    pageId: String?,
    authorization: () -> String?,
    anonymous: Boolean = false,
): Map<String, String> {
    if (anonymous || cookie == null || Innertube.isAnonymousRequest(endpoint)) return emptyMap()
    return buildMap {
        put("Cookie", cookie)
        put("X-Goog-AuthUser", authUser)
        pageId?.let { put("X-Goog-PageId", it) }
        authorization()?.let { put("Authorization", it) }
    }
}
