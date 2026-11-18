package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

/**
 * InnerTubeX clients that keep answering "client response unavailable" on this
 * network, so a catalog walk stops asking them first. (UPG-speed item 1)
 *
 * On the 11-18 phone log VISIONOS failed 766 times and served 0, and
 * WEB_EMBEDDED_PLAYER failed 251 times and served 0, almost all inside proxy
 * catalog walks: each walk spent a median 0.9 s (p90 3.1 s) on them before
 * WEB_CREATOR answered.
 *
 * A client is skipped on a route only after it failed for [MIN_VIDEOS]
 * different videos and never served one there. The memo is per route (direct,
 * or the proxy at its current route generation), and is wiped when the default
 * network changes or the YouTube session changes. It is driven by events,
 * never by a timer. The skipped clients stay a fallback: [InnerTubeXResolver]
 * walks again without them when the walk that skipped them finds nothing.
 *
 * [KO] cold1119: the in-memory memo alone never helped a cold start, the walk
 * that matters most: the process restarts every 30-90 minutes and each new
 * process paid VISIONOS x3 + WEB_EMBEDDED_PLAYER again (about 2-3 s on the
 * 11-18 17:11 cold start) for three more videos before skipping them. A client
 * that crossed [MIN_VIDEOS] is now also written to [init]'s store (once per
 * change, never in a loop) under the route's server identity (not its
 * per-process generation), and a new process skips it for [RESTORED_TTL_MS]
 * after it was recorded. The same events that wipe the memo wipe the store.
 */
object KoDeadClients {
    private const val TAG = "BitChord"
    const val MIN_VIDEOS = 3

    /** How long a client recorded dead by an earlier process stays skipped. */
    const val RESTORED_TTL_MS = 24L * 60 * 60 * 1000

    private class Walk(val route: String, val videoId: String)

    private class Route {
        val failedOn = HashMap<String, MutableSet<String>>()
        val served = HashSet<String>()
    }

    private val walk = ThreadLocal<Walk?>()
    private val routes = HashMap<String, Route>()
    private var network: String? = null

    /** Server identity ([placeOf]) -> client -> when it was recorded dead. */
    private val recorded = HashMap<String, HashMap<String, Long>>()
    private var write: ((String) -> Unit)? = null
    private var now: () -> Long = System::currentTimeMillis

    /**
     * The memo key of a walk: direct or through the proxy, at the proxy's
     * [proxyGeneration] (it moves with every proxy route or setting change,
     * which also changes where a "direct" walk goes when the proxy carries all
     * of YouTube). [place] names the same route across processes ([place]()).
     */
    fun route(viaProxy: Boolean, proxyGeneration: Long, place: String = ""): String =
        "${if (viaProxy) "proxy" else "direct"}:$proxyGeneration" + if (place.isEmpty()) "" else ":$place"

    /**
     * What the proxy route is, stable across processes: a hash of the settings
     * and the server in use, so another server or scope never inherits a memo.
     */
    fun place(): String {
        val proxy = com.music.bitchord.data.net.YouTubeProxy
        val c = proxy.config
        val base = if (!c.usable) {
            "off"
        } else {
            val server = if (proxy.usingBackup) "b/${c.backupHost}/${c.backupPort}" else "p/${c.host}/${c.port}"
            Integer.toHexString("${c.mode.name}/${c.scope.name}/$server".hashCode())
        }
        // [KO] fix1119 F3: Wi-Fi and LTE reach YouTube from different addresses, and the network id the wipe uses is per process.
        val kind = transport
        return if (kind.isEmpty()) base else "$base.$kind"
    }

    @Volatile private var transport = ""

    /**
     * The default network's transport ("wifi", "cell", ...), stable across
     * processes unlike [onNetwork]'s id, so a record made on one transport
     * is never restored on another. Empty while unknown.
     */
    fun onTransport(kind: String) {
        transport = kind
    }

    /**
     * "proxy:7:abc" -> "proxy@abc": the route without its per-process
     * generation. A route made without a place is never stored.
     */
    private fun placeOf(route: String): String? {
        val parts = route.split(':')
        if (parts.size < 3) return null
        return parts[0] + "@" + parts.drop(2).joinToString(":")
    }

    /**
     * Loads what an earlier process recorded and installs the writer. Called
     * once at startup; [stored] is the value [write] last received.
     */
    @Synchronized
    fun init(stored: String?, write: (String) -> Unit, clock: () -> Long = System::currentTimeMillis) {
        this.write = write
        now = clock
        recorded.clear()
        val at = clock()
        stored.orEmpty().lineSequence().forEach { line ->
            val cells = line.split('\t')
            val time = cells.getOrNull(2)?.toLongOrNull() ?: return@forEach
            if (cells[0].isEmpty() || cells[1].isEmpty() || at - time !in 0 until RESTORED_TTL_MS) return@forEach
            recorded.getOrPut(cells[0]) { HashMap() }[cells[1]] = time
        }
        recorded.forEach { (place, clients) ->
            TrackLog.d(TAG, "dead-client memo: restored ${clients.keys.sorted().joinToString()} on $place " +
                "(recorded ${(at - clients.values.min()) / 60_000} min ago)")
        }
    }

