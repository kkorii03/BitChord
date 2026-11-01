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
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.security.MessageDigest
import java.util.Locale

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

/** The Korean fork's Innertube state and request rules, kept out of the upstream file. */
object KoInnertube {

    private const val TAG = "BitChord"

    private const val YOUTUBE_BASE = "https://www.youtube.com/youtubei/v1"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    /** The public web-client key every youtube.com page embeds; not an account credential. */
    private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val WEB_CLIENT_ID = "1"
    private const val WEB_VERSION = "2.20260708.00.00"

    /**
     * The iOS client, used only for [youtubePlaybackTracking] when the Music
     * gate is active: youtube.com's WEB player refuses anonymous and gated
     * sessions, while IOS answers and still carries the `playbackTracking`
     * block the account's history is written through.
     */
    private const val IOS_CLIENT_ID = "5"
    private const val IOS_CLIENT_VERSION = "21.29.1"
    internal const val IOS_USER_AGENT =
        "com.google.ios.youtube/21.29.1 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)"

    private const val IOS_DEVICE_MAKE = "Apple"
    private const val IOS_DEVICE_MODEL = "iPhone16,2"
    private const val IOS_OS_NAME = "iPhone"
    private const val IOS_OS_VERSION = "18.5.0.22F76"

    private const val DEFAULT_ATR_SECONDS = 5L

    /**
     * A visitor id that belongs to no account, for [isAnonymousRequest] calls.
     *
     * [Innertube.visitorData] is deliberately the signed-in shell's id once a
     * login happens, which is exactly what must not travel with a search:
     * Google can tie that id to the account, so an otherwise credential-free
     * search would still be attributable to the listener. This one is minted
     * from the unauthenticated bootstrap in [Innertube.fetchVisitorData] and
     * never replaced by a session-bound value.
     */
    @Volatile
    private var anonymousVisitorData: String? = null

    private val anonymousVisitorLock = Mutex()

    @Volatile
    private var visitorStore: Innertube.VisitorStore? = null

    @Volatile
    private var persistedVisitorKey: String? = null

    @Volatile
    private var persistedVisitorValue: String? = null

    /** Installs (or clears) the between-process home for [Innertube.visitorData]. */
    fun initVisitorStore(store: Innertube.VisitorStore?) {
        visitorStore = store
        persistedVisitorKey = null
        persistedVisitorValue = null
    }

    /**
     * Reads back the id left by the previous process when it was minted under
     * this exact cookie. A visitor id (and the PoTokens derived from it) is
     * bound to the cookie that asked for it, so a rotated cookie for the same
     * account gets a different slot and cannot inherit the old id.
     */
    fun restorePersistedVisitor(cookie: String?) {
        val store = visitorStore ?: return
        val key = visitorPersistenceKey(cookie)
        val value = store.getString(key)?.takeIf { Innertube.VISITOR_DATA.matches(it) } ?: return
        Innertube.visitorData = value
        Innertube.visitorDataIsSessionBound = store.getString(visitorBoundStorageKey(key)) == "1"
        persistedVisitorKey = key
        persistedVisitorValue = value
    }

    /** Writes the id only when the cookie or the value actually changed. */
    fun persistVisitor(cookie: String?) {
        val store = visitorStore ?: return
        val value = Innertube.visitorData ?: return
        val key = visitorPersistenceKey(cookie)
        if (key == persistedVisitorKey && value == persistedVisitorValue) return
        store.putString(key, value)
        store.putString(
            visitorBoundStorageKey(key),
            if (Innertube.visitorDataIsSessionBound) "1" else "0",
        )
        persistedVisitorKey = key
        persistedVisitorValue = value
    }

    /**
     * A visitor id minted without the session, for requests that must not be
     * attributable to the listener.
     *
     * Kept apart from [Innertube.visitorData] rather than reset when a login
     * happens: an anonymous request must never borrow the signed-in shell's id,
     * and the anonymous id stays anonymous no matter how many sign-ins follow.
     */
    suspend fun ensureAnonymousVisitorData(): String? {
        anonymousVisitorData?.let { return it }
        return anonymousVisitorLock.withLock {
            anonymousVisitorData?.let { return@withLock it }
            runCatching { Innertube.fetchVisitorData() }
                .onFailure { Log.w(TAG, "could not mint an anonymous visitor id: ${it.message}") }
                .getOrNull()
                ?.also { anonymousVisitorData = it }
        }
    }

