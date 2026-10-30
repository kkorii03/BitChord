package com.music.bitchord.data.net

import com.music.bitchord.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * Whether a cached URL may still be handed to the player.
     *
     * A direct URL has no route stamp and survives any proxy change. A proxied
     * URL is only valid on the generation (route + configuration) that minted
     * it, and only while a proxy is still usable: after a primary-to-backup
     * switch or an edit, the old URL is bound to the old server's address and
     * is answered with 403.
     */
    fun cachedUrlUsable(
        proxyGeneration: Long?,
        currentGeneration: Long,
        canResolveViaProxy: Boolean,
        allScopeNow: Boolean = false,
        mintedOnAllScope: Boolean = true,
    ): Boolean {
        // Switching to ALL_YOUTUBE changes how the *media* fetch is routed,
        // not only how the player request is. A URL resolved while the scope
        // was Music-only was minted on the phone's own address, so fetching it
        // through the proxy after the switch is a 403. It has no proxy
        // generation to invalidate it, which is why the scope is part of the
        // test rather than inferred from the stamp.
        if (allScopeNow && !mintedOnAllScope) return false
        return proxyGeneration == null ||
            (proxyGeneration == currentGeneration && canResolveViaProxy)
    }

    /** How a resolved URL is stamped in the resolver's short-lived cache. */
    data class ResolvedUrlStamp(val proxyGeneration: Long?, val mintedOnAllScope: Boolean)

    /**
     * Whether the cache is being read while ALL_YOUTUBE is the route actually
     * in force. The bypass is part of the answer: after an ALL proxy outage the
     * request goes direct, so a direct-minted URL is not a MISS merely because
     * the setting still says ALL.
     */
    fun allScopeNow(configUsable: Boolean, allScope: Boolean, bypassing: Boolean): Boolean =
        configUsable && allScope && !bypassing

    /**
     * The route stamp to record for a URL, from the state captured when its
     * request *started*.
     *
     * Reading the route and scope at completion labels a slow ALL_YOUTUBE
     * resolve with whatever the app is doing now: a primary-to-backup switch
     * during the walk makes an old-server URL look current, and a switch into
     * ALL makes a URL minted directly look proxy-minted. Both are the same 403
     * on the next open. [capturedOnAllScope] is therefore passed in from the
     * caller's start; null means "not captured" (a URL handed back by a retry)
     * and keeps the previous live-read behaviour.
     *
     * [registeredGeneration] is the authoritative stamp for a Music-only URL
     * that went through [ProxyPlayback.markResolved]; it is never replaced by
     * the captured ALL value.
     */
    fun stampFor(
        capturedGeneration: Long?,
        capturedOnAllScope: Boolean?,
        registeredGeneration: Long?,
        currentAllScope: Boolean,
    ): ResolvedUrlStamp {
        if (capturedOnAllScope == true) {
            return ResolvedUrlStamp(capturedGeneration, true)
        }
        if (capturedOnAllScope == false) {
            return ResolvedUrlStamp(registeredGeneration, false)
        }
        return ResolvedUrlStamp(
            proxyGeneration = registeredGeneration,
            mintedOnAllScope = registeredGeneration != null && currentAllScope,
        )
    }

    /**
     * Whether a URL obtained by a walk counts as minted on the ALL_YOUTUBE
     * route.
     *
     * Both boundaries must show ALL in force. A walk that starts on ALL but
     * ends during the proxy's unreachable bypass obtained its URL directly, so
     * stamping it proxy-minted makes the next open follow the proxy once the
     * bypass expires — a 403 from googlevideo. A walk that starts outside ALL
     * and only switches in mid-way is the same mistake in reverse: its URL was
     * minted directly before the switch.
     */
    fun mintedOnAllScopeAfterWalk(allScopeAtStart: Boolean, allScopeAtEnd: Boolean): Boolean =
        allScopeAtStart && allScopeAtEnd
}

