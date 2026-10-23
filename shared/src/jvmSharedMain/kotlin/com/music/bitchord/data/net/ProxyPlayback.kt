package com.music.bitchord.data.net

import com.music.bitchord.data.Http
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * What a refused direct resolution should try next.
 *
 * Kept as a pure decision so the two rules that matter — a Korean IP's
 * "unavailable"/UNPLAYABLE answer is worth one proxy walk, and a track that
 * already succeeded through the proxy should start there next time — are
 * tested without a proxy, a socket, or a network.
 */
enum class ProxyPlaybackRoute {
    /** Ordinary path: no proxy involvement. */
    DIRECT,

    /** A previous play succeeded through the proxy; ask it first. */
    PROXY_FIRST,

    /** The direct path was refused; ask the proxy once. */
    PROXY_RETRY,
}

object ProxyPlaybackPolicy {

    /**
     * Whether a proxied player request can fix anything.
     *
     * Only [ProxyScope.MUSIC_ONLY] needs the fallback: [ProxyScope.ALL_YOUTUBE]
     * already sends both the player request and googlevideo bytes through the
     * proxy, and a proxy that is switched off or known unreachable cannot.
     */
    fun canProxyPlayback(config: ProxyConfig, proxyUnreachable: Boolean): Boolean =
        config.usable &&
            config.scope == ProxyScope.MUSIC_ONLY &&
            !proxyUnreachable

    /**
     * The refusal pattern measured from a Korean IP: InnerTubeX answers
     * "unavailable" for every client, the anonymous IOS/ANDROID player asks
     * UNPLAYABLE, and NewPipe demands a sign-in to prove it is not a bot.
     *
     * A location gate and a takedown look identical from here, so the caller
     * only spends one extra walk when a proxy is already configured.
     */
    fun isRegionRefusal(signInConfirmNotBot: Boolean, directUnplayable: Boolean): Boolean =
        signInConfirmNotBot || directUnplayable

    fun route(remembered: Boolean, refused: Boolean, canProxy: Boolean): ProxyPlaybackRoute = when {
        !canProxy -> ProxyPlaybackRoute.DIRECT
        remembered -> ProxyPlaybackRoute.PROXY_FIRST
        refused -> ProxyPlaybackRoute.PROXY_RETRY
        else -> ProxyPlaybackRoute.DIRECT
    }
}

/**
 * The tracks this process has played through the proxy, and the URLs that
 * were minted on that route.
 *
 * googlevideo binds a stream URL to the IP of the player request that minted
 * it, so the media fetch must use the same route. A URL in [proxiedUrls] is
 * the explicit signal for that — never a guess from the host name. The memory
 * is deliberately process-only and bounded: a URL is valid for hours, not
 * days, and the list is a latency cache rather than a store.
 */
object ProxyPlayback {

    private const val MAX_REMEMBERED = 500

    /** videoId -> proxied URL, least recently used first when over the cap. */
    private val remembered = LinkedHashMap<String, String>(16, 0.75f, true)

    /** The URLs a proxy player request minted; their bytes must follow the same route. */
    private val proxiedUrls = ConcurrentHashMap<String, String>()

    /**
     * Records that [videoId] resolved through the proxy to [url].
     *
     * @return true when this is the first success remembered for the id, so
     *   the caller can say so once rather than on every re-open.
     */
    @Synchronized
    fun markResolved(videoId: String, url: String): Boolean {
        val key = canonical(url)
        val first = !remembered.containsKey(videoId)
        remembered.remove(videoId)?.let { proxiedUrls.remove(it) }
        remembered[videoId] = key
        proxiedUrls[key] = videoId
        while (remembered.size > MAX_REMEMBERED) {
            val eldest = remembered.keys.first()
            val eldestUrl = remembered.remove(eldest)
            if (eldestUrl != null) proxiedUrls.remove(eldestUrl)
        }
        return first
    }

    /** Read through [get] so a remembered track also counts as recently used. */
    @Synchronized
    fun isRemembered(videoId: String): Boolean = remembered[videoId] != null

    fun isProxiedUrl(url: String): Boolean = proxiedUrls.containsKey(canonical(url))

    /** Whether a proxy is configured and healthy enough for a new player request. */
    fun canResolveViaProxy(): Boolean {
        val config = YouTubeProxy.config
        if (!config.usable || config.scope != ProxyScope.MUSIC_ONLY) return false
        return ProxyPlaybackPolicy.canProxyPlayback(config, ProxyHealthMonitor.shouldBypassProxy())
    }

    /** Whether bytes for [url] must be fetched over the proxy. */
    fun usesProxyFor(url: String): Boolean =
        proxiedUrls.containsKey(canonical(url)) && YouTubeProxy.config.usable

    /**
     * The same URL OkHttp will open, so `Uri` re-encoding between resolution
     * and playback cannot lose the record.
     */
    private fun canonical(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url

    /**
     * The client a fetch for [url] must use.
     *
     * A proxied URL is fetched through the dedicated proxy client, whose own
     * connection pool keeps a direct socket from being handed to a request
     * that has to leave from the proxy's address. Everything else keeps the
     * app's shared client.
     */
    fun mediaClientFor(url: String): OkHttpClient =
        if (usesProxyFor(url)) Http.proxyMediaClient else Http.client
}
