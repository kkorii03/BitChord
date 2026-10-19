package com.music.bitchord.data.net

import com.music.bitchord.data.Http
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

    @Volatile
    private var current = ProxyConfig()

    /** The configuration in force. Never logged as-is. */
    val config: ProxyConfig get() = current

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
        current = normalized
        ProxyHealthMonitor.clear()
        // Off the caller's thread: closing a pooled TLS socket writes its
        // close_notify, and the settings dialog saves on the main thread, where
        // Android kills the app for network I/O (crashed on the S24, ko-74).
        Thread({ runCatching { Http.client.connectionPool.evictAll() } }, "proxy-evict")
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
    internal val proxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val config = current
            if (!config.usable) return listOf(Proxy.NO_PROXY)
            if (ProxyHealthMonitor.shouldBypassProxy()) return listOf(Proxy.NO_PROXY)
            if (!ProxyScopeMatcher.matches(uri.host, uri.path, config.scope)) {
                return listOf(Proxy.NO_PROXY)
            }
            val type = when (config.mode) {
                ProxyMode.SOCKS5 -> Proxy.Type.SOCKS
                ProxyMode.HTTP -> Proxy.Type.HTTP
                ProxyMode.OFF -> return listOf(Proxy.NO_PROXY)
            }
            return listOf(
                Proxy(type, InetSocketAddress(config.host, config.port)),
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
            if (proxyPort != null && proxyPort != config.port) return null

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
            if (requestingPort != config.port) return null
            if (!requestingHost.equals(config.host, ignoreCase = true)) return null
            return PasswordAuthentication(config.username, config.password.toCharArray())
        }
    }

    init {
        runCatching { Authenticator.setDefault(SocksAuthenticator) }
    }
}