/**
 * The tracks this process has played through the proxy, and the URLs that
 * were minted on that route.
 *
 * googlevideo binds a stream URL to the IP of the player request that minted
 * it, so the media fetch must use the same route. A URL in [proxiedUrls] is
 * the explicit signal for that — never a guess from the host name. The URL
 * cache itself is process-only: a URL is valid for hours, not days. The
 * videoId memory, however, survives restarts for up to 30 days so a known
 * refused track starts on the proxy next time; [rememberedTimestamps] is the
 * persisted part and is filled by [attachPersistence].
 */
object ProxyPlayback {

    private const val MAX_REMEMBERED = 500

    /** Keep a remembered id for 30 days after its last successful play. */
    internal const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L

    /** videoId -> proxied URL, least recently used first when over the cap. */
    private val remembered = LinkedHashMap<String, String>(16, 0.75f, true)

    /** A URL a proxy player request minted, and the route generation that did it. */
    private data class ProxiedUrl(val videoId: String, val generation: Long)

    /** The URLs a proxy player request minted; their bytes must follow the same route. */
    private val proxiedUrls = ConcurrentHashMap<String, ProxiedUrl>()

    /** videoId -> last successful proxy play, the part that survives restarts. */
    private val rememberedTimestamps = ConcurrentHashMap<String, Long>()

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    @Volatile
    private var saveHook: ((Map<String, Long>) -> Unit)? = null

    /**
     * Loads the persisted videoId memory and installs the save callback.
     *
     * Called once at app start. [load] returns the raw stored map; entries
     * older than [MAX_AGE_MS] are dropped here so callers never see them.
     * [save] receives a debounced snapshot on a background coroutine.
     */
    @Synchronized
    fun attachPersistence(
        load: () -> Map<String, Long>,
        save: (Map<String, Long>) -> Unit,
    ) {
        val loaded = filterFresh(load(), System.currentTimeMillis())
        // Keep any id recorded before the store was attached; the disk copy is
        // the older, authoritative one for the rest.
        loaded.forEach { (id, at) -> rememberedTimestamps.putIfAbsent(id, at) }
        trimToCap()
        saveHook = save
    }

    /**
     * Records that [videoId] resolved through the proxy to [url].
     *
     * @return true when this is the first success remembered for the id, so
     *   the caller can say so once rather than on every re-open.
     */
    @Synchronized
    fun markResolved(
        videoId: String,
        url: String,
        generation: Long = YouTubeProxy.routeGeneration,
    ): Boolean {
        val key = canonical(url)
        val first = !remembered.containsKey(videoId)
        remembered.remove(videoId)?.let { proxiedUrls.remove(it) }
        remembered[videoId] = key
        proxiedUrls[key] = ProxiedUrl(videoId, generation)
        rememberedTimestamps[videoId] = System.currentTimeMillis()
        trimToCap()
        scheduleSave()
        return first
    }

    /**
     * Read through [get] so a remembered track also counts as recently used.
     *
     * A restarted process has no URL for a persisted id yet; the id still
     * counts as remembered so the next resolve starts on the proxy, and the
     * fresh URL it mints fills [remembered].
     */
    @Synchronized
    fun isRemembered(videoId: String): Boolean {
        if (remembered[videoId] != null) return true
        val at = rememberedTimestamps[videoId] ?: return false
        if (System.currentTimeMillis() - at > MAX_AGE_MS) {
            rememberedTimestamps.remove(videoId)
            return false
        }
        return true
    }

    /** How many tracks are remembered as having played through the proxy. */
    @Synchronized
    fun rememberedCount(now: Long = System.currentTimeMillis()): Int =
        rememberedTimestamps.count { (_, at) -> now - at <= MAX_AGE_MS }

    /** Drops persisted ids older than [MAX_AGE_MS]; pure so the cutoff is tested. */
    internal fun filterFresh(entries: Map<String, Long>, now: Long): Map<String, Long> =
        entries.filterValues { now - it <= MAX_AGE_MS }

