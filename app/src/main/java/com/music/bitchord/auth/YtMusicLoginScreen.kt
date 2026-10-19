package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.data.DebugLog as Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val MUSIC_ORIGIN = "https://music.youtube.com"
private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

private const val TAG = "BitChord"

/**
 * In-app Google sign-in for YouTube Music, and the way to change which channel
 * it listens as.
 *
 * [WebSessionMode.SIGN_IN] loads the standard Google web login with
 * `continue=` a desktop youtube.com URL, announced as desktop Chrome. The user
 * authenticates directly against accounts.google.com (2FA, passkeys etc. all
 * work — it's the real page). Completion is read from the cookie jar, not from
 * the page: Google replaces the mobile music site with a Premium paywall for
 * some accounts (Korea, non-Premium), and that page has no signed-in `ytcfg`
 * even though Google has already signed the account in. Waiting for
 * music.youtube.com to render the identity therefore never finished.
 *
 * The listener still confirms the profile explicitly. This deliberately leaves
 * a multiple-channel account enough time to choose its Personal or Brand
 * identity before anything is saved, and [captureFrom] falls back to the
 * desktop shell via [com.music.bitchord.data.innertube.Innertube] when the
 * page cannot report one. The browser's local Google cookies are cleared on
 * the way in without visiting Google's logout endpoint, so adding an account
 * cannot invalidate a previously saved session.
 *
 * [WebSessionMode.SWITCH_CHANNEL] keeps those cookies and opens YouTube Music
 * itself, so the listener can use the avatar menu's own Accounts list — the one
 * screen that authoritatively knows which channels exist and which is which.
 * Nothing is taken automatically there: the session is read when they say so,
 * by raising [captureRequest].
 *
 * Either way what is read is the page's own `ytcfg`, not a guess made later
 * from a server-side fetch. The credential itself never passes through app code.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YtMusicLoginScreen(
    mode: WebSessionMode,
    /** Cookie snapshot to install before opening an existing account's channel picker. */
    initialCookie: String? = null,
    onCaptured: (CapturedSession) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Raise to take the session from the page as it stands. Ignored at its
     * initial value, so arriving on the screen doesn't capture anything.
     */
    captureRequest: Int = 0,
    /** Told when a capture was asked for and there was no session to take. */
    onCaptureUnavailable: () -> Unit = {},
    /** True once a signed-in YouTube Music page is available for confirmation. */
    onPageReady: (Boolean) -> Unit = {},
    /** For a host that needs the WebView set up differently — the TV app, for its remote. */
    configureWebView: (WebView) -> Unit = {},
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnUnavailable by rememberUpdatedState(onCaptureUnavailable)
    val currentOnPageReady by rememberUpdatedState(onPageReady)

    LaunchedEffect(captureRequest) {
        if (captureRequest == 0) return@LaunchedEffect
        val view = webView
        if (view == null) currentOnUnavailable()
        else captureFrom(view, currentOnCaptured, currentOnUnavailable)
    }

    // m.youtube.com is a single-page app: after the Google redirects it changes
    // screens without another onPageFinished, so a jar that completes after the
    // last page load was never seen and "이 프로필 사용" stayed disabled (S24).
    // While this sign-in screen is open the jar is checked once a second — a
    // cookie lookup, for the few seconds the screen exists.
    LaunchedEffect(mode) {
        if (mode != WebSessionMode.SIGN_IN) return@LaunchedEffect
        var last: Boolean? = null
        while (true) {
            val ready = authCookieHeader() != null
            if (ready != last) {
                currentOnPageReady(ready)
                last = ready
            }
            kotlinx.coroutines.delay(1_000)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            if (mode == WebSessionMode.SIGN_IN) {
                BrowserSession.clearGoogleCookies()
            } else initialCookie?.let(BrowserSession::installGoogleCookies)
            WebView(context).apply {
                if (mode == WebSessionMode.SIGN_IN) {
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The WebView's own user agent, deliberately: posing as desktop
                // Chrome made Google refuse the sign-in outright ("로그인할 수
                // 없음 - 브라우저 또는 앱이 안전하지 않을 수 있습니다", seen on the
                // S24). The Premium paywall is avoided by where the login
                // continues to (youtube.com, not music.youtube.com) instead.
                configureWebView(this)

                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        // Single-page navigations land here, not in onPageFinished.
                        if (authCookieHeader() != null) currentOnPageReady(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // The cookie jar decides whether the login is done,
                        // not the page. A paywall, youtube.com's home page and
                        // m.youtube.com are all valid landings once the auth
                        // cookies are there; the old check accepted none of
                        // them because it waited for music.youtube.com itself.
                        val signedIn = authCookieHeader() != null
                        // A multi-channel login can still be waiting for the
                        // listener to choose an identity on a Music page, so
                        // reaching that origin remains enough to enable
                        // confirmation there as well.
                        currentOnPageReady(
                            signedIn ||
                                (mode == WebSessionMode.SWITCH_CHANNEL &&
                                    url?.startsWith(MUSIC_ORIGIN) == true),
                        )
                    }
                }

                webView = this
                loadUrl(
                    if (mode == WebSessionMode.SIGN_IN) YouTubeWebLogin.loginUrl()
                    else "$MUSIC_ORIGIN/",
                )
            }
        },
    )
}

