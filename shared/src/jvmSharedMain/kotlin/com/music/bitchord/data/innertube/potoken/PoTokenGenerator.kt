package com.music.bitchord.data.innertube.potoken

import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * One loaded BotGuard: a browser page that has run the challenge, holds an
 * integrity token, and mints PoTokens from it until it expires or dies. The
 * phone's is an Android WebView, the desktop's a JavaFX one; both drive the
 * same [PO_TOKEN_HTML].
 */
interface PoTokenMinter {
    /** Past the integrity token's lifetime, less a margin. */
    val isExpired: Boolean

    /** The page crashed, threw after start-up, or a mint timed out. */
    val isDead: Boolean

    /** A PoToken bound to [identifier] — a visitor data or a video id. */
    suspend fun generatePoToken(identifier: String): String

    /** Tears the page down, on whatever thread the browser needs. */
    fun close()
}

/**
 * BotGuard PoTokens for YouTube's web clients, minted in a browser page.
 *
 * Keeps one loaded page per visitor id: the session's streaming token is
 * minted when the page is made, and each track's player token is minted from
 * the same page. A page that has expired, died or belongs to another visitor
 * is replaced. A page cannot simply be re-minted for a new visitor: the
 * BotGuard integrity token behind it was issued to the visitor that created
 * it, and a streaming token minted on the old page for the new visitor
 * produced URLs that served the opening of a track and then answered 403 to
 * every real range. The cold-start prewarm still pays the Create/GenerateIT
 * round trips; it just cannot carry them across a visitor change.
 *
 * @param createMinter loads a new BotGuard page; the platform's browser.
 * @param available whether this platform can host a page at all.
 */