    private fun persist() {
        val out = write ?: return
        out(recorded.entries.joinToString("\n") { (place, clients) ->
            clients.entries.joinToString("\n") { "$place\t${it.key}\t${it.value}" }
        })
    }

    /**
     * Context for one InnerTubeX walk, so [observe] can tell which video and
     * route a log event belongs to. Events raised outside it are ignored.
     */
    fun walkContext(route: String, videoId: String): CoroutineContext =
        walk.asContextElement(Walk(route, videoId))

    /**
     * Feeds one InnerTubeX log event; counts only a client's unavailable
     * answer. A request that failed in transit, or one sent without the token
     * the client needs, says nothing lasting about the client and is not counted.
     */
    fun observe(tag: String?, message: String?, details: Map<String, String>) {
        if (tag != "PlayerClientDirector" || message?.startsWith("client response unavailable") != true) return
        if (details["requestFailure"] == "true" || details["tokenUnavailable"] == "true") return
        val client = details["client"]
        val current = walk.get() ?: return
        failed(current.route, client ?: return, current.videoId)
    }

    @Synchronized
    fun failed(route: String, client: String, videoId: String) {
        val state = routeState(route)
        if (client in state.served) return
        val videos = state.failedOn.getOrPut(client) { HashSet() }
        if (videos.size >= MIN_VIDEOS) return
        videos += videoId
        if (videos.size == MIN_VIDEOS) {
            TrackLog.d(TAG, "dead-client memo: skipping $client on $route (unavailable for $MIN_VIDEOS videos, served none)")
            placeOf(route)?.let { place ->
                recorded.getOrPut(place) { HashMap() }[client] = now()
                persist()
            }
        }
    }

    @Synchronized
    fun served(route: String, client: String?) {
        if (client.isNullOrBlank()) return
        val state = routeState(route)
        state.served += client
        state.failedOn.remove(client)
        val place = placeOf(route) ?: return
        if (recorded[place]?.remove(client) != null) {
            if (recorded[place].isNullOrEmpty()) recorded.remove(place)
            persist()
        }
    }

    /** Clients a walk on [route] should not ask first. */
    @Synchronized
    fun dead(route: String): Set<String> {
        val state = routes[route]
        val inMemory = state?.failedOn?.filterValues { it.size >= MIN_VIDEOS }?.keys.orEmpty()
        val at = now()
        val restored = placeOf(route)?.let { recorded[it] }?.filterValues { at - it in 0 until RESTORED_TTL_MS }?.keys.orEmpty()
        return (inMemory + restored) - state?.served.orEmpty()
    }

    /**
     * [KO] fix1119 F3: one walk without the [dead] clients, then one with them
     * whenever it finds nothing or fails, whatever the failure says. A
     * "definitely gone" answer from the walk that skipped them is not the
     * video's verdict: a client skipped here (dead on a stale memo, or on
     * another network this process only thinks it left) may still serve it,
     * and the verdict is kept and acted on for the rest of the play. So a
     * verdict only ever comes from the walk that asked every client.
     */
    suspend fun <T : Any> walkSkippingDead(
        dead: Set<String>,
        walk: suspend (skipDead: Boolean) -> T?,
        onRetry: (Exception) -> Unit = {},
    ): T? {
        if (dead.isNotEmpty()) {
            try {
                walk(true)?.let { return it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onRetry(e)
            }
        }
        return walk(false)
    }

    /** The default network is [id] now; a different one starts a fresh memo. */
    @Synchronized
    fun onNetwork(id: String) {
        val previous = network
        network = id
        if (previous != null && previous != id) clear("network changed")
    }

    @Synchronized
    fun clear(reason: String) {
        if (recorded.isNotEmpty() || routes.values.any { it.failedOn.values.any { v -> v.size >= MIN_VIDEOS } }) {
            TrackLog.d(TAG, "dead-client memo cleared: $reason")
        }
        routes.clear()
        if (recorded.isNotEmpty()) {
            recorded.clear()
            persist()
        }
    }

    private fun routeState(route: String): Route {
        // A new proxy generation is another server, address or scope: forget the old one.
        val generation = route.substringAfter(':')
        if (route !in routes) routes.keys.removeAll { it.substringAfter(':') != generation }
        return routes.getOrPut(route) { Route() }
    }
}