/**
 * Takes the session from the WebView's cookie jar, with whatever identity the
 * page could add.
 *
 * The jar is the requirement: if it holds a signing secret the login is done,
 * even when the page in front of the listener is YouTube's Premium paywall or
 * a mobile home page with no `ytcfg`. In that case the capture carries the
 * cookie alone and the caller reads the identity from the desktop shell with
 * the app's own client. [onUnavailable] means the jar is still mid-login and
 * has no request-signing cookie yet; the screen should stay open.
 */
private fun captureFrom(
    view: WebView,
    onCaptured: (CapturedSession) -> Unit,
    onUnavailable: () -> Unit,
) {
    val cookies = authCookieHeader()
    if (cookies == null) {
        onUnavailable()
        return
    }
    // Names only: never the values, which are the credential itself.
    Log.d(TAG, "stored session cookie names: ${cookieNames(cookies)}")
    // Flushed here rather than left to the WebView's own schedule: the screen
    // is usually closing in the next frame, and a cookie jar written after
    // that is a jar the next sign-in reads instead of this one.
    CookieManager.getInstance().flush()

    view.evaluateJavascript(YTCFG_PROBE) { raw ->
        val config = raw.parseConfig()
        val loggedIn = config?.get("loggedIn").let {
            it is JsonPrimitive && it.content == "true"
        }
        if (config == null || !loggedIn) {
            // A paywall or a plain YouTube page. The account is signed in —
            // the jar says so — and the identity is fetched from the desktop
            // music shell in MainViewModel.onWebSession instead.
            Log.d(TAG, "captured a session without a page identity; using the cookie jar")
            onCaptured(
                CapturedSession(
                    cookie = cookies,
                    pageId = null,
                    dataSyncId = null,
                    authUser = null,
                    visitorData = null,
                    clientVersion = null,
                    loggedIn = false,
                ),
            )
            return@evaluateJavascript
        }
        val pageId = config.string("pageId")
        onCaptured(
            CapturedSession(
                cookie = cookies,
                // A delegated identity is the most specific answer the live
                // page can give. Otherwise normalise its DATASYNC_ID.
                dataSyncId = pageId ?: normalizeDataSyncId(config.string("dataSyncId")),
                pageId = pageId,
                authUser = config.string("authUser"),
                visitorData = config.string("visitorData"),
                clientVersion = config.string("clientVersion"),
                loggedIn = true,
            ),
        )
    }
}

/**
 * The WebView's cookie jar as one header, or null until it is a complete
 * login.
 *
 * Both YouTube origins are read because the desktop landing and the music
 * origin can expose slightly different sets; the cookie domain is shared.
 * `www.youtube.com` is read first so its value wins on a duplicate name: the
 * app's own requests go to youtube.com, and that is the jar they need to match.
 */
private fun authCookieHeader(): String? {
    val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
    val header = listOf(YOUTUBE_ORIGIN, MUSIC_ORIGIN)
        .mapNotNull { manager.getCookie(it) }
        .flatMap { it.split(';') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.substringBefore('=').trim() }
        .joinToString("; ")
    return header.takeIf { YouTubeWebLogin.isSessionComplete(it) }
}

/** The cookie names in [header], sorted, for a log line that carries no values. */
private fun cookieNames(header: String): String = header
    .split(';')
    .map { it.substringBefore('=').trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .sorted()
    .joinToString(", ")
    .ifEmpty { "(none)" }

/**
 * The identity of the page as the page itself has it.
 *
 * Returns an object rather than a string so the WebView serialises it — a
 * probe that stringified its own result would come back double-encoded. A page
 * without `ytcfg` (an error page, a redirect that hasn't landed) returns null,
 * which is a fine answer and not an error.
 */
private const val YTCFG_PROBE = """
(function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    };
  } catch (e) {
    return null;
  }
})()
"""

private val json = Json { ignoreUnknownKeys = true }

/** The probe's result, or null for anything that isn't the object it promises. */
private fun String?.parseConfig(): JsonObject? =
    this?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
