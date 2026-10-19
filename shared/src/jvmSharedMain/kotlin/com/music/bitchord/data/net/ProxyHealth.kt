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

    fun recordSuccess() {
        memory.markReachable()
        _state.value = ProxyHealth.OK
    }

    fun recordUnreachable() {
        memory.markUnreachable()
        _state.value = ProxyHealth.UNREACHABLE
    }

    fun clear() {
        memory.markReachable()
        _state.value = ProxyHealth.UNKNOWN
    }

    /** Whether the next proxy request should be sent directly instead. */
    fun shouldBypassProxy(): Boolean {
        val unreachable = memory.isUnreachable()
        if (!unreachable && _state.value == ProxyHealth.UNREACHABLE) {
            _state.value = ProxyHealth.UNKNOWN
        }
        return unreachable
    }
}