    /** Records the visitor id a response handed out, without crossing accounts. */
    fun rememberVisitor(anonymous: Boolean, response: JsonObject) {
        val value = response["responseContext"]?.jsonObject
            ?.get("visitorData")?.jsonPrimitive?.content
        if (anonymous) {
            if (anonymousVisitorData == null) anonymousVisitorData = value
        } else if (Innertube.visitorData == null) {
            Innertube.visitorData = value
        }
    }

    /** The identity a per-session answer belongs to — see [Innertube.sessionKey]. */
    internal fun sessionKey(
        chosen: Innertube.ChannelSelection?,
        scope: Innertube.SessionScope?,
        cookie: String?,
    ): String {
        val id = chosen?.dataSyncId?.takeIf { it.isNotBlank() }
            ?: chosen?.pageId?.takeIf { it.isNotBlank() }
            ?: scope?.dataSyncId?.takeIf { it.isNotBlank() }
            ?: scope?.pageId?.takeIf { it.isNotBlank() }
        if (id != null) return "u:$id"
        val current = cookie ?: return "anonymous"
        return visitorCookieKey(current)
    }

    data class ResolvedSession(
        val pageId: String?,
        val dataSyncId: String?,
        val authUser: String,
        val visitorData: String?,
        val clientVersion: String?,
    )

    internal fun currentSession(scope: Innertube.SessionScope?, visitorData: String?): ResolvedSession? =
        scope?.let {
            ResolvedSession(
                pageId = it.pageId,
                dataSyncId = it.dataSyncId,
                authUser = it.authUser,
                visitorData = visitorData,
                clientVersion = it.clientVersion,
            )
        }

    // ---- Music gate hooks ---------------------------------------------------

    fun onBrowseFailure(browseId: String, anonymous: Boolean, failure: Exception) {
        val message = failure.message.orEmpty()
        val status = (failure as? ResponseException)?.response?.status?.value
        if (!anonymous && browseId.startsWith("FEmusic") && (status == 401 || status == 403)) {
            MusicGateFallback.markGated("music browse $browseId refused: ${message.take(60)}")
        }
    }

    fun observeBrowse(browseId: String, anonymous: Boolean, response: JsonObject) {
        if (!anonymous) MusicGateFallback.observeBrowse(browseId, response)
    }

    // ---- Search and browse request rules -----------------------------------

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
    fun isAnonymousRequest(endpoint: String, browseId: String? = null): Boolean =
        isSearchEndpoint(endpoint) ||
            (endpoint == "browse" && browseId != null && isAnonymousBrowseId(browseId))

    /**
     * The one decision about whether a Music request leaves without the
     * account, shared by [Innertube.postMusic] and by [Innertube.browse]'s
     * choice to observe the response. [forceSigned] exists for the gate
     * re-check: it must ask as the account even while the current verdict
     * would send everything anonymous.
     */
    fun musicRequestIsAnonymous(
        endpoint: String,
        browseId: String? = null,
        forceAnonymous: Boolean = false,
        forceSigned: Boolean = false,
    ): Boolean = !forceSigned &&
        (forceAnonymous || isAnonymousRequest(endpoint, browseId) ||
            (MusicGateFallback.isActive() && !isAccountWrite(endpoint)))

    /** Endpoints that carry a search query, on either host. */
    fun isSearchEndpoint(endpoint: String): Boolean =
        endpoint == "search" || endpoint == "music/get_search_suggestions"

    /** The kind logged when a caller did not state why it searched. */
    fun defaultSearchKind(endpoint: String): SearchKind =
        if (endpoint == "music/get_search_suggestions") SearchKind.SUGGEST else SearchKind.TYPED