class PoTokenGenerator(
    private val createMinter: suspend () -> PoTokenMinter,
    private val available: () -> Boolean = { true },
) {
    private val TAG = "PoTokenGenerator"

    private var webViewBadImpl = false // whether the platform's browser turned out to be unusable

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenMinter? = null

    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        val supported = available()
        if (!supported || webViewBadImpl) {
            TrackLog.d(TAG, "WebView not available: supported=$supported, badImpl=$webViewBadImpl")
            return null
        }
        return try {
            withTimeout(POTOKEN_TIMEOUT_MS) {
                getWebClientPoToken(videoId, sessionId, forceRecreate = false)
            }
        } catch (e: TimeoutCancellationException) {
            TrackLog.w(TAG, "poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms; proceeding without PoToken")
            clearGenerator()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadWebViewException) {
            TrackLog.e(TAG, "Could not obtain PO token because WebView is unavailable")
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            TrackLog.e(TAG, "PO token generation failed type=${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }

    /**
     * Loads the BotGuard page ahead of the first track, so the play path never
     * pays the page's Create/GenerateIT round trips (about half a second cold).
     *
     * Called once when the app starts in the foreground, and once more when a
     * sign-in changes the session; never on a timer.
     */
    suspend fun prewarm(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            TrackLog.d(TAG, "potoken: prewarm skipped (no visitor id)")
            return
        }
        if (!available() || webViewBadImpl) {
            TrackLog.d(TAG, "potoken: prewarm skipped (WebView unavailable)")
            return
        }
        val start = System.nanoTime()
        try {
            val token = getWebClientPoToken(PREWARM_VIDEO_ID, sessionId)
            val took = (System.nanoTime() - start) / 1_000_000
            if (token == null) {
                TrackLog.w(TAG, "potoken: prewarm failed after ${took}ms")
            } else {
                TrackLog.d(TAG, "potoken: prewarmed in ${took}ms")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val took = (System.nanoTime() - start) / 1_000_000
            TrackLog.w(TAG, "potoken: prewarm failed type=${e::class.simpleName ?: "unknown"} after ${took}ms")
        }
    }

    suspend fun close() {
        clearGenerator()
    }

    private suspend fun clearGenerator() {
        webPoTokenGenLock.withLock {
            try {
                webPoTokenGenerator?.close()
            } catch (error: Exception) {
                TrackLog.e(TAG, "PO token WebView cleanup failed type=${error::class.simpleName ?: "unknown"}")
            }
            webPoTokenGenerator = null
            webPoTokenStreamingPot = null
            webPoTokenSessionId = null
        }
    }

    internal companion object {
        /**
         * Longer than the page's own worst case, not equal to it.
         *
         * The desktop and phone browsers each allow forty-five seconds to
         * create the page (BotGuard Create/GenerateIT plus JavaScript), and a
         * live page is then allowed fifteen seconds for its first mint. An
         * outer limit of forty-five seconds sat exactly on the init budget, so
         * a page that took its full allowance — or had to be recreated — was
         * cancelled from outside before a PoToken could ever be minted. The
         * outer limit is only a backstop for a page that ignores its own
         * timeouts; it must not decide the ordinary cold-start race.
         */
        const val POTOKEN_TIMEOUT_MS = 75_000L
        const val PREWARM_VIDEO_ID = "dQw4w9WgXcQ"
    }

    private class LoadedPage(
        val minter: PoTokenMinter,
        val streamingPot: String,
        val recreated: Boolean,
    )

    /**
     * Builds the page in place, or replaces it when there is none or it can no
     * longer mint. Must be called under [webPoTokenGenLock].
     */
    private suspend fun createPage(sessionId: String, forceRecreate: Boolean, reason: String): LoadedPage {
        TrackLog.d(TAG, "potoken: creating page (forceRecreate=$forceRecreate, reason=$reason)")
        webPoTokenGenerator?.close()
        webPoTokenGenerator = null
        webPoTokenStreamingPot = null
        webPoTokenSessionId = null
        val newGenerator = createMinter()
        val newStreamingPot = try {
            newGenerator.generatePoToken(sessionId)
        } catch (t: Throwable) {
            runCatching { newGenerator.close() }
            throw t
        }
        webPoTokenGenerator = newGenerator
        webPoTokenStreamingPot = newStreamingPot
        webPoTokenSessionId = sessionId
        return LoadedPage(newGenerator, newStreamingPot, true)
    }

    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        val mintStart = System.nanoTime()
        val page =
            webPoTokenGenLock.withLock {
                val existing = webPoTokenGenerator
                val action = poTokenPageAction(
                    forceRecreate = forceRecreate,
                    hasPage = existing != null,
                    expired = existing?.isExpired == true,
                    dead = existing?.isDead == true,
                    sessionChanged = webPoTokenSessionId != sessionId,
                )
                when (action) {
                    PoTokenPageAction.Create -> createPage(
                        sessionId = sessionId,
                        forceRecreate = forceRecreate,
                        reason = poTokenPageReason(
                            forceRecreate = forceRecreate,
                            hasPage = existing != null,
                            expired = existing?.isExpired == true,
                            dead = existing?.isDead == true,
                            sessionChanged = webPoTokenSessionId != sessionId,
                        ),
                    )

                    PoTokenPageAction.Keep ->
                        LoadedPage(existing!!, webPoTokenStreamingPot!!, false)
                }
            }
        val playerPot = try {
            page.minter.generatePoToken(videoId)
        } catch (cancelled: CancellationException) {
            // The caller moved on (the queue advanced, a search was replaced).
            // Treating this as a broken page rebuilt the WebView and paid for
            // another BotGuard load after the work had already been abandoned.
            throw cancelled
        } catch (throwable: Throwable) {
            if (page.recreated) {
                throw throwable
            } else {
                TrackLog.e(TAG, "PO-token generation failed; recreating WebView")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        }
        // One line per mint, in place of the browser page's console chatter.
        TrackLog.d(
            TAG,
            "potoken: minted in ${(System.nanoTime() - mintStart) / 1_000_000}ms" +
                (if (page.recreated) " (new page)" else ""),
        )
        return PoTokenResult(
            playerRequestPoToken = page.streamingPot,
            streamingDataPoToken = playerPot,
        )
    }

    /** This generator as InnerTubeX's token provider, for WEB_REMIX and the other BotGuard clients. */
    fun asTokenProvider(): TokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): com.metrolist.innertubex.extraction.PoTokenResult? =
            getWebClientPoToken(videoId, visitorData)?.let { token ->
                com.metrolist.innertubex.extraction.PoTokenResult(
                    playerRequestToken = token.playerRequestPoToken,
                    streamingDataToken = token.streamingDataPoToken,
                    visitorData = visitorData,
                )
            }

        override suspend fun close() {
            this@PoTokenGenerator.close()
        }
    }
}

internal enum class PoTokenPageAction { Create, Keep }

/** What to do with the loaded page for this request. Pure so it can be tested off-device. */
internal fun poTokenPageAction(
    forceRecreate: Boolean,
    hasPage: Boolean,
    expired: Boolean,
    dead: Boolean,
    sessionChanged: Boolean,
): PoTokenPageAction = when {
    forceRecreate || !hasPage || expired || dead -> PoTokenPageAction.Create
    // The integrity token is bound to the visitor that made the page. Re-minting
    // the streaming token on that page for a different visitor produced URLs
    // that 403'd every range after the opening, so a visitor change rebuilds.
    sessionChanged -> PoTokenPageAction.Create
    else -> PoTokenPageAction.Keep
}

private fun poTokenPageReason(
    forceRecreate: Boolean,
    hasPage: Boolean,
    expired: Boolean,
    dead: Boolean,
    sessionChanged: Boolean,
): String = when {
    forceRecreate -> "forced"
    !hasPage -> "none"
    expired -> "expired"
    dead -> "dead"
    sessionChanged -> "session"
    else -> "none"
}
