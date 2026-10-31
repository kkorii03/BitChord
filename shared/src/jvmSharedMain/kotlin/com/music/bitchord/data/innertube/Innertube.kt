package com.music.bitchord.data.innertube

import com.music.bitchord.auth.normalizeDataSyncId
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.net.ProxyFailure
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.YouTubeProxy
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaylistMove
import com.music.bitchord.data.model.PlaylistPrivacy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
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

/**
 * Minimal Innertube (youtubei) client.
 *
 * WEB_REMIX against music.youtube.com for browse/search/library. It returns
 * the full YT Music shelf layout and honours the signed-in session. Stream
 * URLs are not fetched here; see [InnerTubeXResolver].
 *
 * Authenticated requests are signed with Google's SAPISIDHASH scheme derived
 * from the stored cookie; no long-lived token is ever minted or stored.
 *
 * Search and its suggestions are the deliberate exception: every one is sent
 * without the account (see [isAnonymousRequest]), because YouTube records a
 * signed-in search in the listener's history even when the app asked. The
 * youtube.com builder applies the same rule, so its `search` endpoint cannot
 * carry the session even if a caller asks for it.
 */
object Innertube {
    /**
     * The app's own language when the platform lets the listener pick one
     * separately from the system's — the phone's per-app locale. Null means
     * follow the JVM default.
     */
    @Volatile
    var appLanguage: () -> String? = { null }

    /**
     * The country whose catalogue, charts and moods YouTube Music answers
     * with — the `gl` of every signed request. Only the request's: the
     * account's own location (and so its Premium plan) is never touched.
     * Two letters, upper case; anything else falls back to "US".
     */
    @Volatile
    var contentCountry: () -> String? = { null }

    val currentCountry: String
        get() = contentCountry()?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            ?: "US"

    val currentLocaleTag: String
        get() {
            val raw = appLanguage()?.ifEmpty { null }
                ?: Locale.getDefault().toLanguageTag().ifEmpty { "en" }
            return com.music.bitchord.data.LocaleTags.normalizeAppTag(raw)
        }

    val currentLanguage: String
        get() = com.music.bitchord.data.LocaleTags.ytHl(currentLocaleTag)

    val currentRegion: String
        get() = com.music.bitchord.data.LocaleTags.ytGl(currentLocaleTag)

    /** [currentRegion] for search, where null lets YouTube place us — see [LocaleTags.ytSearchGl]. */
    private val searchRegion: String?
        get() = com.music.bitchord.data.LocaleTags.ytSearchGl(currentLocaleTag)

    private val acceptLanguageHeader: String
        get() = com.music.bitchord.data.LocaleTags.acceptLanguage(currentLocaleTag)

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val YOUTUBE_BASE = "https://www.youtube.com/youtubei/v1"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    /** The public web-client key every youtube.com page embeds; not an account credential. */
    private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val WEB_CLIENT_ID = "1"
    private const val WEB_VERSION = "2.20260708.00.00"

