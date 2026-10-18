package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decision that keeps searches out of the listener's YouTube history.
 *
 * Every line of the app's search traffic - typed queries, typeahead, the
 * original behind a cover, a song's other-language name - ends at the same
 * request builder, so the guarantee is only as good as these pure decisions:
 * an account is never named, and no credential is ever computed, for a search
 * or suggestion endpoint.
 */
class InnertubeSearchPrivacyTest {

    private val cookie = "SAPISID=secret; SID=session"
    private val pageId = "113355"
    private val authorization = "SAPISIDHASH 1700000000_deadbeef"

    @Test
    fun `search and suggestions are anonymous endpoints`() {
        assertTrue(Innertube.isAnonymousRequest("search"))
        assertTrue(Innertube.isAnonymousRequest("music/get_search_suggestions"))
    }

    @Test
    fun `everything that needs the account is not anonymous`() {
        listOf(
            "browse",
            "next",
            "player",
            "account/account_menu",
            "account/accounts_list",
            "like/like",
            "playlist/create",
            "browse/edit_playlist",
        ).forEach { assertFalse(Innertube.isAnonymousRequest(it), it) }
    }

    @Test
    fun `search carries no account headers even when signed in`() {
        var hashed = false
        val headers = Innertube.accountHeadersFor(
            endpoint = "search",
            cookie = cookie,
            authUser = "0",
            pageId = pageId,
            authorization = {
                hashed = true
                authorization
            },
        )

        assertEquals(emptyMap<String, String>(), headers)
        // Not merely absent from the map: the credential was never computed.
        assertFalse(hashed)
    }

    @Test
    fun `suggestions carry no account headers even when signed in`() {
        val headers = Innertube.accountHeadersFor(
            endpoint = "music/get_search_suggestions",
            cookie = cookie,
            authUser = "2",
            pageId = pageId,
            authorization = { authorization },
        )

        assertEquals(emptyMap<String, String>(), headers)
    }

    @Test
    fun `search never borrows the signed-in visitor id`() {
        assertEquals("anon", Innertube.visitorIdFor("search", "session-bound", "anon"))
        assertEquals("anon", Innertube.visitorIdFor("music/get_search_suggestions", "session-bound", "anon"))
        assertEquals("session-bound", Innertube.visitorIdFor("browse", "session-bound", "anon"))
    }

    @Test
    fun `search never names the account as onBehalfOfUser`() {
        assertNull(Innertube.dataSyncIdForRequest("search", "SESSION_B"))
        assertNull(Innertube.dataSyncIdForRequest("music/get_search_suggestions", "SESSION_B"))
        assertEquals("SESSION_B", Innertube.dataSyncIdForRequest("browse", "SESSION_B"))
    }

    @Test
    fun `library requests keep the session headers`() {
        val headers = Innertube.accountHeadersFor(
            endpoint = "browse",
            cookie = cookie,
            authUser = "2",
            pageId = pageId,
            authorization = { authorization },
        )

        assertEquals(cookie, headers["Cookie"])
        assertEquals("2", headers["X-Goog-AuthUser"])
        assertEquals(pageId, headers["X-Goog-PageId"])
        assertEquals(authorization, headers["Authorization"])
    }

    @Test
    fun `a signed-out search is anonymous for the same reason`() {
        var hashed = false
        val headers = Innertube.accountHeadersFor(
            endpoint = "browse",
            cookie = null,
            authUser = "0",
            pageId = null,
            authorization = {
                hashed = true
                authorization
            },
        )

        assertEquals(emptyMap<String, String>(), headers)
        assertFalse(hashed)
    }
}

