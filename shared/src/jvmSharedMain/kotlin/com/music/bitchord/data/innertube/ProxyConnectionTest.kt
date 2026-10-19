package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.YouTubeProxy
import kotlinx.coroutines.CancellationException

/**
 * The Settings > Network proxy "연결 테스트" action.
 *
 * One anonymous music.youtube.com Home browse, through whatever the proxy
 * settings now are. Anonymous keeps the check cheap and independent of the
 * account's gate verdict; a pass still proves the proxy can reach Music and
 * not merely that a TCP connection could be opened.
 *
 * A pass also clears a stored GATED verdict ([MusicGateFallback.markOpen]):
 * the gate is a property of the network the request left from, and the proxy
 * is how the listener changed that network. Without this the fallback would
 * keep serving youtube.com until the next daily re-check.
 */
object ProxyConnectionTest {

    sealed interface Result {
        /** YouTube Music answered with [shelves] shelves on Home. */
        data class Ok(val shelves: Int) : Result

        /** YouTube Music answered, but with the no-Premium gate. */
        data object Gated : Result

        /** No answer, or an answer that was neither usable content nor the gate. */
        data class Failed(val message: String?) : Result
    }

    suspend fun run(): Result = try {
        // Anonymous on purpose: this asks whether the route can reach Music at
        // all, so it must not depend on (or disturb) the account's gate verdict
        // before the answer is in.
        //
        // No direct failover here: a pass must mean the proxy answered, not
        // that the request quietly went around it.
        if (YouTubeProxy.config.usable && ProxyHealthMonitor.shouldBypassProxy()) {
            Result.Failed(null)
        } else {
            val home = Innertube.browseAnonymousThroughProxyOnly("FEmusic_home")
            val shelves = MusicGateFallback.musicHomeShelfCount(home)
            when {
                MusicGateFallback.isGatedHome(home) -> {
                    MusicGateFallback.markGated("proxy connection test")
                    Result.Gated
                }
                shelves > 0 -> {
                    if (YouTubeProxy.config.usable) MusicGateFallback.markOpen()
                    Result.Ok(shelves)
                }
                else -> Result.Failed(null)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Failed(e.message)
    }
}