    /**
     * Trims both the URL cache and the persisted id memory to [MAX_REMEMBERED].
     *
     * The URL cache is LRU; the persisted memory drops its oldest timestamps so
     * a restart cannot resurrect entries the cap would have evicted.
     */
    private fun trimToCap() {
        while (remembered.size > MAX_REMEMBERED) {
            val eldest = remembered.keys.first()
            val eldestUrl = remembered.remove(eldest)
            if (eldestUrl != null) proxiedUrls.remove(eldestUrl)
            rememberedTimestamps.remove(eldest)
        }
        while (rememberedTimestamps.size > MAX_REMEMBERED) {
            val oldest = rememberedTimestamps.entries.minByOrNull { it.value } ?: break
            rememberedTimestamps.remove(oldest.key)
        }
    }

    /**
     * Writes the persisted memory after the last change settles, not on every
     * play: a queue that walks several proxied tracks should cost one write.
     */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(3_000)
            runCatching { saveHook?.invoke(rememberedTimestamps.toMap()) }
        }
    }

    fun isProxiedUrl(url: String): Boolean = proxiedUrls.containsKey(canonical(url))

    /**
     * The route generation that minted [url], or null when it was not proxied.
     *
     * Under [ProxyScope.ALL_YOUTUBE] every YouTube host — the player request
     * included — goes through the proxy, so a URL resolved on that scope is
     * bound to the route even though it never passes through the Music-only
     * fallback that calls [markResolved]. It still needs a stamp: a route
     * switch or a settings edit makes it a 403 exactly as it does for a
     * Music-only URL. While the proxy is in its unreachable bypass the request
     * went direct, so no stamp is given and the URL is allowed to survive.
     */
    fun generationFor(url: String): Long? {
        val key = canonical(url)
        proxiedUrls[key]?.let { return it.generation }
        val config = YouTubeProxy.config
        if (!config.usable || config.scope != ProxyScope.ALL_YOUTUBE) return null
        if (ProxyHealthMonitor.shouldBypassProxy()) return null
        val parsed = key.toHttpUrlOrNull() ?: return null
        if (!ProxyScopeMatcher.matches(parsed.host, parsed.encodedPath, ProxyScope.ALL_YOUTUBE)) {
            return null
        }
        return YouTubeProxy.routeGeneration
    }

    /** The route generation in force now. */
    val currentRouteGeneration: Long get() = YouTubeProxy.routeGeneration

    /** Whether [generation] is still the route in force. */
    fun isCurrentRoute(generation: Long): Boolean = generation == YouTubeProxy.routeGeneration

    /** Whether a proxy is configured and healthy enough for a new player request. */
    fun canResolveViaProxy(): Boolean {
        val config = YouTubeProxy.config
        if (!config.usable || config.scope != ProxyScope.MUSIC_ONLY) return false
        return ProxyPlaybackPolicy.canProxyPlayback(config, ProxyHealthMonitor.shouldBypassProxy())
    }

    /**
     * Whether a cached URL may be reused on the current route.
     *
     * Wider than [canResolveViaProxy] on purpose: an ALL_YOUTUBE URL is also
     * bound to the route that minted it, even though that scope never uses the
     * Music-only playback fallback. Both scopes need the same answer here, or
     * an ALL_YOUTUBE URL survives a primary-to-backup switch and 403s.
     */
    fun canUseCachedProxyUrl(): Boolean {
        val config = YouTubeProxy.config
        if (!config.usable) return false
        if (config.scope != ProxyScope.MUSIC_ONLY && config.scope != ProxyScope.ALL_YOUTUBE) {
            return false
        }
        return !ProxyHealthMonitor.shouldBypassProxy()
    }

    /**
     * Whether bytes for [url] must be fetched over the proxy.
     *
     * Only a URL minted by the route in force qualifies. A stale stamp means
     * the URL belongs to a server that is no longer being asked, so treating
     * it as proxied would send it to the wrong address (or treat it as direct,
     * which is the same 403 from the other side). Recovery re-resolves it.
     */
    fun usesProxyFor(url: String): Boolean {
        val entry = proxiedUrls[canonical(url)] ?: return false
        return entry.generation == YouTubeProxy.routeGeneration && YouTubeProxy.config.usable
    }

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
