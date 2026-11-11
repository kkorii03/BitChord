package com.music.bitchord.data.net

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.ko.KoInnertube
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A stored cookie Google no longer accepts (phone 11-11, Galaxy Z Fold, KR):
 * the music shell said LOGGED_IN=false, yet every youtube.com request still
 * carried the cookie, its SAPISID hashes and the chosen channel, so even the
 * public RD mix was answered 401 UNAUTHENTICATED and AutoPlay never filled.
 * Once that login is known dead, public requests must leave without it.
 */
class DeadSessionAnonymousTest {

    private val deadCookie = "SAPISID=dead-test-only; SID=dead-test-only"

    private fun <T> withCookie(cookie: String?, block: () -> T): T {
        val previous = Innertube.cookie
        Innertube.cookie = cookie
        try {
            return block()
        } finally {
            Innertube.selectChannel(null, null)
            Innertube.cookie = previous
        }
    }

    @Test
    fun `a signed public youtube request answered 401 is retried without the session`() {
        assertTrue(KoInnertube.retriesWithoutSession(401, false, deadCookie, "next"))
        assertTrue(KoInnertube.retriesWithoutSession(401, false, deadCookie, "browse"))
        assertTrue(KoInnertube.retriesWithoutSession(401, false, deadCookie, "player"))
        // Not a dead-login answer, already anonymous, or no login at all.
        assertFalse(KoInnertube.retriesWithoutSession(403, false, deadCookie, "next"))
        assertFalse(KoInnertube.retriesWithoutSession(null, false, deadCookie, "next"))
        assertFalse(KoInnertube.retriesWithoutSession(401, true, deadCookie, "next"))
        assertFalse(KoInnertube.retriesWithoutSession(401, false, null, "next"))
        // Account endpoints need the account; a retry without it is meaningless.
        assertFalse(KoInnertube.retriesWithoutSession(401, false, deadCookie, "like/like"))
        assertFalse(KoInnertube.retriesWithoutSession(401, false, deadCookie, "account/account_menu"))
    }

    @Test
    fun `a rejected login sends public requests anonymously and keeps account ones signed`() = withCookie(deadCookie) {
        Innertube.selectChannel(pageId = "PAGE_743132", dataSyncId = "DS_743132")
        assertFalse(Innertube.isAnonymousYouTubeRequest("next", anonymous = false))

        KoInnertube.markSessionRejected(deadCookie)

        listOf("next", "browse", "player").forEach {
            assertTrue(Innertube.isAnonymousYouTubeRequest(it, anonymous = false), it)
        }
        assertTrue(Innertube.musicRequestIsAnonymous("next"))
        assertTrue(Innertube.musicRequestIsAnonymous("browse", "FEmusic_home"))
        // An anonymous request computes no Cookie, Authorization or page id.
        var hashed = false
        val headers = Innertube.accountHeadersFor(
            endpoint = "next",
            cookie = deadCookie,
            authUser = Innertube.authUserFor(null),
            pageId = Innertube.pageIdFor(null),
            authorization = { hashed = true; "SAPISIDHASH x" },
            anonymous = Innertube.isAnonymousYouTubeRequest("next", anonymous = false),
        )
        assertEquals(emptyMap<String, String>(), headers)
        assertFalse(hashed)

        // Writes and the account's own menus still go as the account.
        assertFalse(Innertube.isAnonymousYouTubeRequest("like/like", anonymous = false))
        assertFalse(Innertube.isAnonymousYouTubeRequest("account/account_menu", anonymous = false))
        assertFalse(Innertube.musicRequestIsAnonymous("account/accounts_list"))
        // The gate re-check still asks signed, so a revived login is noticed.
        assertFalse(Innertube.musicRequestIsAnonymous("browse", "FEmusic_home", forceSigned = true))
        // The stored cookie is never cleared by the verdict.
        assertEquals(deadCookie, Innertube.cookie)
    }

    @Test
    fun `signing in again or choosing another channel is asked signed again`() = withCookie(deadCookie) {
        KoInnertube.markSessionRejected(deadCookie)
        assertTrue(Innertube.isAnonymousYouTubeRequest("next", anonymous = false))

        Innertube.selectChannel(pageId = "OTHER_PAGE", dataSyncId = "OTHER_DS")
        assertFalse(Innertube.isAnonymousYouTubeRequest("next", anonymous = false))
        Innertube.selectChannel(null, null)
        assertTrue(Innertube.isAnonymousYouTubeRequest("next", anonymous = false))

        Innertube.cookie = "SAPISID=fresh-test-only; SID=fresh-test-only"
        assertFalse(Innertube.isAnonymousYouTubeRequest("next", anonymous = false))
        assertFalse(Innertube.musicRequestIsAnonymous("next"))
    }

    @Test
    fun `a verdict about a login that already left is not recorded`() = withCookie("SAPISID=current-test-only") {
        KoInnertube.markSessionRejected(deadCookie)
        assertFalse(Innertube.isAnonymousYouTubeRequest("next", anonymous = false))
        KoInnertube.markSessionRejected(null)
        assertFalse(Innertube.isAnonymousYouTubeRequest("next", anonymous = false))
    }
}
