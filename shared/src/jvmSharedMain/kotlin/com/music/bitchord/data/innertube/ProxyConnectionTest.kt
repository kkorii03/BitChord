package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyFailure
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
 * keep serving youtube.com until the next re-check.
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

    suspend fun run(allowBackupRetry: Boolean = true): Result {
        val proxyConfigured = YouTubeProxy.config.usable
        // A manual test is the listener asking again right now, so the
        // five-minute "unreachable" memory must not decide the answer before
        // the request is sent (it would otherwise be bypassed and fail with no
        // content). Forget it; a failed attempt below records it again.
        if (proxyConfigured) ProxyHealthMonitor.clear()
        return try {
            // Anonymous on purpose: this asks whether the route can reach Music at
            // all, so it must not depend on (or disturb) the account's gate verdict
            // before the answer is in.
            //
            // No direct failover here: a pass must mean the proxy answered, not
            // that the request quietly went around it.
            val home = Innertube.browseAnonymousThroughProxyOnly("FEmusic_home")
            // The request came back, so the route works however the gate reads.
            if (proxyConfigured) ProxyHealthMonitor.recordSuccess()
            val shelves = MusicGateFallback.musicHomeShelfCount(home)
            when {
                MusicGateFallback.isGatedHome(home) -> {
                    MusicGateFallback.markGated("proxy connection test")
                    Result.Gated
                }
                shelves > 0 -> {
                    if (proxyConfigured) MusicGateFallback.markOpen()
                    Result.Ok(shelves)
                }
                else -> Result.Failed(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (proxyConfigured && ProxyFailure.isProxyDown(e)) {
                if (!allowBackupRetry) {
                    // Both configured servers have now refused this test.
                    ProxyHealthMonitor.recordBackupUnreachable()
                } else {
                    // Recorded first: with a backup this is what moves the
                    // route there, and the listener's one tap then tests the
                    // server that will actually answer.
                    ProxyHealthMonitor.recordUnreachable("connection test")
                    if (YouTubeProxy.usingBackup && !ProxyHealthMonitor.isMarkedUnreachable()) {
                        return run(allowBackupRetry = false)
                    }
                }
                // Keep the short bypass so other requests do not each wait for
                // the same timeout.
            }
            Result.Failed(e.message)
        }
    }
}
