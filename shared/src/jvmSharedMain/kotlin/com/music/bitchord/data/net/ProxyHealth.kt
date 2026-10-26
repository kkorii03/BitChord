package com.music.bitchord.data.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap

/** How the optional YouTube Music proxy is currently behaving. */
enum class ProxyHealth { UNKNOWN, OK, UNREACHABLE }

/**
 * Recognises a failure that says the proxy route itself is dead, as opposed to
 * YouTube refusing the request or the phone's own connection failing.
 *
 * Kept a pure function: the caller only asks about a request that really went
 * through the proxy, so a connect failure or timeout there is the proxy's
 * answer. OkHttp reports a refused CONNECT handshake as an IOException whose
 * message names the proxy ("Unable to tunnel through proxy ... 407").
 */
object ProxyFailure {

    fun isProxyDown(failure: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        while (true) {
            val node = current ?: return false
            if (!seen.add(node)) return false
            when (node) {
                is ConnectException,
                is SocketTimeoutException,
                is UnknownHostException,
                is NoRouteToHostException -> return true
            }
            val message = node.message.orEmpty()
            if (message.contains("proxy", ignoreCase = true) ||
                message.contains("407") ||
                message.contains("unable to tunnel", ignoreCase = true)
            ) {
                return true
            }
            current = node.cause
        }
    }

    /**
     * Whether a failed proxy request may be recorded as "the proxy is
     * unreachable".
     *
     * Only a direct retry that then *succeeds* proves the proxy alone is down.
     * If the direct request fails too, the phone's own network is what failed
     * — every host is unresolvable — and remembering the proxy as unreachable
     * would blame it for an outage and toast about a fallback route that never
     * worked. The caller rethrows the direct failure in that case and leaves
     * [ProxyHealthMonitor] at [ProxyHealth.UNKNOWN].
     */
    fun directRetryProvesProxyDown(directRetrySucceeded: Boolean): Boolean = directRetrySucceeded
}

/**
 * Remembers "the proxy was unreachable" for a short window, so requests after
 * the first failure skip the dead route instead of each waiting for a timeout.
 *
 * The clock is injected so the window is tested without sleeping.
 */
class ProxyHealthMemory(private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        const val WINDOW_MS = 5L * 60L * 1000L
    }

    @Volatile
    private var unreachableAt: Long? = null

    @Synchronized
    fun markUnreachable() {
        unreachableAt = clock()
    }

    @Synchronized
    fun markReachable() {
        unreachableAt = null
    }

    @Synchronized
    fun isUnreachable(): Boolean {
        val at = unreachableAt ?: return false
        if (clock() - at < WINDOW_MS) return true
        unreachableAt = null
        return false
    }
}

/**
 * Process-wide proxy health, published for Settings and for the one-time Home
 * notice. The memory is cleared when the user edits the proxy configuration.
 */
object ProxyHealthMonitor {

    private val memory = ProxyHealthMemory()

    private val _state = MutableStateFlow(ProxyHealth.UNKNOWN)
    val state: StateFlow<ProxyHealth> = _state.asStateFlow()

    /**
     * Requests already in flight on the route we just left fail on their own
     * schedule. They must not condemn the backup for a primary's failure, so
     * a failure reported this soon after a switch is ignored.
     */
    private const val SWITCH_GRACE_MS = 30_000L

    @Volatile
    private var routeChangedAtMs = 0L

    /**
     * A proxy request succeeded.
     *
     * With a backup in use this is also the only moment the app can consider
     * moving back to the primary: after [ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS]
     * the next request is sent there, and a failure simply returns to the
     * backup (see [recordUnreachable] and the callers' retry).
     *
     * @param allowRouteChange false for a stream request that minted a media
     *   URL. That URL is bound to the server that minted it, so the return to
     *   the primary waits for a browse success instead of breaking the track
     *   that just started.
     * @param msSinceSwitch how long the backup has been in use. Callers use the
     *   default; tests pass it so the retry window is exercised without
     *   sleeping or polling.
     */
    @Synchronized
    fun recordSuccess(
        allowRouteChange: Boolean = true,
        msSinceSwitch: Long = YouTubeProxy.millisSinceRouteChange(),
    ) {
        val config = YouTubeProxy.config
        if (allowRouteChange && config.backupUsable &&
            YouTubeProxy.activeRoute.value == ProxyRoute.BACKUP
        ) {
            val due = ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = msSinceSwitch,
            )
            if (due == ProxyRoute.PRIMARY) {
                YouTubeProxy.switchRoute(ProxyRoute.PRIMARY, "backup answered")
            }
        }
        memory.markReachable()
        _state.value = ProxyHealth.OK
    }

    /**
     * A proxy request failed in a way that says the route is dead.
     *
     * With a backup configured the first such failure moves traffic there
     * instead of bypassing the proxy: the backup exists exactly so a lost
     * free server does not cost the listener their music. Only when the
     * backup is also failing does the short direct bypass start, and with no
     * backup the old behaviour is unchanged.
     *
     * @param reason what asked for the proxy; logged with the switch, never
     *   with a server address.
     */
    @Synchronized
    fun recordUnreachable(reason: String = "unreachable") {
        val config = YouTubeProxy.config
        val now = System.currentTimeMillis()
        val target = ProxyRoutePolicy.nextRouteAfterFailure(hasBackup = config.backupUsable)
        // While the proxy is already known down this is just another request
        // noticing it: do not move the route back and forth on every one.
        if (target == ProxyRoute.BACKUP && !memory.isUnreachable() &&
            YouTubeProxy.activeRoute.value == ProxyRoute.PRIMARY
        ) {
            YouTubeProxy.switchRoute(target, reason)
            routeChangedAtMs = now
            memory.markReachable()
            _state.value = ProxyHealth.UNKNOWN
            return
        }
        if (YouTubeProxy.activeRoute.value == ProxyRoute.BACKUP &&
            config.backupUsable &&
            now - routeChangedAtMs < SWITCH_GRACE_MS
        ) {
            return
        }
        memory.markUnreachable()
        _state.value = ProxyHealth.UNREACHABLE
    }

    /**
     * The backup attempt failed after a primary failure moved traffic there.
     *
     * Unlike [recordUnreachable] this is never suppressed by the switch grace:
     * the caller knows the failed request went to the backup, so both
     * configured servers are down and the direct bypass is what is left.
     */
    @Synchronized
    fun recordBackupUnreachable() {
        memory.markUnreachable()
        _state.value = ProxyHealth.UNREACHABLE
        // Both servers are down. Prefer the primary again for the attempt that
        // follows the bypass window: it is the one most likely to come back,
        // and this keeps the recovery attempt on a request event rather than a
        // timer. Nothing is retried until then because the memory is set.
        if (YouTubeProxy.activeRoute.value == ProxyRoute.BACKUP) {
            YouTubeProxy.switchRoute(ProxyRoute.PRIMARY, "backup failed")
        }
    }

    fun clear() {
        memory.markReachable()
        _state.value = ProxyHealth.UNKNOWN
    }

    /** Whether a proxy failure was recorded recently, so the route is bypassed. */
    fun isMarkedUnreachable(): Boolean = memory.isUnreachable()

    /** Whether the next proxy request should be sent directly instead. */
    fun shouldBypassProxy(): Boolean {
        val unreachable = memory.isUnreachable()
        if (!unreachable && _state.value == ProxyHealth.UNREACHABLE) {
            _state.value = ProxyHealth.UNKNOWN
        }
        return unreachable
    }
}
