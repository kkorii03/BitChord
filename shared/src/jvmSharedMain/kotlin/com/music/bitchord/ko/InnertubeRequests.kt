package com.music.bitchord.ko

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.net.ProxyFailure
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.YouTubeProxy
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** [KO] request-plumbing */
internal object KoRequests {
    private const val TAG = "BitChord"
    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val YOUTUBE_BASE = "https://www.youtube.com/youtubei/v1"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
    private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val WEB_CLIENT_ID = "1"
    private const val WEB_REMIX_CLIENT_ID = "67"
    private const val WEB_VERSION = "2.20260708.00.00"
    private const val IOS_CLIENT_ID = "5"
    private const val IOS_CLIENT_VERSION = "21.29.1"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/21.29.1 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)"
    private const val IOS_DEVICE_MAKE = "Apple"
    private const val IOS_DEVICE_MODEL = "iPhone16,2"
    private const val IOS_OS_NAME = "iPhone"
    private const val IOS_OS_VERSION = "18.5.0.22F76"
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private const val DEFAULT_ATR_SECONDS = 5L
    private const val FEATURED_PLAYLISTS_PARAMS = "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ="
    private const val COMMUNITY_PLAYLISTS_PARAMS = "EgeKAQQoAEABagwQDhAKEAMQBRAJEAQ="

    internal suspend fun postMusic(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        hl: String = Innertube.currentLanguage,
        gl: String = Innertube.currentCountry,
        forceAnonymous: Boolean = false,
        forceSigned: Boolean = false,
        browseId: String? = null,
        directRetryOnProxyFailure: Boolean = true,
        searchKind: SearchKind? = null,
        searchQueryLength: Int? = null,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        // In Korea without Premium the signed-in Music session is refused
        // (401/403) on every page, while the same catalog pages — albums,
        // artists, playlists — answer an anonymous request (measured
        // 2026-10-18). Once the gate is known, Music is read signed out; the
        // account's own feeds come from youtube.com instead (YouTubeFallback).
        // Account writes keep the session so they fail visibly, not silently.
        val anonymous = KoSearch.musicRequestIsAnonymous(
            endpoint = endpoint,
            browseId = browseId,
            forceAnonymous = forceAnonymous,
            forceSigned = forceSigned,
        )
        if (KoSearch.isSearchEndpoint(endpoint)) {
            // isAnonymousRequest already forces this; the check keeps a future
            // search endpoint from ever logging (or sending) account=yes.
            check(anonymous) { "search request must not carry the account" }
            Log.d(
                TAG,
                KoSearch.searchLogLine(
                    host = "music.youtube.com",
                    endpoint = endpoint,
                    kind = searchKind ?: KoSearch.defaultSearchKind(endpoint),
                    queryLength = searchQueryLength,
                ),
            )
        }
        // The common authenticated path: Home, Library, likes, playlists,
        // account menus and all their continuations pass through it. Waiting
        // here makes restoration process-wide rather than a special case
        // implemented by whichever screen happened to open. Anonymous search
        // never waits for — or looks at — the account's scope.
        if (!anonymous && Innertube.cookie != null) Innertube.ensureSessionScope()
        val session = if (anonymous) null else Innertube.scope
        // [anonymous] is wider than the per-endpoint rule in [accountHeadersFor]
        // and [visitorIdFor] (a gated session reads every Music page signed
        // out), so it decides here: no cookie and the account-free visitor id.
        val requestCookie = if (anonymous) null else Innertube.cookie
        val credentials = KoSearch.accountHeadersFor(
            endpoint = endpoint,
            anonymous = anonymous,
            cookie = requestCookie,
            authUser = Innertube.authUserFor(session),
            pageId = Innertube.pageIdFor(session),
            // Lazy so an anonymous request never even hashes a credential.
            authorization = { requestCookie?.let { c -> KoAuth.authorizationFor(c, MUSIC_ORIGIN) } },
        )
        val anonymousVisitor = if (anonymous) KoVisitor.ensureAnonymousVisitorData() else null
        val visitor = if (anonymous) {
            KoSearch.visitorIdFor(endpoint, Innertube.visitorData, anonymousVisitor)
        } else {
            Innertube.visitorData
        }
        val clientVersion = Innertube.webRemixVersion
        val musicUrl = "$MUSIC_BASE/$endpoint"
        var proxyActive = YouTubeProxy.matches(musicUrl) && !ProxyHealthMonitor.shouldBypassProxy()
        val response = Innertube.withRetry {
            val attempt: suspend (HttpClient, Boolean) -> JsonObject = { http, throughProxy ->
                val answer = http.post(musicUrl) {
                    contentType(ContentType.Application.Json)
                    parameter("prettyPrint", "false")
                    parameter("hl", hl)
                    header("Accept-Language", Innertube.acceptLanguageHeader)
                    query.forEach { (key, value) -> parameter(key, value) }
                    header("X-Origin", MUSIC_ORIGIN)
                    header("Origin", MUSIC_ORIGIN)
                    header("Referer", "$MUSIC_ORIGIN/")
                    // Stats pings are only honoured for a session Google recognises
                    // as a real client, so identify as one here too — the visitor
                    // id is minted on the first call and reused for the session.
                    header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                    header("X-YouTube-Client-Version", clientVersion)
                    visitor?.let { header("X-Goog-Visitor-Id", it) }
                    credentials.forEach { (name, value) -> header(name, value) }
                    setBody(
                        buildJsonObject {
                            putJsonObject("context") {
                                putJsonObject("client") {
                                    put("clientName", "WEB_REMIX")
                                    put("clientVersion", clientVersion)
                                    put("hl", hl)
                                    put("gl", gl)
                                    visitor?.let { put("visitorData", it) }
                                }
                                putJsonObject("user") {
                                    put("lockedSafetyMode", false)
                                    // Only ever a value read back from a shell that
                                    // said it was signed in: Google answers an
                                    // `onBehalfOfUser` it cannot tie to the cookie
                                    // with 401, so a guess here would take the
                                    // whole app down rather than just history. An
                                    // anonymous request names no account at all —
                                    // not even the chosen channel's, which outranks
                                    // the session in [dataSyncIdFor].
                                    if (!anonymous) {
                                        KoSearch.dataSyncIdForRequest(
                                            endpoint,
                                            Innertube.dataSyncIdFor(session),
                                        )?.let { put("onBehalfOfUser", it) }
                                    }
                                }
                                putJsonObject("request") { put("useSsl", true) }
                            }
                            bodyExtras()
                        },
                    )
                }.body<JsonObject>()
                if (throughProxy) ProxyHealthMonitor.recordSuccess()
                answer
            }
            try {
                attempt(if (proxyActive) Innertube.routedClient else Innertube.client, proxyActive)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (proxyActive && directRetryOnProxyFailure && !KoSearch.isAccountWrite(endpoint) &&
                    ProxyFailure.isProxyDown(e)
                ) {
                    // With a backup configured this is recorded first, because
                    // it is what moves the route there. Without one, recording
                    // it now would condemn the proxy before the direct retry
                    // has proved the phone's own network is working — a
                    // transient proxy timeout on a flaky connection used to
                    // leave the proxy bypassed for five minutes even though
                    // the direct route failed too. The mark is made below,
                    // only after that retry succeeds.
                    if (YouTubeProxy.config.backupUsable) {
                        ProxyHealthMonitor.recordUnreachable("music $endpoint")
                        if (YouTubeProxy.usingBackup && !ProxyHealthMonitor.isMarkedUnreachable()) {
                            try {
                                return@withRetry attempt(Innertube.routedClient, true)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (backupFailure: Exception) {
                                if (!ProxyFailure.isProxyDown(backupFailure)) throw backupFailure
                                ProxyHealthMonitor.recordBackupUnreachable()
                            }
                        }
                    }
                    Log.w(TAG, "music $endpoint: proxy unreachable, retrying directly")
                    proxyActive = false
                    var directRetrySucceeded = false
                    val direct = try {
                        attempt(Innertube.directClient, false)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (directFailure: Exception) {
                        // The phone's whole network is down, not just the proxy.
                        // Rethrow what the direct route said and leave
                        // [ProxyHealthMonitor] at UNKNOWN: no toast, no 5-minute
                        // bypass, because there is nothing reachable either way.
                        throw directFailure
                    }
                    directRetrySucceeded = true
                    if (ProxyFailure.directRetryProvesProxyDown(directRetrySucceeded)) {
                        ProxyHealthMonitor.recordUnreachable("music $endpoint")
                    }
                    direct
                } else {
                    throw e
                }
            }
        }

        val responseVisitorData = response["responseContext"]?.jsonObject
            ?.get("visitorData")?.jsonPrimitive?.content
        if (anonymous) {
            // A visitor id handed out on an unauthenticated request is safe to
            // reuse for later ones; it never becomes the account's id.
            if (KoVisitor.anonymousVisitorData == null) {
                KoVisitor.anonymousVisitorData = responseVisitorData
            }
        } else if (Innertube.visitorData == null) {
            Innertube.visitorData = responseVisitorData
        }
        return response
    }

    /**
     * The one youtube.com request path, for the no-Premium fallback and the
     * history tracking block.
     *
     * It deliberately mirrors [postMusic] rather than generalising it: the two
     * clients differ in origin, signed origin, client name/version, API key and
     * device context, and every one of those is something Google compares.
     */
    private suspend fun postYouTube(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        hl: String = Innertube.currentLanguage,
        gl: String = Innertube.currentCountry,
        ios: Boolean = false,
        anonymous: Boolean = false,
        searchKind: SearchKind? = null,
        searchQueryLength: Int? = null,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val anonymousRequest = KoSearch.isAnonymousYouTubeRequest(endpoint, anonymous)
        if (KoSearch.isSearchEndpoint(endpoint)) {
            check(anonymousRequest) { "youtube.com search must not carry the account" }
            Log.d(
                TAG,
                KoSearch.searchLogLine(
                    host = "www.youtube.com",
                    endpoint = endpoint,
                    kind = searchKind ?: KoSearch.defaultSearchKind(endpoint),
                    queryLength = searchQueryLength,
                ),
            )
        }
        // The session scope is read from the Music shell, which a gated Korean
        // session is refused; youtube.com does not need it to answer, so a
        // failure here must not take the fallback down with it.
        if (!anonymousRequest && Innertube.cookie != null) {
            runCatching { Innertube.ensureSessionScope() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        val session = if (anonymousRequest) null else Innertube.scope
        val credentials = if (anonymousRequest) {
            emptyMap<String, String>()
        } else {
            KoSearch.accountHeadersFor(
                endpoint = endpoint,
                anonymous = anonymousRequest,
                cookie = Innertube.cookie,
                authUser = Innertube.authUserFor(session),
                pageId = Innertube.pageIdFor(session),
                authorization = {
                    Innertube.cookie?.let { c -> KoAuth.authorizationFor(c, YOUTUBE_ORIGIN) }
                },
            )
        }
        val visitor = if (anonymousRequest) {
            KoVisitor.ensureAnonymousVisitorData()
        } else {
            Innertube.visitorData ?: Innertube.ensureVisitorData()
        }
        val clientId = if (ios) IOS_CLIENT_ID else WEB_CLIENT_ID
        val clientVersion = if (ios) IOS_CLIENT_VERSION else WEB_VERSION
        val userAgent = if (ios) IOS_USER_AGENT else WEB_USER_AGENT
        val response = Innertube.withRetry {
            Innertube.client.post("$YOUTUBE_BASE/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                parameter("key", WEB_API_KEY)
                parameter("hl", hl)
                header("Accept-Language", Innertube.acceptLanguageHeader)
                query.forEach { (key, value) -> parameter(key, value) }
                header("X-Origin", YOUTUBE_ORIGIN)
                header("Origin", YOUTUBE_ORIGIN)
                header("Referer", "$YOUTUBE_ORIGIN/")
                header("X-YouTube-Client-Name", clientId)
                header("X-YouTube-Client-Version", clientVersion)
                header("User-Agent", userAgent)
                visitor?.let { header("X-Goog-Visitor-Id", it) }
                credentials.forEach { (name, value) -> header(name, value) }
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", if (ios) "IOS" else "WEB")
                                put("clientVersion", clientVersion)
                                put("hl", hl)
                                put("gl", gl)
                                visitor?.let { put("visitorData", it) }
                                if (ios) {
                                    put("deviceMake", IOS_DEVICE_MAKE)
                                    put("deviceModel", IOS_DEVICE_MODEL)
                                    put("osName", IOS_OS_NAME)
                                    put("osVersion", IOS_OS_VERSION)
                                    put("userAgent", IOS_USER_AGENT)
                                }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                if (!anonymousRequest) {
                                    Innertube.dataSyncIdFor(session)?.let { put("onBehalfOfUser", it) }
                                }
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.body<JsonObject>()
        }
        if (anonymousRequest) {
            if (KoVisitor.anonymousVisitorData == null) {
                KoVisitor.anonymousVisitorData = response["responseContext"]?.jsonObject
                    ?.get("visitorData")?.jsonPrimitive?.content
            }
        } else if (Innertube.visitorData == null) {
            Innertube.visitorData = response["responseContext"]?.jsonObject
                ?.get("visitorData")?.jsonPrimitive?.content
        }
        return response
    }

    internal suspend fun browse(
        browseId: String,
        params: String? = null,
        forceSigned: Boolean = false,
    ): JsonObject {
        // An anonymous page says nothing about the account: moods/genres,
        // charts and Explore are sent without the cookie even while signed in.
        // Its response (and its refusal) must therefore not become the
        // signed-in identity's verdict. A gate re-check passes [forceSigned]
        // so the answer it is meant to learn from is asked as the account.
        val anonymous = KoSearch.musicRequestIsAnonymous("browse", browseId, forceSigned = forceSigned)
        val response = try {
            postMusic("browse", browseId = browseId, forceSigned = forceSigned) {
                put("browseId", browseId)
                params?.let { put("params", it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Signed in from Korea without Premium, Music's own pages are not
            // answered empty but refused outright (401/403) — the phone showed
            // "YouTube Music에서 요청을 거부했습니다". That is the same gate the
            // empty-home check looks for, so it switches the session to the
            // youtube.com fallback instead of leaving Home on an error.
            val message = e.message.orEmpty()
            val status = (e as? ResponseException)?.response?.status?.value
            if (!anonymous && browseId.startsWith("FEmusic") && (status == 401 || status == 403)) {
                MusicGateFallback.markGated("music browse $browseId refused: ${message.take(60)}")
            }
            throw e
        }
        if (!anonymous) MusicGateFallback.observeBrowse(browseId, response)
        return response
    }

    internal suspend fun browseAnonymous(browseId: String, params: String? = null): JsonObject =
        postMusic("browse", forceAnonymous = true) {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    internal suspend fun browseAnonymousThroughProxyOnly(browseId: String): JsonObject =
        postMusic("browse", forceAnonymous = true, directRetryOnProxyFailure = false) {
            put("browseId", browseId)
        }

    internal suspend fun browseYouTube(browseId: String, params: String? = null): JsonObject =
        postYouTube("browse") {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    internal suspend fun browseYouTubeContinuation(token: String): JsonObject = postYouTube(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }

    internal suspend fun browseMusicAnonymous(browseId: String): JsonObject =
        postMusic("browse", browseId = browseId, forceAnonymous = true) {
            put("browseId", browseId)
        }

    internal suspend fun browseYouTubeAnonymous(
        browseId: String,
        params: String? = null,
    ): JsonObject = postYouTube("browse", anonymous = true) {
        put("browseId", browseId)
        params?.let { put("params", it) }
    }

    internal suspend fun browseYouTubeContinuationAnonymous(token: String): JsonObject = postYouTube(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        anonymous = true,
    ) {
        put("continuation", token)
    }

    internal suspend fun playerCategory(videoId: String): String? {
        if (videoId.isBlank()) return null
        val response = Innertube.client.post("$YOUTUBE_BASE/player") {
            contentType(ContentType.Application.Json)
            parameter("prettyPrint", "false")
            parameter("key", WEB_API_KEY)
            parameter("hl", "en")
            header("Accept-Language", Innertube.acceptLanguageHeader)
            header("X-Origin", YOUTUBE_ORIGIN)
            header("Origin", YOUTUBE_ORIGIN)
            header("Referer", "$YOUTUBE_ORIGIN/")
            header("X-YouTube-Client-Name", WEB_CLIENT_ID)
            header("X-YouTube-Client-Version", WEB_VERSION)
            header("User-Agent", WEB_USER_AGENT)
            timeout {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 4_000
                socketTimeoutMillis = 6_000
            }
            setBody(
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "WEB")
                            put("clientVersion", WEB_VERSION)
                            put("hl", "en")
                            put("gl", Innertube.currentCountry)
                        }
                    }
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                },
            )
        }.body<JsonObject>()
        return response["microformat"]?.jsonObject
            ?.get("playerMicroformatRenderer")?.jsonObject
            ?.get("category")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    internal suspend fun playerResponse(videoId: String): JsonObject =
        postYouTube("player", anonymous = true) {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

    internal suspend fun watchNext(videoId: String): JsonObject =
        postYouTube("next", anonymous = true) {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

    internal suspend fun nextYouTube(videoId: String?, playlistId: String): JsonObject =
        postYouTube("next") {
            videoId?.let { put("videoId", it) }
            put("playlistId", playlistId)
        }

    internal suspend fun rateYouTube(videoId: String, status: LikeStatus) {
        if (Innertube.cookie == null) throw Innertube.NotSignedInException()
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        val response = postYouTube(endpoint) {
            putJsonObject("target") { put("videoId", videoId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube refused the rating: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $videoId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    internal suspend fun charts(country: String?): JsonObject =
        postMusic("browse", browseId = Innertube.CHARTS_BROWSE_ID) {
            put("browseId", Innertube.CHARTS_BROWSE_ID)
            if (!country.isNullOrBlank()) {
                putJsonObject("formData") { putJsonArray("selectedValues") { add(country) } }
            }
        }

    internal suspend fun browseContinuationMusicAnonymous(token: String): JsonObject = postMusic(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        forceAnonymous = true,
    ) {
        put("continuation", token)
    }

    internal suspend fun accountMenuYouTube(): JsonObject =
        postYouTube("account/account_menu") {}

    internal suspend fun search(
        query: String,
        params: String? = null,
        anonymous: Boolean = true,
        kind: SearchKind = SearchKind.TYPED,
    ): JsonObject =
        postMusic("search", searchKind = kind, searchQueryLength = query.length) {
            put("query", query)
            params?.let { put("params", it) }
        }

    internal suspend fun searchContinuation(
        token: String,
        kind: SearchKind = SearchKind.TYPED,
    ): JsonObject = postMusic(
        endpoint = "search",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        searchKind = kind,
    ) {
        put("continuation", token)
    }

    internal suspend fun searchFeaturedPlaylists(query: String): JsonObject =
        search(query, params = FEATURED_PLAYLISTS_PARAMS, kind = SearchKind.CATALOG)

    internal suspend fun searchCommunityPlaylists(query: String): JsonObject =
        search(query, params = COMMUNITY_PLAYLISTS_PARAMS, kind = SearchKind.CATALOG)

    internal suspend fun searchSuggestions(input: String): JsonObject =
        postMusic(
            "music/get_search_suggestions",
            searchKind = SearchKind.SUGGEST,
            searchQueryLength = input.length,
        ) {
            put("input", input)
        }

    internal suspend fun searchTypeahead(query: String): JsonObject =
        postMusic(
            "search",
            searchKind = SearchKind.TYPEAHEAD,
            searchQueryLength = query.length,
        ) {
            put("query", query)
        }

    internal suspend fun searchAnonymous(
        query: String,
        params: String? = null,
        hl: String = "en",
        gl: String = "US",
        kind: SearchKind = SearchKind.TYPED,
    ): JsonObject = postMusic(
        "search",
        hl = hl,
        gl = gl,
        searchKind = kind,
        searchQueryLength = query.length,
    ) {
        put("query", query)
        params?.let { put("params", it) }
    }

    internal suspend fun playbackTracking(
        videoId: String,
        signatureTimestamp: Int?,
    ): Innertube.PlaybackTracking? {
        if (Innertube.cookie == null) return null
        Innertube.ensureSessionScope()
        // A gated listener has no Music player to ask; IOS on youtube.com still
        // answers and still carries the history-tracking block.
        if (MusicGateFallback.isActive()) return youtubePlaybackTracking(videoId, signatureTimestamp)
        val response = postMusic("player") {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            // Real clients always describe where playback is happening; the
            // response's tracking block is scoped to it.
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("referer", "$MUSIC_ORIGIN/watch?v=$videoId")
                    signatureTimestamp?.let { put("signatureTimestamp", it) }
                }
            }
        }
        MusicGateFallback.observePlayer(response)
        if (MusicGateFallback.isActive()) return youtubePlaybackTracking(videoId, signatureTimestamp)
        val tracking = response["playbackTracking"]?.jsonObject
        if (tracking == null) {
            val playability = response["playabilityStatus"]?.jsonObject
            Log.w(
                TAG,
                "player response has no playbackTracking for $videoId " +
                    "(status=${playability?.get("status")?.jsonPrimitive?.content}, " +
                    "reason=${playability?.get("reason")?.jsonPrimitive?.content}, " +
                    "sts=${signatureTimestamp ?: "none"})",
            )
            return null
        }
        val playbackUrl = tracking.trackingUrl("videostatsPlaybackUrl") ?: return null
        return Innertube.PlaybackTracking(
            playbackUrl = playbackUrl,
            watchtimeUrl = tracking.trackingUrl("videostatsWatchtimeUrl"),
            atrUrl = tracking.trackingUrl("atrUrl"),
            atrAfterSeconds = tracking["atrUrl"]?.jsonObject
                ?.get("elapsedMediaTimeSeconds")?.jsonPrimitive?.contentOrNull
                ?.toLongOrNull() ?: DEFAULT_ATR_SECONDS,
            origin = MUSIC_ORIGIN,
            clientName = "WEB_REMIX",
            clientVersion = Innertube.webRemixVersion,
        )
    }

    /**
     * The same block from youtube.com's own player.
     *
     * IOS first: it answers a gated Korean session where WEB is refused, and
     * its `playbackTracking` URLs write to the same account history the Music
     * client used to write. WEB is the second attempt for deployments where
     * IOS is the one being refused.
     */
    private suspend fun youtubePlaybackTracking(
        videoId: String,
        signatureTimestamp: Int?,
    ): Innertube.PlaybackTracking? {
        for (ios in listOf(true, false)) {
            val response = runCatching {
                postYouTube(
                    endpoint = "player",
                    ios = ios,
                ) {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    putJsonObject("playbackContext") {
                        putJsonObject("contentPlaybackContext") {
                            put("html5Preference", "HTML5_PREF_WANTS")
                            put("referer", "$YOUTUBE_ORIGIN/watch?v=$videoId")
                            signatureTimestamp?.let { put("signatureTimestamp", it) }
                        }
                    }
                }
            }.onFailure {
                Log.w(TAG, "youtube.com player ${if (ios) "IOS" else "WEB"} failed for $videoId: ${it.message}")
            }.getOrNull() ?: continue
            MusicGateFallback.observePlayer(response)
            val tracking = response["playbackTracking"]?.jsonObject ?: continue
            val playbackUrl = tracking.trackingUrl("videostatsPlaybackUrl") ?: continue
            return Innertube.PlaybackTracking(
                playbackUrl = playbackUrl,
                watchtimeUrl = tracking.trackingUrl("videostatsWatchtimeUrl"),
                atrUrl = tracking.trackingUrl("atrUrl"),
                atrAfterSeconds = tracking["atrUrl"]?.jsonObject
                    ?.get("elapsedMediaTimeSeconds")?.jsonPrimitive?.contentOrNull
                    ?.toLongOrNull() ?: DEFAULT_ATR_SECONDS,
                origin = YOUTUBE_ORIGIN,
                clientName = if (ios) "IOS" else "WEB",
                clientVersion = if (ios) IOS_CLIENT_VERSION else WEB_VERSION,
            )
        }
        return null
    }

    internal suspend fun pingPlayback(
        baseUrl: String,
        cpn: String,
        origin: String = MUSIC_ORIGIN,
        clientName: String = "WEB_REMIX",
        clientVersion: String = Innertube.WEB_REMIX_VERSION,
    ) = pingStats(baseUrl, cpn, origin, clientName, clientVersion) {}

    internal suspend fun pingWatchtime(
        baseUrl: String,
        cpn: String,
        seconds: Long,
        final: Boolean = false,
        origin: String = MUSIC_ORIGIN,
        clientName: String = "WEB_REMIX",
        clientVersion: String = Innertube.WEB_REMIX_VERSION,
    ) =
        pingStats(baseUrl, cpn, origin, clientName, clientVersion) {
            parameter("st", "0")
            parameter("et", seconds.toString())
            // Where the playhead is, as distinct from how much was watched.
            // The web client sends both and they are not redundant: `et` bounds
            // a segment, `cmt` is a position.
            parameter("cmt", seconds.toString())
            parameter("state", if (final) "paused" else "playing")
            if (final) parameter("final", "1")
        }

    internal suspend fun pingAtr(
        baseUrl: String,
        cpn: String,
        origin: String = MUSIC_ORIGIN,
        clientName: String = "WEB_REMIX",
    ): Int {
        // Playback can outlive the Activity that restored the session. Ensure
        // the selected profile's headers exist before this first history ping.
        if (Innertube.cookie != null) Innertube.ensureSessionScope()
        val session = Innertube.scope
        return Innertube.client.get(baseUrl) {
            parameter("cpn", cpn)
            statsHeaders(session, origin, userAgentFor(clientName))
        }.status.value
    }

    /** Shared shape of the s.youtube.com stats pings, including session auth. */
    private suspend fun pingStats(
        baseUrl: String,
        cpn: String,
        origin: String,
        clientName: String,
        clientVersion: String,
        extras: HttpRequestBuilder.() -> Unit,
    ): Int {
        if (Innertube.cookie != null) Innertube.ensureSessionScope()
        val session = Innertube.scope
        val ios = clientName == "IOS"
        return Innertube.client.get(baseUrl) {
            parameter("ver", "2")
            parameter("c", clientName)
            parameter("cver", if (clientVersion.isBlank()) Innertube.webRemixVersion else clientVersion)
            parameter("cpn", cpn)
            // What the client says about itself. Cheap, and the pings are
            // weighted by how much they look like a real session.
            parameter("cplayer", "UNIPLAYER")
            if (ios) {
                parameter("cbr", "iPhone")
                parameter("cbrver", "18.5")
                parameter("cos", "iPhone")
                parameter("cosver", "18.5")
            } else {
                parameter("cbr", "Chrome")
                parameter("cbrver", "141.0.0.0")
                parameter("cos", "Windows")
                parameter("cosver", "10.0")
            }
            parameter("hl", Innertube.currentLanguage.replace('-', '_'))
            parameter("cr", Innertube.currentCountry)
            extras()
            statsHeaders(session, origin, userAgentFor(clientName))
        }.status.value
    }

    private fun userAgentFor(clientName: String): String =
        if (clientName == "IOS") IOS_USER_AGENT else WEB_USER_AGENT

    /**
     * The three headers the tracking block asks for by name — `USER_AUTH`,
     * `VISITOR_ID` and `PLUS_PAGE_ID`. Google lists them per ping URL in the
     * player response; sending fewer is what makes a ping land somewhere other
     * than the listener's own history.
     */
    private fun HttpRequestBuilder.statsHeaders(
        session: Innertube.SessionScope?,
        origin: String,
        userAgent: String,
    ) {
        header("X-Origin", origin)
        header("Origin", origin)
        header("Referer", "$origin/")
        header("User-Agent", userAgent)
        Innertube.visitorData?.let { header("X-Goog-Visitor-Id", it) }
        Innertube.cookie?.let { c ->
            header("Cookie", c)
            header("X-Goog-AuthUser", Innertube.authUserFor(session))
            Innertube.pageIdFor(session)?.let { header("X-Goog-PageId", it) }
            KoAuth.authorizationFor(c, origin)?.let { header("Authorization", it) }
        }
    }

    private fun JsonObject.trackingUrl(key: String): String? =
        this[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content

    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> (element[key] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }
}

