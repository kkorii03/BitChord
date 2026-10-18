package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.YouTubeProxy
import kotlinx.coroutines.CancellationException

/**
 * The Settings > Network proxy "연결 테스트" action.
 *
 * One music.youtube.com Home browse, through whatever the proxy settings now
 * are. It is deliberately the same request the app's Home load makes, so a
 * pass means the normal YouTube Music path is reachable and not merely that a
 * TCP connection could be opened.
 *
 * A pass also clears a stored GATED verdict ([MusicGateFallback.markOpen]):
 * the gate is a property of the network the request left from, and the proxy
 * is how the listener changed that network. Without this the fallback would
 * keep serving youtube.com until the next daily re-check.
 */
object ProxyConnectionTest {

    sealed interface Result {
        /** YouTube Music answered with shelves. */
        data object Ok : Result

        /** YouTube Music answered, but with the no-Premium gate. */
        data object Gated : Result

        /** No answer, or an answer that was neither usable content nor the gate. */
        data class Failed(val message: String?) : Result
    }

    suspend fun run(): Result = try {
        val home = Innertube.browse("FEmusic_home")
        when {
            MusicGateFallback.isGatedHome(home) -> {
                MusicGateFallback.markGated("proxy connection test")
                Result.Gated
            }
            MusicGateFallback.musicHomeShelfCount(home) > 0 -> {
                if (YouTubeProxy.config.usable) MusicGateFallback.markOpen()
                Result.Ok
            }
            else -> Result.Failed(null)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Failed(e.message)
    }
}