    /**
     * The iOS client, used only for [playbackTracking] when the Music gate is
     * active: youtube.com's WEB player refuses anonymous and gated sessions,
     * while IOS answers and still carries the `playbackTracking` block the
     * account's history is written through.
     */
    private const val IOS_CLIENT_ID = "5"
    private const val IOS_CLIENT_VERSION = "21.29.1"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/21.29.1 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)"

    private const val IOS_DEVICE_MAKE = "Apple"
    private const val IOS_DEVICE_MODEL = "iPhone16,2"
    private const val IOS_OS_NAME = "iPhone"
    private const val IOS_OS_VERSION = "18.5.0.22F76"

    /**
     * Fallback WEB_REMIX version, used until [SessionScope] reads the live one
     * out of the music.youtube.com shell. Only a starting point: the real
     * version moves every few days, and the one that matters is the one
     * [webRemixVersion] reports.
     */
    private const val WEB_REMIX_VERSION = "1.20250101.01.00"
    private const val WEB_REMIX_CLIENT_ID = "67"

    private const val TAG = "BitChord"

    /** Session cookie captured by the login WebView; null = browse as guest. */
    var cookie: String? = null
        set(value) {
            if (field != value) {
                // Both belong to the session that just left. A scope kept across
                // a sign-in would credit the new account's plays to the old one,
                // and a visitor id minted under the old session is not bound to
                // the new one — see [SessionScope] and [visitorData].
                scope = null
                visitorData = null
                visitorDataIsSessionBound = false
                // The chosen channel belonged to the account that just left.
                // A `dataSyncId` from one login sent under another's cookie is
                // answered with 401 on every request, so it goes with it.
                channelOverride = null
            }
            field = value
            MusicGateFallback.onSessionChanged()
        }

    /**
     * Google's per-session visitor id.
     *
     * Far more load-bearing than "an id for stats". A `player` request that
     * carries no visitor id is treated as a client with no session at all, and
     * Google answers it in one of two ways: the honest one, `LOGIN_REQUIRED` /
     * "Sign in to confirm you're not a bot", or the quiet one — a perfectly
     * ordinary-looking response whose stream URLs serve a byte to anything that
     * asks and then refuse every real read with 403. The second is what
     * "it loads and then doesn't play" is made of.
     *
     * So it is fetched deliberately by [ensureVisitorData] rather than being
     * hoped for: browse responses carry one only sometimes, and a session that
     * never happened to see one would silently never play anything.
     */
    @Volatile
    private var visitorData: String? = null

    /**
     * Whether [visitorData] came from the signed-in shell rather than being
     * minted anonymously.
     *
     * A session-bound id outranks an anonymous one and must not be replaced by
     * it. Both are fetched near startup and nothing orders them, so without this
     * the better id was lost to whichever request happened to finish second.
     */
    @Volatile
    private var visitorDataIsSessionBound = false

    /**
     * A visitor id that belongs to no account, for [isAnonymousRequest] calls.
     *
     * [visitorData] is deliberately the signed-in shell's id once a login
     * happens, which is exactly what must not travel with a search: Google can
     * tie that id to the account, so an otherwise credential-free search would
     * still be attributable to the listener. This one is minted from the
     * unauthenticated bootstrap in [fetchVisitorData] and never replaced by a
     * session-bound value.
     */
    @Volatile
    private var anonymousVisitorData: String? = null

    private val anonymousVisitorLock = Mutex()

    /**
     * Where a visitor id is kept between process starts.
     *
     * Minting one is a network round trip to the web player's service worker,
     * and the value is bound to the cookie that asked for it rather than to a
     * single request. Losing it when Android reclaims a backgrounded process
     * made the next launch pay that round trip again before it could resolve
     * anything.
     */
    interface VisitorStore {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
    }

    @Volatile
    private var visitorStore: VisitorStore? = null

    @Volatile
    private var persistedVisitorKey: String? = null

    @Volatile
    private var persistedVisitorValue: String? = null

    /** Installs (or clears) the between-process home for [visitorData]. */
    fun initVisitorStore(store: VisitorStore?) {
        visitorStore = store
        persistedVisitorKey = null
        persistedVisitorValue = null
    }

    /**
     * A visitor id for this session, minting one if there isn't one yet.
     *
     * @param refresh discard the current id and take a fresh one — worth doing
     *   exactly once when a request comes back accusing us of being a bot,
     *   since an id can be burned while the session around it is fine.
     */
    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) {
            persistVisitor()
            return visitorData
        }
        if (!refresh) {
            restorePersistedVisitor()
            if (visitorData != null) return visitorData
        }
        runCatching { fetchVisitorData() }
            .onFailure { Log.w(TAG, "could not mint a visitor id: ${it.message}") }
            .getOrNull()
            ?.let {
                if (refresh || !visitorDataIsSessionBound) {
                    visitorData = it
                    visitorDataIsSessionBound = false
                }
            }
        persistVisitor()
        return visitorData
    }

    /**
     * Reads back the id left by the previous process when it was minted under
     * this exact cookie. A visitor id (and the PoTokens derived from it) is
     * bound to the cookie that asked for it, so a rotated cookie for the same
     * account gets a different slot and cannot inherit the old id.
     */
    private fun restorePersistedVisitor() {
        val store = visitorStore ?: return
        val key = visitorPersistenceKey(cookie)
        val value = store.getString(key)?.takeIf { VISITOR_DATA.matches(it) } ?: return
        visitorData = value
        visitorDataIsSessionBound = store.getString(visitorBoundStorageKey(key)) == "1"
        persistedVisitorKey = key
        persistedVisitorValue = value
    }

    /** Writes the id only when the cookie or the value actually changed. */
    private fun persistVisitor() {
        val store = visitorStore ?: return
        val value = visitorData ?: return
        val key = visitorPersistenceKey(cookie)
        if (key == persistedVisitorKey && value == persistedVisitorValue) return
        store.putString(key, value)
        store.putString(visitorBoundStorageKey(key), if (visitorDataIsSessionBound) "1" else "0")
        persistedVisitorKey = key
        persistedVisitorValue = value
    }

    /**
     * A visitor id minted without the session, for requests that must not be
     * attributable to the listener.
     *
     * Kept apart from [visitorData] rather than reset when a login happens: an
     * anonymous request must never borrow the signed-in shell's id, and the
     * anonymous id stays anonymous no matter how many sign-ins follow.
     */
    private suspend fun ensureAnonymousVisitorData(): String? {
        anonymousVisitorData?.let { return it }
        return anonymousVisitorLock.withLock {
            anonymousVisitorData?.let { return@withLock it }
            runCatching { fetchVisitorData() }
                .onFailure { Log.w(TAG, "could not mint an anonymous visitor id: ${it.message}") }
                .getOrNull()
                ?.also { anonymousVisitorData = it }
        }
    }

    /**
     * The service worker bootstrap the web player loads before anything else,
     * which is where a fresh visitor id comes from without needing a page.
     * It answers with an anti-hijacking prefix and then plain nested arrays,
     * so the id is found by shape rather than by a path that would rot.
     */
    private suspend fun fetchVisitorData(): String? {
        val body = client.get("https://www.youtube.com/sw.js_data") {
            header("User-Agent", WEB_USER_AGENT)
        }.bodyAsText()
        val payload = Json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
        return findVisitorData(payload)
    }

    private fun findVisitorData(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findVisitorData(it) }
        is JsonPrimitive -> element.contentOrNull?.takeIf { VISITOR_DATA.matches(it) }
        else -> null
    }

    /** Protobuf-in-base64; always this shape, and nothing else in there is. */
    private val VISITOR_DATA = Regex("""Cg[A-Za-z0-9_%-]{40,}""")

    // ---- Which account is this, exactly -------------------------------------

    /**
     * Who the session cookie actually acts as, and which client version it acts
     * with — read out of the signed-in music.youtube.com shell.
     *
     * A cookie is not an account. One Google login carries every account the
     * browser has ever signed into, plus every brand channel hanging off them,
     * and *nothing in the cookie says which one is meant*. The web client
     * resolves that from its page config and then says so on every request. An
     * app that skips this step is not making an ambiguous request — it is
     * making a request about the first account in the jar, whoever that is.
     *
     * That is the whole of "history works for me and not for them": for a
     * listener whose YouTube Music account *is* the first one, guessing is
     * indistinguishable from asking. For anyone with two Google accounts, or a
     * brand channel — the account YouTube Music itself pushes you onto when you
     * have one — every play was being credited to the wrong identity, so their
     * own history stayed empty no matter how many pings went out successfully.
     *
     * @param dataSyncId the account, as `context.user.onBehalfOfUser`. Only
     *   ever taken from a shell that reported itself signed in: Google answers
     *   an `onBehalfOfUser` it cannot tie to a session with 401, so a guessed
     *   value would break every request in the app rather than just history.
     * @param pageId the brand channel, as `X-Goog-PageId` — the header the
     *   stats endpoints ask for by name as `PLUS_PAGE_ID`. Absent for a plain
     *   personal account, which is why it is nullable rather than defaulted.
     * @param authUser which entry in the cookie jar, as `X-Goog-AuthUser`.
     *   Hardcoded `0` before this, which is the same guess by another name.
     */
    private class SessionScope(
        val dataSyncId: String?,
        val pageId: String?,
        val authUser: String,
        val clientVersion: String?,
    )

    /**
     * The identity in force, for callers that captured only a cookie.
     *
     * A login completed on a page with no `ytcfg` — YouTube's Premium paywall,
     * a mobile home page — has no page identity to adopt. The caller resolves
     * the desktop shell first with [ensureSessionScope], then reads back what
     * it found so the profile it saves names the right channel.
     */
    data class ResolvedSession(
        val pageId: String?,
        val dataSyncId: String?,
        val authUser: String,
        val visitorData: String?,
        val clientVersion: String?,
    )

    @Volatile
    private var scope: SessionScope? = null

    private val scopeLock = Mutex()

    /** A channel the listener picked, standing in for the shell's default. */
    class ChannelSelection(
        val pageId: String?,
        val dataSyncId: String?,
        /**
         * Which Google account in the cookie jar the channel belongs to. Null
         * leaves the shell's own answer alone — a brand channel sits under the
         * account that owns it, so this only differs when the listener switched
         * to a channel of a *different* signed-in Google account.
         */
        val authUser: String? = null,
    )

    /**
     * The channel to act as, when the listener has said which.
     *
     * [fetchSessionScope] can only report the one music.youtube.com serves by
     * default, and for an account whose music lives on a brand channel that is
     * the wrong one — the library, the likes and the history all belong to the
     * other identity. Once a channel has been chosen it outranks the shell
     * completely, including when what it chose is the account's own channel and
     * the shell is the one offering a brand page.
     */
    @Volatile
    private var channelOverride: ChannelSelection? = null

    /**
     * Act as this channel from now on; both null goes back to the shell's
     * default. Takes effect on the next request — nothing is cached from it.
     */
    fun selectChannel(pageId: String?, dataSyncId: String?, authUser: String? = null) {
        channelOverride = if (pageId == null && dataSyncId == null) {
            null
        } else {
            ChannelSelection(pageId, dataSyncId, authUser)
        }
        Log.d(
            TAG,
            "acting as channel pageId=${pageId ?: "none"} authUser=${authUser ?: "as-is"} " +
                "(override=${channelOverride != null})",
        )
        MusicGateFallback.onSessionChanged()
    }

    /**
     * Takes the session scope from a page the listener was actually looking at,
     * rather than working it out later from a fetch of our own.
     *
     * The shell fetch in [fetchSessionScope] can only ever report the channel
     * music.youtube.com serves this app by default, and the whole reason the
     * in-app browser exists is that the listener has just told it, by hand,
     * that they want a different one. That answer is written into the page's
     * own `ytcfg`, so it is read from there and adopted whole.
     *
     * Adopting also settles [ensureSessionScope] — a scope already in hand is
     * not refetched — so the shell cannot quietly overwrite the choice with its
     * default on the next request.
     *
     * A page that reported itself signed out is ignored apart from its client
     * version: its `DATASYNC_ID` belongs to no account, and sending one Google
     * cannot tie to the session is answered with 401 on every request.
     */
    fun adoptSessionScope(
        pageId: String?,
        dataSyncId: String?,
        authUser: String?,
        visitorData: String?,
        clientVersion: String?,
        loggedIn: Boolean,
    ) {
        val version = clientVersion ?: scope?.clientVersion
        if (!loggedIn) {
            Log.w(TAG, "captured page was signed out; not scoping requests to it")
            scope = version?.let { SessionScope(null, null, "0", it) }
            return
        }
        scope = SessionScope(
            dataSyncId = dataSyncId?.takeIf { it.isNotBlank() },
            pageId = pageId?.takeIf { it.isNotBlank() },
            authUser = authUser?.takeIf { it.isNotBlank() } ?: "0",
            clientVersion = version,
        )
        // The page's own visitor id, bound to this session — strictly better
        // than the anonymous one [fetchVisitorData] mints. See [visitorData].
        visitorData?.takeIf { it.isNotBlank() }?.let {
            this.visitorData = it
            visitorDataIsSessionBound = true
        }
        Log.d(TAG, "adopted page scope: pageId=${pageId ?: "none"} authUser=${authUser ?: "0"}")
        MusicGateFallback.onSessionChanged()
    }

    /**
     * The identity a per-session answer belongs to — the music-gate verdict,
     * and anything else cached across a switch (Auto's Recents, Quick Picks,
     * Home).
     *
     * A channel override wins because the same Google login can act as several
     * listeners, and one of them being gated says nothing about another. Before
     * the shell has answered, the cookie is hashed so the entry still cannot
     * leak across accounts; an anonymous session has one shared entry.
     */
    fun sessionKey(): String {
        val chosen = channelOverride
        val id = chosen?.dataSyncId?.takeIf { it.isNotBlank() }
            ?: chosen?.pageId?.takeIf { it.isNotBlank() }
            ?: scope?.dataSyncId?.takeIf { it.isNotBlank() }
            ?: scope?.pageId?.takeIf { it.isNotBlank() }
        if (id != null) return "u:$id"
        val current = cookie ?: return "anonymous"
        return visitorCookieKey(current)
    }

    /** The brand channel to send, chosen one first. */
    private fun pageIdFor(session: SessionScope?): String? =
        (channelOverride ?: return session?.pageId).pageId

    /**
     * The account to send as `onBehalfOfUser`, chosen one first.
     *
     * No falling back to the shell's value once a channel has been chosen: the
     * shell's id names the default identity, and pairing it with another
     * channel's [pageId] describes an account/page combination that doesn't
     * exist.
     */
    private fun dataSyncIdFor(session: SessionScope?): String? =
        (channelOverride ?: return session?.dataSyncId).dataSyncId

    /** Which account in the cookie jar, chosen channel's first. */
    private fun authUserFor(session: SessionScope?): String =
        channelOverride?.authUser ?: session?.authUser ?: "0"

    /**
     * The WEB_REMIX version to claim, live if the shell has been read.
     *
     * Worth taking from the shell rather than pinning: the stats pings carry it
     * as `cver`, and a version Google has never shipped is a standing invitation
     * to be treated as something other than a music client.
     */
    private val webRemixVersion: String
        get() = scope?.clientVersion ?: WEB_REMIX_VERSION

    /**
     * Reads the session scope, once per cookie, before anything that depends on
     * being the right account.
     *
     * Cheap to be wrong about and expensive to skip, so it fails open: a shell
     * that cannot be fetched or parsed leaves [scope] null and every request
     * behaves exactly as it did before. What it must never do is invent a
     * [SessionScope.dataSyncId] — see that field.
     */
    suspend fun ensureSessionScope() {
        val session = cookie ?: return
        if (scope != null) return
        scopeLock.withLock {
            if (scope != null || cookie != session) return
            runCatching { fetchSessionScope(session) }
                .onFailure { Log.w(TAG, "could not read the session scope: ${it.message}") }
                .getOrNull()
                ?.let { fresh ->
                    // A login/profile switch can happen while the shell is in
                    // flight. Never install the old cookie's answer under the
                    // new one: that is a guaranteed 401 and, worse, can credit
                    // a play to the profile that just left.
                    if (cookie != session) {
                        Log.d(TAG, "discarding a session scope from an account that is no longer active")
                        return@let
                    }
                    scope = fresh
                    channelOverride?.let { selected ->
                        if (selected.pageId != fresh.pageId || selected.dataSyncId != fresh.dataSyncId) {
                            Log.w(TAG, "server shell identity differs from selected profile; retaining override")
                        }
                    }
                    Log.d(
                        TAG,
                        "session scope: authUser=${fresh.authUser} " +
                            "pageId=${fresh.pageId ?: "none"} " +
                            "dataSyncId=${if (fresh.dataSyncId != null) "present" else "none"} " +
                            "cver=${fresh.clientVersion ?: WEB_REMIX_VERSION}",
                    )
                }
        }
    }

    /** Re-read request context once after an authenticated rejection. */
    suspend fun refreshSessionScope() {
        if (cookie == null) return
        scopeLock.withLock { scope = null }
        ensureSessionScope()
    }

    /**
     * The identity currently resolved from the cookie, or null if none is yet.
     *
     * See [ResolvedSession]. The returned visitor id is the session-bound one
     * when the shell supplied it.
     */
    fun currentSession(): ResolvedSession? = scope?.let {
        ResolvedSession(
            pageId = it.pageId,
            dataSyncId = it.dataSyncId,
            authUser = it.authUser,
            visitorData = visitorData,
            clientVersion = it.clientVersion,
        )
    }

    /**
     * The music.youtube.com shell, fetched with the session, for its `ytcfg`.
     *
     * Read by regex rather than by evaluating the config blob: it is one script
     * assignment among hundreds of kilobytes of app JavaScript, and the four
     * values wanted are flat strings in it. A key that moves reads as absent,
     * which is the same as not having asked.
     */
    private suspend fun fetchSessionScope(session: String): SessionScope? {
        val html = client.get("$MUSIC_ORIGIN/") {
            header("User-Agent", WEB_USER_AGENT)
            header("Accept-Language", acceptLanguageHeader)
            header("Cookie", session)
            authorizationFor(session, MUSIC_ORIGIN)?.let { header("Authorization", it) }
        }.bodyAsText()

        // The one value that must not be guessed. A shell that says it is
        // signed out either has a dead cookie or was served to nobody in
        // particular; either way its DATASYNC_ID belongs to no account, and
        // sending it would 401 every request in the app.
        val signedIn = CONFIG_LOGGED_IN.find(html)?.groupValues?.get(1) == "true"
        val clientVersion = CONFIG_CLIENT_VERSION.find(html)?.groupValues?.get(1)
        if (!signedIn) {
            Log.w(TAG, "music.youtube.com served a signed-out shell; not scoping requests")
            // Still worth the client version — that part is true either way.
            return clientVersion?.let { SessionScope(null, null, "0", it) }
        }

        val pageId = CONFIG_PAGE_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val dataSyncId = pageId ?: normalizeDataSyncId(
            CONFIG_DATASYNC_ID.find(html)?.groupValues?.get(1),
        )
        val authUser = CONFIG_SESSION_INDEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        // The shell's own visitor id, which is bound to this session. Strictly
        // better than the anonymous one [fetchVisitorData] mints: the stats
        // pings are attributed against the visitor the player response was
        // issued to, so a signed-in play reported under an anonymous id is a
        // play reported about nobody.
        CONFIG_VISITOR_DATA.find(html)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                visitorData = it
                visitorDataIsSessionBound = true
            }

        return SessionScope(dataSyncId, pageId, authUser ?: "0", clientVersion)
    }

    private val CONFIG_LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
    private val CONFIG_DATASYNC_ID = Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_PAGE_ID = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")
    private val CONFIG_VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
    private val CONFIG_CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    private fun newClient(okHttp: okhttp3.OkHttpClient): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(ContentNegotiation) { json(json) }
        // Without this the only bound is OkHttp's own read timeout, and the
        // failure it raises reads as "Socket timeout has expired […]
        // socket_timeout=unknown" — Ktor reporting a limit it was never told.
        // The connect bound is the proxy failover's: a dead proxy must not
        // cost more than four seconds before the request goes direct.
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 4_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = true
    }

    // Same OkHttp instance ExoPlayer streams through — see Http.
    private val client = newClient(Http.client)

    /**
     * The shared stack with the proxy route pinned for this one request.
     *
     * [postMusic] decides the route once, before sending. Without this the
     * selector could still answer [java.net.Proxy.NO_PROXY] because another
     * request recorded the proxy unreachable a moment later, and the response
     * would then be counted as a proxy success even though it went direct.
     */
    private val routedClient = newClient(
        Http.client.newBuilder().proxySelector(YouTubeProxy.forceProxySelector).build(),
    )

    /** The direct-only stack used for the single retry when the proxy dies. */
    private val directClient = newClient(Http.directClient)

    /**
     * Runs [block], giving transport failures another go before letting them
     * reach the caller.
     *
     * A connection reset on mobile data is weather, not information: the
     * request was fine and asking again generally answers. That matters most
     * on a shared connection pool, where a socket torn down under one request
     * — an abandoned search, a network handover — surfaces as
     * "Software caused connection abort" on whichever request picked that
     * connection up next, which had nothing to do with it.
     *
     * Only transport failures. An HTTP error status is an answer, and
     * repeating the question won't change it. Cancellation isn't caught at
     * all: [delay] throws when the coroutine is cancelled, so a search the
     * user has typed past stops here instead of retrying on behalf of a query
     * nobody is waiting for.
     */
    private suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> T): T {
        var backoff = 500L
        repeat(attempts - 1) {
            try {
                return block()
            } catch (e: HttpRequestTimeoutException) {
                // Not weather, and not worth repeating. A timeout is this app's
                // own decision that the request had long enough — so trying it
                // again cannot learn anything the first attempt didn't, and the
                // cost is multiplied rather than shared: [HttpRequestTimeoutException]
                // is an [IOException], so before this branch existed every timed-out
                // `player` call was quietly attempted three times. That turned a
                // six-second ceiling into a nineteen-second one on a walk of
                // seven clients, which is worse than the unbounded call the
                // ceiling was added to prevent. Give up on this client and let
                // the caller move to the next one.
                Log.d(TAG, "not retrying, request timed out: ${e.message}")
                throw e
            } catch (e: IOException) {
                Log.d(TAG, "retrying: ${e.message}")
            }
            delay(backoff)
            backoff *= 2
        }
        return block()
    }

    // ---- Public API ---------------------------------------------------------

    suspend fun browse(
        browseId: String,
        params: String? = null,
        forceSigned: Boolean = false,
    ): JsonObject {
        // An anonymous page says nothing about the account: moods/genres,
        // charts and Explore are sent without the cookie even while signed in.
        // Its response (and its refusal) must therefore not become the
        // signed-in identity's verdict. A gate re-check passes [forceSigned]
        // so the answer it is meant to learn from is asked as the account.
        val anonymous = musicRequestIsAnonymous("browse", browseId, forceSigned = forceSigned)
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

    /**
     * A music.youtube.com page read without the listener's account.
     *
     * The Korea/no-Premium album pages are the one catalogue source that still
     * answers anonymously, and [com.music.bitchord.data.CatalogSongMapper]
     * needs their track lists to replace video uploads with real songs. The
     * request carries no cookie and no account visitor id, whatever the gate
     * verdict happens to be. Its answer is deliberately not fed to
     * [MusicGateFallback.observeBrowse]: an anonymous response says nothing
     * about whether the signed-in identity is gated.
     */
    suspend fun browseAnonymous(browseId: String, params: String? = null): JsonObject =
        postMusic("browse", forceAnonymous = true) {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    /**
     * The same anonymous browse without the proxy failover, so the Settings
     * connection test observes the proxy route itself rather than a direct
     * retry behind it.
     */
    internal suspend fun browseAnonymousThroughProxyOnly(browseId: String): JsonObject =
        postMusic("browse", forceAnonymous = true, directRetryOnProxyFailure = false) {
            put("browseId", browseId)
        }

    // ---- youtube.com WEB fallback -------------------------------------------
    //
    // Same endpoints, same cookie, different origin and client. Used only when
    // [MusicGateFallback] says music.youtube.com is gated, or to read a
    // playback-tracking block the gated Music player will not issue.

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

    /**
     * A YouTube Music page read without the listener's account.
     *
     * The signed browse of a `VLRDCLAK…` editorial playlist came back with no
     * rows, so the page fell through to youtube.com, whose rows carry no
     * `musicVideoType` — every music video then looked like a song and was
     * never switched to its song version (2026-10-23, a whole shuffled
     * playlist played as MVs). Asked anonymously through the proxy, Music's
     * own browse answers the full list with the type on every row (measured
     * the same day), so this is tried before youtube.com.
     */
    suspend fun browseMusicAnonymous(browseId: String): JsonObject =
        postMusic("browse", browseId = browseId, forceAnonymous = true) {
            put("browseId", browseId)
        }

    /**
     * A public youtube.com page read without the listener's account.
     *
     * Used for YouTube Music's editorial `RDCLAK` playlists: their Music page
     * answers no rows anonymously, while youtube.com's WEB browse of the same
     * `VLRDCLAK…` id does. No cookie, no session visitor id, no account.
     */
    suspend fun browseYouTubeAnonymous(
        browseId: String,
        params: String? = null,
    ): JsonObject = postYouTube("browse", anonymous = true) {
        put("browseId", browseId)
        params?.let { put("params", it) }
    }

    /** The next page of an anonymous youtube.com public browse. */
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
     *
     * The fallback history is a youtube.com feed, so it also contains ordinary
     * uploads; the one cheap discriminator YouTube publishes for every video is
     * `microformat.playerMicroformatRenderer.category` ("Music" for music
     * videos, "Film & Animation" for the rest). Measured from Korea: the WEB
     * player answers this metadata even when playability itself is refused, so
     * no cookie and no visitor id are needed — and deliberately not sent,
     * because classifying a history row must not attach the listener to a
     * request they did not make.
     *
     * A short timeout is part of the contract: this is one background lookup
     * per unknown history row, never something a page waits on for long.
     */
    suspend fun playerCategory(videoId: String): String? {
        if (videoId.isBlank()) return null
        val response = client.post("$YOUTUBE_BASE/player") {
            contentType(ContentType.Application.Json)
            parameter("prettyPrint", "false")
            parameter("key", WEB_API_KEY)
            parameter("hl", "en")
            header("Accept-Language", acceptLanguageHeader)
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
                            put("gl", currentCountry)
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

    /**
     * The `player` metadata for [videoId], read without the listener's account.
     *
     * Song Info needs the views and the description's release facts. It asks
     * youtube.com's own player rather than scraping a watch page: the page is
     * what a Korean request without a visitor id gets refused, while this
     * endpoint is the same one the app's fallback already uses.
     */
    suspend fun playerResponse(videoId: String): JsonObject =
        postYouTube("player", anonymous = true) {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

    /**
     * The watch page's `next` data for [videoId], read without the account.
     * This is where the like button and comment count live.
     */
    suspend fun watchNext(videoId: String): JsonObject =
        postYouTube("next", anonymous = true) {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

    /**
     * The watch queue for [videoId], or for a standalone mix when the id is
     * null. A `RD` + seed id is YouTube's own Mix, personalised by the
     * signed-in account's cookies.
     */
    suspend fun nextYouTube(videoId: String?, playlistId: String): JsonObject =
        postYouTube("next") {
            videoId?.let { put("videoId", it) }
            put("playlistId", playlistId)
        }

    /** A thumbs up/down sent to youtube.com; the same endpoints Music shares. */
    suspend fun rateYouTube(videoId: String, status: LikeStatus) {
        requireSession()
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

    /**
     * YouTube Music's charts page for [country] — a code from the page's own
     * country list, or null for the request's content country.
     */
    suspend fun charts(country: String?): JsonObject =
        postMusic("browse", browseId = CHARTS_BROWSE_ID) {
            put("browseId", CHARTS_BROWSE_ID)
            if (!country.isNullOrBlank()) {
                putJsonObject("formData") { putJsonArray("selectedValues") { add(country) } }
            }
        }

    const val CHARTS_BROWSE_ID = "FEmusic_charts"

    /**
     * The next page of a paged browse response — playlists and library feeds
     * come back roughly 100 rows at a time. YouTube Music takes the token as
     * query parameters rather than in the body, and answers with a bare
     * continuation envelope carrying the same row renderers.
     */
    suspend fun browseContinuation(token: String): JsonObject = postMusic(
        endpoint = "browse",
        // The web client passes the token in the body and the older query-string
        // form is still honoured; both are sent so either is enough.
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }

    /** The next page of [browseMusicAnonymous]: a token minted signed out is followed signed out. */
    suspend fun browseContinuationMusicAnonymous(token: String): JsonObject = postMusic(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        forceAnonymous = true,
    ) {
        put("continuation", token)
    }

    /** Signed-in profile: display name, email/handle and avatar. */
    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu") {}

    /**
     * youtube.com's own avatar menu for the same login. Music answers a gated
     * Korean session's `account_menu` with 404 "Requested entity was not
     * found", which used to throw away a sign-in whose cookies were fine.
     */
    suspend fun accountMenuYouTube(): JsonObject = postYouTube("account/account_menu") {}

    /**
     * Every channel this session can act as, as Innertube's account switcher
     * lists them: the account's own channel first, then its brand channels.
     *
     * Each entry carries the two things a request needs to be made *as* that
     * channel — a `pageIdToken` and a `datasyncIdToken` — which is the whole
     * reason to ask rather than to reason about it. See [selectChannel].
     */
    suspend fun accountsList(): JsonObject = postMusic("account/accounts_list") {}

    /**
     * The same list from youtube.com's own switcher, as a second route.
     *
     * Worth having both. `accounts_list` is the tidier call but it is not
     * uniformly answered for every client identity, and a listener whose music
     * is on a brand channel is stuck with the wrong library until *something*
     * enumerates their channels. This endpoint is what the youtube.com avatar
     * menu itself calls, and it answers a plain signed GET.
     *
     * The body is JSON behind Google's XSSI guard — a `)]}'` line that exists
     * to make the response invalid JavaScript — so it is trimmed before parsing
     * rather than being handed to the JSON reader as-is.
     */
    suspend fun accountSwitcher(): JsonObject {
        requireSession()
        ensureSessionScope()
        val session = scope
        val text = withRetry {
            client.get("$YOUTUBE_ORIGIN/getAccountSwitcherEndpoint") {
                header("User-Agent", WEB_USER_AGENT)
                header("Accept-Language", acceptLanguageHeader)
                header("X-Origin", YOUTUBE_ORIGIN)
                header("Referer", "$YOUTUBE_ORIGIN/")
                cookie?.let { c ->
                    header("Cookie", c)
                    header("X-Goog-AuthUser", authUserFor(session))
                    authorizationFor(c, YOUTUBE_ORIGIN)?.let { header("Authorization", it) }
                }
            }.bodyAsText()
        }
        val body = text.substringAfter(")]}'", text).trim()
        return json.parseToJsonElement(body).jsonObject
    }

    /**
     * The watch queue that YouTube Music would play after [videoId] — the
     * "RDAMVM" radio mix. Used to keep AutoPlay going past the last track.
     */
    suspend fun next(videoId: String): JsonObject = postMusic("next") {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
    }

    /**
     * A watch queue started from a playlist rather than a track: a mix such as
     * My Supermix, a playlist radio (`RDAMPL…`), or an artist's Mix / Shuffle
     * button, which also carry their own seed [videoId] and [params].
     */
    suspend fun nextPlaylist(playlistId: String, videoId: String? = null, params: String? = null): JsonObject =
        postMusic("next") {
            videoId?.let { put("videoId", it) }
            put("playlistId", playlistId)
            params?.let { put("params", it) }
            put("isAudioOnly", true)
        }

    /** Timed caption transcript used as a last-resort lyrics source. */
    suspend fun transcript(videoId: String): JsonObject = postMusic("get_transcript") {
        // get_transcript expects a tiny protobuf: field 1, length, video id.
        val bytes = byteArrayOf(10, videoId.toByteArray().size.toByte()) + videoId.toByteArray()
        put("params", Base64.getEncoder().encodeToString(bytes))
    }

    suspend fun search(
        query: String,
        params: String? = null,
        anonymous: Boolean = true,
        kind: SearchKind = SearchKind.TYPED,
    ): JsonObject =
        postMusic("search", searchKind = kind, searchQueryLength = query.length) {
            put("query", query)
            params?.let { put("params", it) }
        }

    /** The next page of a filtered search result; anonymous by the same rule. */
    suspend fun searchContinuation(
        token: String,
        kind: SearchKind = SearchKind.TYPED,
    ): JsonObject = postMusic(
        endpoint = "search",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        searchKind = kind,
    ) {
        put("continuation", token)
    }

    /**
     * YouTube Music's featured-playlist search filter (measured Korean IP,
     * 2026-10-19). It answers YouTube-Music-authored `RDCLAK` collections,
     * each with square editorial artwork.
     */
    const val FEATURED_PLAYLISTS_PARAMS = "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ="

    /** The same search filtered to community playlists. */
    const val COMMUNITY_PLAYLISTS_PARAMS = "EgeKAQQoAEABagwQDhAKEAMQBRAJEAQ="

    suspend fun searchFeaturedPlaylists(query: String): JsonObject =
        search(query, params = FEATURED_PLAYLISTS_PARAMS, kind = SearchKind.CATALOG)

    suspend fun searchCommunityPlaylists(query: String): JsonObject =
        search(query, params = COMMUNITY_PLAYLISTS_PARAMS, kind = SearchKind.CATALOG)

    /**
     * The typeahead list YouTube Music's own search box shows for a
     * half-typed query — query strings, not results.
     *
     * A different endpoint from [search] rather than a cheap mode of it, and
     * far cheaper than one: the response is a few hundred bytes of text with
     * no shelves, thumbnails or playback endpoints in it, which is what makes
     * it affordable per keystroke where a search is not.
     *
     * Account-free like [search] — every half-typed query would otherwise be
     * recorded against the listener as one they searched for.
     */
    suspend fun searchSuggestions(input: String): JsonObject =
        postMusic(
            "music/get_search_suggestions",
            searchKind = SearchKind.SUGGEST,
            searchQueryLength = input.length,
        ) {
            put("input", input)
        }

    /**
     * Live media results for the typeahead phase — same shape as [search] but
     * unauthenticated, so YouTube Music does not log each debounced keystroke
     * to the account's server-side search history.
     */
    suspend fun searchTypeahead(query: String): JsonObject =
        postMusic(
            "search",
            searchKind = SearchKind.TYPEAHEAD,
            searchQueryLength = query.length,
        ) {
            put("query", query)
        }

    /**
     * A filtered search for lookups the app makes on its own behalf — finding
     * the original-language name of a song whose lyrics could not be found,
     * the original behind a cover — answered the same anonymous way as
     * [search], never with the listener's account.
     *
     * Answers in English for the US catalogue unless the caller says
     * otherwise, so names come back as the release prints them rather than
     * localised.
     */
    suspend fun searchAnonymous(
        query: String,
        params: String? = null,
        /** The catalogue to answer from; "ko"/"KR" lists Korean releases under their Korean names. */
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


    /** The stats endpoints a player response nominates for one playback. */
    data class PlaybackTracking(
        val playbackUrl: String,
        val watchtimeUrl: String?,
        /** The ad-tracking ping real clients fire a few seconds in. */
        val atrUrl: String?,
        /** How far in [atrUrl] is due, per the response's own schedule. */
        val atrAfterSeconds: Long,
        /** The origin the player response came from; the pings have to match it. */
        val origin: String = MUSIC_ORIGIN,
        /** The client the tracking URLs belong to (`WEB_REMIX`, `IOS`, …). */
        val clientName: String = "WEB_REMIX",
        val clientVersion: String = WEB_REMIX_VERSION,
    )

    /**
     * Player response fetched *with* the session cookie, purely to read back
     * `playbackTracking` — [player] deliberately skips auth so its device
     * clients are answered at all, so it never sees this block. Null for
     * guests: there's no account history to update.
     *
     * [signatureTimestamp] is not optional in practice, and that is the bug
     * this whole file was reported for.
     *
     * WEB_REMIX is a browser identity, and a browser proves it is running
     * YouTube's current player by quoting that player's timestamp. Without one
     * — or with a stale one — Google does not refuse the request in any way a
     * caller would notice: it answers HTTP 200, `playabilityStatus` `UNPLAYABLE`,
     * reason "Video unavailable", subreason "The page needs to be reloaded",
     * and simply omits `playbackTracking` entirely. So every play registration
     * this app made returned null here, logged one line, and stopped. No ping
     * was ever sent; no history was ever written. Nothing failed loudly enough
     * to notice, which is why it read as working.
     *
     * It is the *only* gate. Verified against the live endpoint: with a current
     * timestamp and nothing else — no visitor id, no referer, no
     * `html5Preference` — the block comes back. With every one of those and a
     * timestamp one revision old, it does not.
     */
    suspend fun playbackTracking(videoId: String, signatureTimestamp: Int?): PlaybackTracking? {
        if (cookie == null) return null
        ensureSessionScope()
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
        return PlaybackTracking(
            playbackUrl = playbackUrl,
            watchtimeUrl = tracking.trackingUrl("videostatsWatchtimeUrl"),
            atrUrl = tracking.trackingUrl("atrUrl"),
            atrAfterSeconds = tracking["atrUrl"]?.jsonObject
                ?.get("elapsedMediaTimeSeconds")?.jsonPrimitive?.contentOrNull
                ?.toLongOrNull() ?: DEFAULT_ATR_SECONDS,
            origin = MUSIC_ORIGIN,
            clientName = "WEB_REMIX",
            clientVersion = webRemixVersion,
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
    ): PlaybackTracking? {
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
            return PlaybackTracking(
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

    /** What YouTube Music itself schedules `atr` for, when it doesn't say. */
    private const val DEFAULT_ATR_SECONDS = 5L

    private fun JsonObject.trackingUrl(key: String): String? =
        this[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content

    /**
     * The "playback started" ping real YouTube Music clients send once a track
     * becomes audible. This is what creates the history entry the home feed
     * feeds off. [cpn] is the client-playback-nonce identifying this one play:
     * it must be the same value used for every [pingWatchtime] that follows.
     *
     * No `el` here. The base URL already carries `el=detailpage` — Google puts
     * it there — and a repeated query parameter is not a stronger statement of
     * the same thing, it is an ambiguous request whose resolution is Google's
     * to decide.
     */
    suspend fun pingPlayback(
        baseUrl: String,
        cpn: String,
        origin: String = MUSIC_ORIGIN,
        clientName: String = "WEB_REMIX",
        clientVersion: String = WEB_REMIX_VERSION,
    ) = pingStats(baseUrl, cpn, origin, clientName, clientVersion) {}

    /**
     * The follow-up ping reporting how much of the track was actually heard.
     * A history entry with no watchtime behind it reads as a skip, so it
     * carries little weight in recommendations — [seconds] is what makes the
     * play count. `st`/`et` are the watched segment's bounds, in seconds.
     *
     * @param final whether this is the last report for the play, which is what
     *   lets Google close the play out rather than leave it looking abandoned.
     */
    suspend fun pingWatchtime(
        baseUrl: String,
        cpn: String,
        seconds: Long,
        final: Boolean = false,
        origin: String = MUSIC_ORIGIN,
        clientName: String = "WEB_REMIX",
        clientVersion: String = WEB_REMIX_VERSION,
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

    /**
     * The `atr` ping, fired a few seconds into a play.
     *
     * Not analytics garnish. It is the third leg of the sequence a real client
     * performs — playback, atr, watchtime — and the one that distinguishes a
     * play that started from a play that happened. Its base URL already carries
     * `ver`, `c` and `cver`, so unlike the others it is sent as-is.
     */
    suspend fun pingAtr(
        baseUrl: String,
        cpn: String,
        origin: String = MUSIC_ORIGIN,
        clientName: String = "WEB_REMIX",
    ): Int {
        // Playback can outlive the Activity that restored the session. Ensure
        // the selected profile's headers exist before this first history ping.
        if (cookie != null) ensureSessionScope()
        val session = scope
        return client.get(baseUrl) {
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
        if (cookie != null) ensureSessionScope()
        val session = scope
        val ios = clientName == "IOS"
        return client.get(baseUrl) {
            parameter("ver", "2")
            parameter("c", clientName)
            parameter("cver", if (clientVersion.isBlank()) webRemixVersion else clientVersion)
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
            parameter("hl", currentLanguage.replace('-', '_'))
            parameter("cr", currentCountry)
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
        session: SessionScope?,
        origin: String,
        userAgent: String,
    ) {
        header("X-Origin", origin)
        header("Origin", origin)
        header("Referer", "$origin/")
        header("User-Agent", userAgent)
        visitorData?.let { header("X-Goog-Visitor-Id", it) }
        cookie?.let { c ->
            header("Cookie", c)
            header("X-Goog-AuthUser", authUserFor(session))
            pageIdFor(session)?.let { header("X-Goog-PageId", it) }
            authorizationFor(c, origin)?.let { header("Authorization", it) }
        }
    }

    // ---- Writes -------------------------------------------------------------
    //
    // Everything below changes something on the account, so all of it needs
    // the session cookie [postMusic] already signs with. None of it needs a
    // new credential or a different client — the same WEB_REMIX identity that
    // reads the library is the one allowed to edit it.

    /** A write attempted without a session; the caller has a sign-in prompt to show. */
    class NotSignedInException : IllegalStateException("이 작업을 하려면 YouTube Music에 로그인하십시오")

    private fun requireSession() {
        if (cookie == null) throw NotSignedInException()
    }

    /**
     * Thumbs up / down / neither, for [videoId].
     *
     * The response is inspected rather than discarded. Innertube answers a
     * refused write with HTTP 200 and an `error` object in the body, so the
     * status line alone will happily report a rating that never happened.
     */
    suspend fun rate(videoId: String, status: LikeStatus) {
        requireSession()
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("videoId", videoId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the rating: ${message ?: error}")
        }
        // YouTube states what it did in the toast it would have shown. Worth
        // keeping: a rating it declines to act on still answers 200, and this
        // one line is the difference between "the call was made" and "the
        // call did something".
        Log.d(TAG, "$endpoint $videoId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Saves an album or playlist to the library, or takes it back out.
     *
     * The same endpoints [rate] uses, aimed at a playlist instead of a video:
     * YouTube has no separate "save" verb for a release — a saved album *is* a
     * liked one, which is why the Library tab's Albums and Playlists shelves and
     * the account's likes are the same list. [playlistId] is the id the page
     * itself named, not its browse id; see
     * [com.music.bitchord.data.model.LibraryState].
     *
     * No dislike half, unlike [rate]: nothing in YouTube Music reads a disliked
     * release, so the only two states worth expressing are saved and not.
     */
    suspend fun ratePlaylist(playlistId: String, saved: Boolean) {
        requireSession()
        val endpoint = if (saved) "like/like" else "like/removelike"
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("playlistId", playlistId) }
        }
        // As in [rate]: a refusal arrives as HTTP 200 with an error in the body.
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the change: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $playlistId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Subscribes to an artist's channel, or unsubscribes from it.
     *
     * Not one of the `like/…` endpoints: a subscription is a YouTube-wide
     * relationship rather than a Music one, and it is addressed by channel id —
     * the `UC…` the artist page is served under. See
     * [com.music.bitchord.data.model.SubscriptionState].
     *
     * As in [rate], the body is read rather than the status line: Innertube
     * answers a refused write with HTTP 200 and an `error` object.
     */
    suspend fun setSubscribed(channelId: String, subscribed: Boolean) {
        requireSession()
        val endpoint = if (subscribed) "subscription/subscribe" else "subscription/unsubscribe"
        val response = postMusic(endpoint) {
            putJsonArray("channelIds") { add(channelId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the change: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $channelId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Adds or removes a track from the library, using a token minted by
     * YouTube for exactly that transition — see [com.music.bitchord.data.model.SongMenu].
     * There is no video-id form of this call; the token *is* the request.
     */
    suspend fun sendFeedback(token: String) {
        requireSession()
        postMusic("feedback") {
            putJsonArray("feedbackTokens") { add(token) }
        }
    }

    /**
     * Creates a playlist and returns its id.
     *
     * [videoIds] seeds it in the same request, which is what "add to a new
     * playlist" is: one round trip rather than a create followed by an edit
     * that could half-succeed.
     */
    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        description: String? = null,
        videoIds: List<String> = emptyList(),
    ): String {
        requireSession()
        val response = postMusic("playlist/create") {
            put("title", title)
            put("description", description.orEmpty())
            put("privacyStatus", privacy.apiValue)
            if (videoIds.isNotEmpty()) {
                putJsonArray("videoIds") { videoIds.forEach { add(it) } }
            }
        }
        // Normally a bare top-level id; occasionally only inside the command
        // that would navigate the web client to the new page, so fall back to
        // finding it by name rather than by a path that would rot.
        return response["playlistId"]?.jsonPrimitive?.contentOrNull
            ?: findString(response, "playlistId")
            ?: error("playlist created but no id came back")
    }

    suspend fun deletePlaylist(playlistId: String) {
        requireSession()
        postMusic("playlist/delete") { put("playlistId", playlistId.removePrefix("VL")) }
    }

    /**
     * One or more edits to a playlist, applied together.
     *
     * The endpoint answers `STATUS_SUCCEEDED` rather than an HTTP error when
     * it refuses — a playlist the account merely saved rather than owns is
     * the usual reason — so the body is checked as well as the status line.
     */
    private suspend fun editPlaylist(
        playlistId: String,
        actions: JsonArrayBuilder.() -> Unit,
    ): JsonObject {
        requireSession()
        val response = postMusic("browse/edit_playlist") {
            // The edit endpoint takes the raw id; `VL` is the browse prefix.
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions", actions)
        }
        val status = response["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && status != "STATUS_SUCCEEDED") {
            error("YouTube Music refused the edit ($status)")
        }
        return response
    }

    /**
     * Adds tracks to a playlist, and reports the per-entry id each one landed
     * under — video id to set-video-id, for the tracks the response named.
     *
     * Worth reading rather than discarding, because it is the only chance to
     * learn it without re-fetching the whole playlist: a set-video-id is minted
     * by this call, and it is what a later removal has to be expressed in (see
     * [removeFromPlaylist]). A row added to a playlist already on screen is
     * otherwise one the user can see but not take back out until the page is
     * reopened.
     *
     * Absences are normal and not an error — the add still happened; only the
     * id for undoing it is unknown.
     */
    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Map<String, String> {
        val response = editPlaylist(playlistId) {
            videoIds.forEach { videoId ->
                addJsonObject {
                    put("action", "ACTION_ADD_VIDEO")
                    put("addedVideoId", videoId)
                }
            }
        }
        return (response["playlistEditResults"] as? JsonArray)
            .orEmpty()
            .mapNotNull { result ->
                val added = (result as? JsonObject)
                    ?.get("playlistEditVideoAddedResultData") as? JsonObject
                    ?: return@mapNotNull null
                val videoId = (added["videoId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val setVideoId = (added["setVideoId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                videoId to setVideoId
            }
            .toMap()
    }

    /**
     * Removes entries from a playlist. Keyed by set-video-id as well as video
     * id: the same track added twice is two entries, and only the pair says
     * which of them to drop.
     */
    suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) {
        editPlaylist(playlistId) {
            entries.forEach { (setVideoId, videoId) ->
                addJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("setVideoId", setVideoId)
                    put("removedVideoId", videoId)
                }
            }
        }
    }

    /**
     * Rearranges a playlist, one [PlaylistMove] after another in the order
     * given — see [com.music.bitchord.data.model.playlistMoves] for building
     * them. A move with no successor goes to the end.
     */
    suspend fun movePlaylistItems(playlistId: String, moves: List<PlaylistMove>) {
        if (moves.isEmpty()) return
        editPlaylist(playlistId) {
            moves.forEach { move ->
                addJsonObject {
                    put("action", "ACTION_MOVE_VIDEO_BEFORE")
                    put("setVideoId", move.setVideoId)
                    move.before?.let { put("movedSetVideoIdSuccessor", it) }
                }
            }
        }
    }

    suspend fun renamePlaylist(playlistId: String, title: String) {
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title)
            }
        }
    }

    /** A fresh client-playback-nonce, identifying one play of one track. */
    fun newCpn(): String = (1..16).map { CPN_ALPHABET.random() }.joinToString("")

    private const val CPN_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    // ---- Request plumbing ---------------------------------------------------

    /**
     * The one request path.
     *
     * Whether the listener's account travels with a request is decided here
     * from the endpoint alone, not by each caller: [isAnonymousRequest]
     * endpoints carry no Cookie, Authorization, X-Goog-AuthUser, X-Goog-PageId,
     * `onBehalfOfUser` or session-bound visitor id. Everything else (Home,
     * Library, likes, playlists, stats pings) keeps the session as before.
     */
    private suspend fun postMusic(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        hl: String = currentLanguage,
        gl: String = currentCountry,
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
        val anonymous = musicRequestIsAnonymous(
            endpoint = endpoint,
            browseId = browseId,
            forceAnonymous = forceAnonymous,
            forceSigned = forceSigned,
        )
        if (isSearchEndpoint(endpoint)) {
            // isAnonymousRequest already forces this; the check keeps a future
            // search endpoint from ever logging (or sending) account=yes.
            check(anonymous) { "search request must not carry the account" }
            Log.d(
                TAG,
                searchLogLine(
                    host = "music.youtube.com",
                    endpoint = endpoint,
                    kind = searchKind ?: defaultSearchKind(endpoint),
                    queryLength = searchQueryLength,
                ),
            )
        }
        // The common authenticated path: Home, Library, likes, playlists,
        // account menus and all their continuations pass through it. Waiting
        // here makes restoration process-wide rather than a special case
        // implemented by whichever screen happened to open. Anonymous search
        // never waits for — or looks at — the account's scope.
        if (!anonymous && cookie != null) ensureSessionScope()
        val session = if (anonymous) null else scope
        // [anonymous] is wider than the per-endpoint rule in [accountHeadersFor]
        // and [visitorIdFor] (a gated session reads every Music page signed
        // out), so it decides here: no cookie and the account-free visitor id.
        val requestCookie = if (anonymous) null else cookie
        val credentials = accountHeadersFor(
            endpoint = endpoint,
            anonymous = anonymous,
            cookie = requestCookie,
            authUser = authUserFor(session),
            pageId = pageIdFor(session),
            // Lazy so an anonymous request never even hashes a credential.
            authorization = { requestCookie?.let { c -> authorizationFor(c, MUSIC_ORIGIN) } },
        )
        val anonymousVisitor = if (anonymous) ensureAnonymousVisitorData() else null
        val visitor = if (anonymous) anonymousVisitor else visitorIdFor(endpoint, visitorData, anonymousVisitor)
        val clientVersion = webRemixVersion
        val musicUrl = "$MUSIC_BASE/$endpoint"
        var proxyActive = YouTubeProxy.matches(musicUrl) && !ProxyHealthMonitor.shouldBypassProxy()
        val response = withRetry {
            val attempt: suspend (HttpClient, Boolean) -> JsonObject = { http, throughProxy ->
                val answer = http.post(musicUrl) {
                    contentType(ContentType.Application.Json)
                    parameter("prettyPrint", "false")
                    parameter("hl", hl)
                    header("Accept-Language", acceptLanguageHeader)
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
                                        dataSyncIdForRequest(endpoint, dataSyncIdFor(session))
                                            ?.let { put("onBehalfOfUser", it) }
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
                attempt(if (proxyActive) routedClient else client, proxyActive)
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
                                return@withRetry attempt(routedClient, true)
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
                        attempt(directClient, false)
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
            if (anonymousVisitorData == null) anonymousVisitorData = responseVisitorData
        } else if (visitorData == null) {
            visitorData = responseVisitorData
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
        hl: String = currentLanguage,
        gl: String = currentCountry,
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
        if (!anonymousRequest && cookie != null) {
            runCatching { ensureSessionScope() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        val session = if (anonymousRequest) null else scope
        val credentials = if (anonymousRequest) {
            emptyMap<String, String>()
        } else {
            accountHeadersFor(
                endpoint = endpoint,
                anonymous = anonymousRequest,
                cookie = cookie,
                authUser = authUserFor(session),
                pageId = pageIdFor(session),
                authorization = { cookie?.let { c -> authorizationFor(c, YOUTUBE_ORIGIN) } },
            )
        }
        val visitor = if (anonymousRequest) {
            ensureAnonymousVisitorData()
        } else {
            visitorData ?: ensureVisitorData()
        }
        val clientId = if (ios) IOS_CLIENT_ID else WEB_CLIENT_ID
        val clientVersion = if (ios) IOS_CLIENT_VERSION else WEB_VERSION
        val userAgent = if (ios) IOS_USER_AGENT else WEB_USER_AGENT
        val response = withRetry {
            client.post("$YOUTUBE_BASE/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                parameter("key", WEB_API_KEY)
                parameter("hl", hl)
                header("Accept-Language", acceptLanguageHeader)
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
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                if (!anonymousRequest) {
                                    dataSyncIdFor(session)?.let { put("onBehalfOfUser", it) }
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
            if (anonymousVisitorData == null) {
                anonymousVisitorData = response["responseContext"]?.jsonObject
                    ?.get("visitorData")?.jsonPrimitive?.content
            }
        } else if (visitorData == null) {
            visitorData = response["responseContext"]?.jsonObject
                ?.get("visitorData")?.jsonPrimitive?.content
        }
        return response
    }

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
     * account, shared by [postMusic] and by [browse]'s choice to observe the
     * response. [forceSigned] exists for the gate re-check: it must ask as the
     * account even while the current verdict would send everything anonymous.
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
            browseId == CHARTS_BROWSE_ID ||
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


    /** First string value under [key] anywhere in [element], depth-first. */
    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> (element[key] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    /**
     * The `Authorization` header youtube.com's own pages send for [cookieHeader].
     *
     * One hash per signing cookie the jar holds: `SAPISIDHASH`, then
     * `SAPISID1PHASH` (`__Secure-1PAPISID`) and `SAPISID3PHASH`
     * (`__Secure-3PAPISID`). A current Google login can carry only the
     * `__Secure-` forms; signing those with a lone `SAPISIDHASH` is answered
     * 401 "missing required authentication credential" — what the S24 log
     * showed for every youtube.com request after a fresh sign-in.
     *
     * Public so the desktop module signs its own youtube.com page fetches with
     * the same scheme instead of keeping a second, weaker copy.
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

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun hashPart(label: String, secret: String, origin: String, timestamp: Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $secret $origin".toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "$label ${timestamp}_$digest"
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
