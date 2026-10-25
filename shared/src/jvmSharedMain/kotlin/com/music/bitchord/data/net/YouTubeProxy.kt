package com.music.bitchord.data.net

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.Authenticator as OkHttpAuthenticator
import okhttp3.Credentials

/**
 * The one place the optional proxy lives for the network stack.
 *
 * The app shares a single OkHttp client ([Http.client]) between Innertube,
 * NewPipe, the stream probes, ExoPlayer's media fetches and downloads, so
 * installing a [ProxySelector] and an [OkHttpAuthenticator] on that client
 * covers every path at once. The selector reads [config] on each request
 * instead of being rebuilt, so turning the proxy on or off takes effect on the
 * next request; [update] also empties the connection pool so a request cannot
 * be served over a direct socket opened before the switch moved.
 *
 * `AppSettings` owns persistence and publishes here, exactly as it does for
 * `WebDavAuth` and `SmbAuth`; the shared network layer never touches storage
 * and never logs this object (whose [ProxyConfig.toString] redacts the
 * password).
 */
object YouTubeProxy {

    private const val TAG = "BitChord"

    @Volatile
    private var current = ProxyConfig()

    /** The configuration in force. Never logged as-is. */
    val config: ProxyConfig get() = current

    private val _activeRoute = MutableStateFlow(ProxyRoute.PRIMARY)

    /** Which configured server requests are using right now: primary or backup. */
    val activeRoute: StateFlow<ProxyRoute> = _activeRoute.asStateFlow()

    @Volatile
    private var routeChangedAtMs = 0L

    /**
     * Whether requests really go to the backup server now.
     *
     * False whenever no backup is configured, even if the route still reads
     * [ProxyRoute.BACKUP] from a switch made before the configuration changed.
     */
    val usingBackup: Boolean
        get() = current.backupUsable && _activeRoute.value == ProxyRoute.BACKUP

    /** Milliseconds since the route last moved, for [ProxyRoutePolicy]. */
    fun millisSinceRouteChange(now: Long = System.currentTimeMillis()): Long =
        if (routeChangedAtMs == 0L) Long.MAX_VALUE else now - routeChangedAtMs

    /**
     * Moves traffic to the other server. Called from [ProxyHealthMonitor] at a
     * request failure or success, never from a timer.
     *
     * The connection pools are dropped because a keep-alive socket to the old
     * server would otherwise carry the next request, and because a stream URL
     * minted on one route must never be fetched over a socket opened on the
     * other. The log line names only the direction and the reason: the servers
     * themselves are the listener's own configuration and stay out of logs.
     */
    @Synchronized
    internal fun switchRoute(target: ProxyRoute, reason: String) {
        if (_activeRoute.value == target) return
        _activeRoute.value = target
        routeChangedAtMs = System.currentTimeMillis()
        if (target == ProxyRoute.BACKUP) {
            Log.i(TAG, "proxy: switched to backup ($reason)")
        } else {
            Log.i(TAG, "proxy: back to primary")
        }
        evictPools()
    }

    /**
     * Publishes a new configuration. Called at startup and on every edit.
     *
     * Dropping pooled connections is what makes "off" mean off immediately: a
     * keep-alive socket established while the proxy was bypassed would
     * otherwise carry the next request straight to Google.
     */
    fun update(config: ProxyConfig) {
        val normalized = config.normalized()
        if (normalized == current) return
        // A new configuration is a new pair of servers: start by asking the
        // primary, which is also what a fresh process does. The route is reset
        // before the config is published so a selector can never pair the new
        // config with the previous one's backup switch.
        _activeRoute.value = ProxyRoute.PRIMARY
        routeChangedAtMs = 0L
        current = normalized
        ProxyHealthMonitor.clear()
        evictPools()
    }

    /**
     * Off the caller's thread: closing a pooled TLS socket writes its
     * close_notify, and the settings dialog saves on the main thread, where
     * Android kills the app for network I/O (crashed on the S24, ko-74).
     */
    private fun evictPools() {
        Thread({
            runCatching {
                Http.client.connectionPool.evictAll()
                // The proxy-pinned client keeps its own pool; stale sockets
                // there still point at the route being replaced.
                Http.proxyMediaClient.connectionPool.evictAll()
            }
        }, "proxy-evict")
            .apply { isDaemon = true }
            .start()
    }

    /** Whether [url] would be sent through the proxy under the current scope. */
    fun matches(url: String): Boolean {
        val config = current
        if (!config.usable) return false
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        return ProxyScopeMatcher.matches(parsed.host, parsed.path, config.scope)
    }

    /**
     * The selector OkHttp asks per route. Everything outside the chosen scope
     * answers [Proxy.NO_PROXY] and keeps its normal connection.
     */
    internal val proxySelector: ProxySelector = selector(ignoreHealth = false)