    /**
     * The one line every search request writes.
     *
     * The query never appears here — only its length — and `account=no` is not
     * a parameter: a search request that could say yes is the bug this rule
     * exists to prevent, so the logger cannot record one.
     */
    fun searchLogLine(
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
    fun isAnonymousYouTubeRequest(endpoint: String, anonymous: Boolean): Boolean =
        anonymous || isAnonymousRequest(endpoint)

    /**
     * Browse pages that are not personal: the mood/genre grid and its
     * categories, new releases, charts (params select the country) and
     * Explore. Home, library, history, likes, playlists and radio are not
     * here and keep the session.
     */
    fun isAnonymousBrowseId(browseId: String): Boolean =
        browseId == "FEmusic_moods_and_genres" ||
            browseId.startsWith("FEmusic_moods_and_genres_category") ||
            browseId.startsWith("FEmusic_new_releases") ||
            browseId == Innertube.CHARTS_BROWSE_ID ||
            browseId == "FEmusic_explore"

    /** Endpoints that change the account; never sent signed out. */
    fun isAccountWrite(endpoint: String): Boolean =
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
    fun visitorIdFor(
        endpoint: String,
        sessionVisitorData: String?,
        anonymousVisitorData: String?,
    ): String? = if (isAnonymousRequest(endpoint)) anonymousVisitorData else sessionVisitorData

    /** The visitor id a request actually sends; mints the account-free one if needed. */
    suspend fun visitorFor(
        endpoint: String,
        anonymous: Boolean,
        sessionVisitorData: String?,
    ): String? = if (anonymous) {
        ensureAnonymousVisitorData()
    } else {
        visitorIdFor(endpoint, sessionVisitorData, null)
    }

    /** The `onBehalfOfUser` a request may name — never one for a search. */
    fun dataSyncIdForRequest(endpoint: String, dataSyncId: String?): String? =
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
    fun accountHeadersFor(
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

    // ---- Request plumbing ---------------------------------------------------

    /**
     * The shared Music request run, including the proxy failover.
     *
     * The route is decided once, before sending, so a selector change while a
     * request is in flight cannot make a direct answer count as a proxy
     * success. A dead proxy gets exactly one direct retry; the phone's whole
     * network being down leaves the monitor at UNKNOWN.
     */
    suspend fun <T> withProxyFailover(
        endpoint: String,
        musicUrl: String,
        directRetryOnProxyFailure: Boolean,
        attempt: suspend (HttpClient, Boolean) -> T,
    ): T {
        var proxyActive = YouTubeProxy.matches(musicUrl) && !ProxyHealthMonitor.shouldBypassProxy()
        return Innertube.withRetry {
            try {
                attempt(if (proxyActive) Innertube.routedClient else Innertube.client, proxyActive)
                    .also { if (proxyActive) ProxyHealthMonitor.recordSuccess() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (proxyActive && directRetryOnProxyFailure && !isAccountWrite(endpoint) &&
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
                                    .also { ProxyHealthMonitor.recordSuccess() }
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
                    if (ProxyFailure.directRetryProvesProxyDown(true)) {
                        ProxyHealthMonitor.recordUnreachable("music $endpoint")
                    }
                    direct
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * The one youtube.com request path, for the no-Premium fallback and the
     * history tracking block.
     *
     * It deliberately mirrors [Innertube.postMusic] rather than generalising
     * it: the two clients differ in origin, signed origin, client name/version,
     * API key and device context, and every one of those is something Google
     * compares.
     */
    suspend fun postYouTube(
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
        val anonymousRequest = isAnonymousYouTubeRequest(endpoint, anonymous)
        if (isSearchEndpoint(endpoint)) {
            check(anonymousRequest) { "youtube.com search must not carry the account" }
            Log.d(
                TAG,
                searchLogLine(
                    host = "www.youtube.com",
                    endpoint = endpoint,
                    kind = searchKind ?: defaultSearchKind(endpoint),
                    queryLength = searchQueryLength,
                ),
            )
        }
        // The session scope is read from the Music shell, which a gated Korean
        // session is refused; youtube.com does not need it to answer, so a
        // failure here must not take the fallback down with it.
        val cookie = Innertube.cookie
        if (!anonymousRequest && cookie != null) {
            runCatching { Innertube.ensureSessionScope() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        val session = if (anonymousRequest) null else Innertube.scope
        val credentials = if (anonymousRequest) {
            emptyMap()
        } else {
            accountHeadersFor(
                endpoint = endpoint,
                anonymous = anonymousRequest,
                cookie = cookie,
                authUser = Innertube.authUserFor(session),
                pageId = Innertube.pageIdFor(session),
                authorization = { cookie?.let { c -> authorizationFor(c, YOUTUBE_ORIGIN) } },
            )
        }
        val visitor = if (anonymousRequest) {
            ensureAnonymousVisitorData()
        } else {
            Innertube.visitorData ?: Innertube.ensureVisitorData()
        }
        val clientId = if (ios) IOS_CLIENT_ID else WEB_CLIENT_ID
        val clientVersion = if (ios) IOS_CLIENT_VERSION else WEB_VERSION
        val userAgent = if (ios) IOS_USER_AGENT else Innertube.WEB_USER_AGENT
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
        rememberVisitor(anonymousRequest, response)
        return response
    }

    // ---- youtube.com public API ---------------------------------------------

    suspend fun browseAnonymous(browseId: String, params: String? = null): JsonObject =
        Innertube.postMusic("browse", forceAnonymous = true) {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    suspend fun browseAnonymousThroughProxyOnly(browseId: String): JsonObject =
        Innertube.postMusic(
            "browse",
            forceAnonymous = true,
            directRetryOnProxyFailure = false,
        ) {
            put("browseId", browseId)
        }

    suspend fun browseYouTube(browseId: String, params: String? = null): JsonObject =
        postYouTube("browse") {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    suspend fun browseYouTubeContinuation(token: String): JsonObject = postYouTube(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }

    suspend fun browseMusicAnonymous(browseId: String): JsonObject =
        Innertube.postMusic("browse", browseId = browseId, forceAnonymous = true) {
            put("browseId", browseId)
        }

    suspend fun browseYouTubeAnonymous(
        browseId: String,
        params: String? = null,
    ): JsonObject = postYouTube("browse", anonymous = true) {
        put("browseId", browseId)
        params?.let { put("params", it) }
    }

    suspend fun browseYouTubeContinuationAnonymous(token: String): JsonObject = postYouTube(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        anonymous = true,
    ) {
        put("continuation", token)
    }

    /**
     * The category YouTube's own player metadata states for [videoId], read
     * without the listener's account.
     */
    suspend fun playerCategory(videoId: String): String? {
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
            header("User-Agent", Innertube.WEB_USER_AGENT)
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

    suspend fun playerResponse(videoId: String): JsonObject =
        postYouTube("player", anonymous = true) {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

    suspend fun watchNext(videoId: String): JsonObject =
        postYouTube("next", anonymous = true) {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

    suspend fun nextYouTube(videoId: String?, playlistId: String): JsonObject =
        postYouTube("next") {
            videoId?.let { put("videoId", it) }
            put("playlistId", playlistId)
        }

    suspend fun rateYouTube(videoId: String, status: LikeStatus) {
        Innertube.requireSession()
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

    suspend fun charts(country: String?): JsonObject =
        Innertube.postMusic("browse", browseId = Innertube.CHARTS_BROWSE_ID) {
            put("browseId", Innertube.CHARTS_BROWSE_ID)
            if (!country.isNullOrBlank()) {
                putJsonObject("formData") { putJsonArray("selectedValues") { add(country) } }
            }
        }

    suspend fun browseContinuationMusicAnonymous(token: String): JsonObject = Innertube.postMusic(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        forceAnonymous = true,
    ) {
        put("continuation", token)
    }

    suspend fun accountMenuYouTube(): JsonObject = postYouTube("account/account_menu") {}

    // ---- search -------------------------------------------------------------

    /**
     * YouTube Music's featured-playlist search filter (measured Korean IP,
     * 2026-10-19). It answers YouTube-Music-authored `RDCLAK` collections,
     * each with square editorial artwork.
     */
    private const val FEATURED_PLAYLISTS_PARAMS = "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ="

    /** The same search filtered to community playlists. */
    private const val COMMUNITY_PLAYLISTS_PARAMS = "EgeKAQQoAEABagwQDhAKEAMQBRAJEAQ="

    suspend fun search(
        query: String,
        params: String? = null,
        kind: SearchKind = SearchKind.TYPED,
    ): JsonObject =
        Innertube.postMusic("search", searchKind = kind, searchQueryLength = query.length) {
            put("query", query)
            params?.let { put("params", it) }
        }

    suspend fun searchContinuation(
        token: String,
        kind: SearchKind = SearchKind.TYPED,
    ): JsonObject = Innertube.postMusic(
        endpoint = "search",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        searchKind = kind,
    ) {
        put("continuation", token)
    }

    suspend fun searchFeaturedPlaylists(query: String): JsonObject =
        search(query, params = FEATURED_PLAYLISTS_PARAMS, kind = SearchKind.CATALOG)

    suspend fun searchCommunityPlaylists(query: String): JsonObject =
        search(query, params = COMMUNITY_PLAYLISTS_PARAMS, kind = SearchKind.CATALOG)

    suspend fun searchSuggestions(input: String): JsonObject =
        Innertube.postMusic(
            "music/get_search_suggestions",
            searchKind = SearchKind.SUGGEST,
            searchQueryLength = input.length,
        ) {
            put("input", input)
        }

    suspend fun searchTypeahead(query: String): JsonObject =
        Innertube.postMusic(
            "search",
            searchKind = SearchKind.TYPEAHEAD,
            searchQueryLength = query.length,
        ) {
            put("query", query)
        }

    suspend fun searchAnonymous(
        query: String,
        params: String? = null,
        hl: String = "en",
        gl: String = "US",
        kind: SearchKind = SearchKind.TYPED,
    ): JsonObject = Innertube.postMusic(
        "search",
        hl = hl,
        gl = gl,
        searchKind = kind,
        searchQueryLength = query.length,
    ) {
        put("query", query)
        params?.let { put("params", it) }
    }

    // ---- playback tracking --------------------------------------------------

    /**
     * The same block from youtube.com's own player.
     *
     * IOS first: it answers a gated Korean session where WEB is refused, and
     * its `playbackTracking` URLs write to the same account history the Music
     * client used to write. WEB is the second attempt for deployments where
     * IOS is the one being refused.
     */
    suspend fun youtubePlaybackTracking(
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

    private fun JsonObject.trackingUrl(key: String): String? =
        this[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content

    // ---- Authorization ------------------------------------------------------

    /**
     * The `Authorization` header youtube.com's own pages send for [cookieHeader].
     *
     * One hash per signing cookie the jar holds: `SAPISIDHASH`, then
     * `SAPISID1PHASH` (`__Secure-1PAPISID`) and `SAPISID3PHASH`
     * (`__Secure-3PAPISID`). A current Google login can carry only the
     * `__Secure-` forms; signing those with a lone `SAPISIDHASH` is answered
     * 401 "missing required authentication credential".
     */
    fun authorizationFor(
        cookieHeader: String,
        origin: String,
        timestamp: Long = nowSeconds(),
    ): String? {
        val jar = cookieHeader.split(';')
            .mapNotNull { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
        val plain = jar["SAPISID"] ?: jar["__Secure-3PAPISID"] ?: jar["__Secure-1PAPISID"] ?: return null
        return buildList {
            add(hashPart("SAPISIDHASH", plain, origin, timestamp))
            jar["__Secure-1PAPISID"]?.let { add(hashPart("SAPISID1PHASH", it, origin, timestamp)) }
            jar["__Secure-3PAPISID"]?.let { add(hashPart("SAPISID3PHASH", it, origin, timestamp)) }
        }.joinToString(" ")
    }

    internal fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun hashPart(label: String, secret: String, origin: String, timestamp: Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $secret $origin".toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "$label ${timestamp}_$digest"
    }

    /** First string value under [key] anywhere in [element], depth-first. */
    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> (element[key] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        is kotlinx.serialization.json.JsonArray ->
            element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }
}

/** Keep a minted visitor id out of another cookie's slot. */
internal fun visitorStorageKey(sessionKey: String): String = "innertube_visitor:$sessionKey"

/** Hash of the cookie that minted a visitor id; anonymous has one shared slot. */
internal fun visitorCookieKey(cookie: String?): String {
    if (cookie == null) return "anonymous"
    return "c:" + MessageDigest.getInstance("SHA-256")
        .digest(cookie.toByteArray())
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
        .take(16)
}

/**
 * The store slot for a visitor id.
 *
 * Keyed by the cookie, not [Innertube.sessionKey]: the same account can rotate
 * its cookie, and a visitor id (and the PoTokens minted from it) stays bound to
 * the cookie that asked for it.
 */
internal fun visitorPersistenceKey(cookie: String?): String =
    visitorStorageKey(visitorCookieKey(cookie))

/** Whether the stored id came from the signed-in shell rather than an anonymous mint. */
private fun visitorBoundStorageKey(key: String): String = "$key:bound"
