package com.music.bitchord.ko

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.MusicGateFallback

/**
 * Why a search request was made.
 *
 * Logged instead of the query itself: a diagnostic needs to know which path
 * asked, never what the listener typed.
 */
enum class SearchKind(val label: String) {
    TYPED("typed"),
    TYPEAHEAD("typeahead"),
    VIDEO_SWITCH("video-switch"),
    AUDIO_SWITCH("audio-switch"),
    CATALOG("catalog"),
    ARTIST("artist"),
    COVER("cover"),
    SUGGEST("suggest"),
}

/** [KO] search-privacy */
internal object KoSearch {
    /**
     * Whether [endpoint] is answered without the listener's account.
     *
     * Search and its suggestions are asked on the app's behalf as often as the
     * listener's — the original behind a cover, a song's other-language name,
     * typeahead keystrokes, chroma/timing candidates — and YouTube treats every
     * one made with a session as a search the listener performed, writing it
     * into their history and shaping recommendations around it. So none of
     * them may carry credentials.
     *
     * An impersonal browse page is anonymous for the same reason: moods and
     * genres, new releases, charts and Explore are the same page for everyone,
     * yet a signed-in account registered in another country (India) makes
     * Music answer them with *its* country's shelves whatever `gl` says. The
     * account-free request keeps `gl` and returns the content country's page.
     *
     * Decided by endpoint (and browse id) rather than by caller so a future
     * route cannot reintroduce the problem by calling the request builder
     * directly.
     */
    internal fun isAnonymousRequest(endpoint: String, browseId: String? = null): Boolean =
        isSearchEndpoint(endpoint) ||
            (endpoint == "browse" && browseId != null && isAnonymousBrowseId(browseId))

    /**
     * The one decision about whether a Music request leaves without the
     * account, shared by the request builders and by [Innertube.browse]'s
     * choice to observe the response. [forceSigned] exists for the gate
     * re-check: it must ask as the account even while the current verdict
     * would send everything anonymous.
     */
    internal fun musicRequestIsAnonymous(
        endpoint: String,
        browseId: String? = null,
        forceAnonymous: Boolean = false,
        forceSigned: Boolean = false,
    ): Boolean = !forceSigned &&
        (forceAnonymous || isAnonymousRequest(endpoint, browseId) ||
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
        anonymous || isAnonymousRequest(endpoint)

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
     *
     * [sessionVisitorData] is the signed-in shell's id, and sending it with a
     * credential-free search would hand Google the account association the
     * missing cookie was meant to remove.
     */
    internal fun visitorIdFor(
        endpoint: String,
        sessionVisitorData: String?,
        anonymousVisitorData: String?,
    ): String? = if (isAnonymousRequest(endpoint)) anonymousVisitorData else sessionVisitorData

    /** The `onBehalfOfUser` a request may name — never one for a search. */
    internal fun dataSyncIdForRequest(endpoint: String, dataSyncId: String?): String? =
        dataSyncId.takeUnless { isAnonymousRequest(endpoint) }

    /**
     * The account-specific headers a request to [endpoint] may carry, as a
     * pure decision so it can be tested without a socket.
     *
     * Empty for an anonymous request even when a cookie is present; that is
     * the whole privacy guarantee. [anonymous] is the caller's explicit
     * decision, so a force-anonymous browse of a normally signed endpoint
     * cannot leak the session. [authorization] is lazy so an anonymous
     * request never even computes a SAPISID hash.
     */
    internal fun accountHeadersFor(
        endpoint: String,
        cookie: String?,
        authUser: String,
        pageId: String?,
        authorization: () -> String?,
        anonymous: Boolean = false,
    ): Map<String, String> {
        if (anonymous || cookie == null || isAnonymousRequest(endpoint)) return emptyMap()
        return buildMap {
            put("Cookie", cookie)
            put("X-Goog-AuthUser", authUser)
            pageId?.let { put("X-Goog-PageId", it) }
            authorization()?.let { put("Authorization", it) }
        }
    }
}