    /**
     * The same selector without the "proxy was unreachable" bypass.
     *
     * Used for a request that was already decided to go through the proxy (and
     * for the Settings connection test): if the health memory flips to
     * UNREACHABLE between that decision and the selector running, the request
     * must still really use the proxy — otherwise a success would be recorded
     * for a route it never took and would clear a fresh UNREACHABLE.
     */
    internal val forceProxySelector: ProxySelector = selector(ignoreHealth = true)

    /**
     * The same selector, but every YouTube host — `player` included — is sent
     * through the proxy regardless of scope.
     *
     * This exists for the one track whose direct player request a Korean IP
     * refused: its stream URL is bound to the proxy's address, so both the
     * player ask that mints it and the googlevideo fetch afterwards must leave
     * from there. Non-YouTube hosts (InnerTubeX's remote player config, for
     * one) keep their direct connection. Health is ignored because a URL that
     * was already minted through the proxy cannot be fetched directly; the
     * caller decides whether to start a new proxy request at all.
     */
    internal val forceYouTubeProxySelector: ProxySelector =
        selector(ignoreHealth = true, forceYouTubeHosts = true)

    private fun selector(
        ignoreHealth: Boolean,
        forceYouTubeHosts: Boolean = false,
    ): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val config = current
            if (!config.usable) return listOf(Proxy.NO_PROXY)
            if (!ignoreHealth && ProxyHealthMonitor.shouldBypassProxy()) return listOf(Proxy.NO_PROXY)
            if (forceYouTubeHosts) {
                if (!ProxyScopeMatcher.matches(uri.host, uri.path, ProxyScope.ALL_YOUTUBE)) {
                    return listOf(Proxy.NO_PROXY)
                }
            } else if (!ProxyScopeMatcher.matches(uri.host, uri.path, config.scope)) {
                return listOf(Proxy.NO_PROXY)
            }
            val type = when (config.mode) {
                ProxyMode.SOCKS5 -> Proxy.Type.SOCKS
                ProxyMode.HTTP -> Proxy.Type.HTTP
                ProxyMode.OFF -> return listOf(Proxy.NO_PROXY)
            }
            // The selector is the one place the active route becomes an
            // address, so a switch needs no client rebuild: the next request
            // is simply addressed to the other server. Read from the same
            // snapshot the scope check above used, so a simultaneous settings
            // save cannot pair one config's route with another's address.
            val backup = config.backupUsable && _activeRoute.value == ProxyRoute.BACKUP
            return listOf(
                Proxy(
                    type,
                    InetSocketAddress(
                        if (backup) config.backupHost else config.host,
                        if (backup) config.backupPort else config.port,
                    ),
                ),
            )
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // Nothing useful to add: OkHttp reports the failed route to its
            // caller. The URI can contain a session-bearing query, so it is
            // deliberately not written to a log here.
        }
    }

    /**
     * Answers an HTTP proxy's 407 with the saved Basic credentials.
     *
     * OkHttp calls this for 401 and 407 alike, so both the status and the route
     * are checked before a credential is attached: a 401 from YouTube itself is
     * an account problem, not a proxy handshake, and must not be answered with
     * the proxy password.
     */
    internal val proxyAuthenticator: OkHttpAuthenticator = object : OkHttpAuthenticator {
        override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
            val config = current
            if (!config.usable || config.mode != ProxyMode.HTTP) return null
            if (response.code != 407) return null
            val routeProxy = route?.proxy ?: return null
            if (routeProxy.type() != Proxy.Type.HTTP) return null
            if (config.username.isEmpty()) return null
            val proxyPort = (routeProxy.address() as? InetSocketAddress)?.port
            // Either configured server may be answering this handshake; the
            // credentials are the same one the listener entered for both.
            val knownPort = proxyPort == config.port ||
                (config.backupUsable && proxyPort == config.backupPort)
            if (proxyPort != null && !knownPort) return null

            val credential = Credentials.basic(config.username, config.password)
            if (response.request.header("Proxy-Authorization") == credential) {
                // Credentials were already presented and refused; asking again
                // with the same header would loop.
                return null
            }
            return response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
    }

    /**
     * SOCKS5 username/password authentication, which is a JDK-level handshake
     * rather than an OkHttp header. The authenticator answers only while a
     * SOCKS5 proxy with a username is configured, and only for that proxy's
     * host and port; every other request falls through (null) untouched.
     */
    private object SocksAuthenticator : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val config = current
            if (!config.usable || config.mode != ProxyMode.SOCKS5) return null
            if (requestorType != java.net.Authenticator.RequestorType.PROXY) return null
            if (config.username.isEmpty()) return null
            // The SOCKS5 handshake names the server it is talking to; only the
            // configured pair may receive this credential, backup included.
            val primary = requestingPort == config.port &&
                requestingHost.equals(config.host, ignoreCase = true)
            val backup = config.backupUsable &&
                requestingPort == config.backupPort &&
                requestingHost.equals(config.backupHost, ignoreCase = true)
            if (!primary && !backup) return null
            return PasswordAuthentication(config.username, config.password.toCharArray())
        }
    }

    init {
        runCatching { Authenticator.setDefault(SocksAuthenticator) }
    }
}
