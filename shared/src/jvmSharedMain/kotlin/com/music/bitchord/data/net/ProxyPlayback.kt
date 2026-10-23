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

    /** The URLs a proxy player request minted; their bytes must follow the same route. */
    private val proxiedUrls = ConcurrentHashMap<String, String>()

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
    fun markResolved(videoId: String, url: String): Boolean {
        val key = canonical(url)
        val first = !remembered.containsKey(videoId)
        remembered.remove(videoId)?.let { proxiedUrls.remove(it) }
        remembered[videoId] = key
        proxiedUrls[key] = videoId
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
