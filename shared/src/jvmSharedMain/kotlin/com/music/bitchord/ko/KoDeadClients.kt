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
 * different videos and never served one there. The memo lives in memory
 * only (an app restart starts empty), is per route (direct, or the proxy at
 * its current route generation), and is wiped when the default network
 * changes or the YouTube session changes. It is driven by events, never by a
 * timer. The skipped clients stay a fallback: [InnerTubeXResolver] walks
 * again without them when the walk that skipped them finds nothing.
 */
object KoDeadClients {
    private const val TAG = "BitChord"
    const val MIN_VIDEOS = 3

    private class Walk(val route: String, val videoId: String)

    private class Route {
        val failedOn = HashMap<String, MutableSet<String>>()
        val served = HashSet<String>()
    }

    private val walk = ThreadLocal<Walk?>()
    private val routes = HashMap<String, Route>()
    private var network: String? = null

    /**
     * The memo key of a walk: direct or through the proxy, at the proxy's
     * [proxyGeneration] (it moves with every proxy route or setting change,
     * which also changes where a "direct" walk goes when the proxy carries all
     * of YouTube).
     */
    fun route(viaProxy: Boolean, proxyGeneration: Long): String =
        "${if (viaProxy) "proxy" else "direct"}:$proxyGeneration"

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
        }
    }

    @Synchronized
    fun served(route: String, client: String?) {
        if (client.isNullOrBlank()) return
        val state = routeState(route)
        state.served += client
        state.failedOn.remove(client)
    }

    /** Clients a walk on [route] should not ask first. */
    @Synchronized
    fun dead(route: String): Set<String> {
        val state = routes[route] ?: return emptySet()
        return state.failedOn.filterValues { it.size >= MIN_VIDEOS }.keys.toSet()
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
        if (routes.values.any { it.failedOn.values.any { v -> v.size >= MIN_VIDEOS } }) {
            TrackLog.d(TAG, "dead-client memo cleared: $reason")
        }
        routes.clear()
    }

    private fun routeState(route: String): Route {
        // A new proxy generation is another server, address or scope: forget the old one.
        val generation = route.substringAfter(':')
        if (route !in routes) routes.keys.removeAll { it.substringAfter(':') != generation }
        return routes.getOrPut(route) { Route() }
    }
}
