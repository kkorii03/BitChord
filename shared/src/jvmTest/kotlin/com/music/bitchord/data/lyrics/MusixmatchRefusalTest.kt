package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L16: a refused Musixmatch token no longer throws the signing secret away
 * (a search page plus a megabyte of script) on every call, and two refusals
 * in a row stop the requests for a while.
 */
class MusixmatchRefusalTest {

    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var clock = 1_000_000L

    private val page = """<script src="/_next/static/chunks/pages/_app-abc.js"></script>"""
    // "testsecret", base64, reversed — the shape the web client hides it in.
    private val script = """x.from("==AdlJ3YlNHdzVGd".split(""))"""
    private val token = """{"message":{"header":{"status_code":200},"body":{"user_token":"tok"}}}"""
    private val refused = """{"message":{"header":{"status_code":401}}}"""
    private val noRows = """{"message":{"header":{"status_code":200},"body":{"track_list":[]}}}"""

    private fun kind(url: String) = when {
        url.startsWith("https://www.musixmatch.com/search") -> "page"
        "_app-abc.js" in url -> "script"
        "/token.get" in url -> "token"
        "/track.search" in url -> "search"
        else -> "other"
    }

    private fun serve(search: (Int) -> Pair<Int, String?>?) {
        var searches = 0
        Musixmatch.transport = { url, _, _ ->
            val what = kind(url)
            asked += what
            when (what) {
                "page" -> 200 to page
                "script" -> 200 to script
                "token" -> 200 to token
                "search" -> search(searches++)
                else -> 404 to null
            }
        }
    }

    private fun count(kind: String) = asked.count { it == kind }

    @BeforeTest
    fun setUp() {
        Musixmatch.resetForTest()
        Musixmatch.now = { clock }
    }

    @AfterTest
    fun restore() {
        Musixmatch.transport = null
        Musixmatch.now = System::currentTimeMillis
        Musixmatch.resetForTest()
    }

    @Test
    fun twoRefusalsStopTheRequestsInsteadOfReDerivingEveryTime() = runBlocking<Unit> {
        serve { 200 to refused }
        repeat(3) { assertNull(Musixmatch.lyrics("Song", "Artist", 200_000)) }
        // Today: 8 requests on the first call and 5 on each after (search page and
        // script fetched three times). Now: one derivation, two tokens, two searches.
        assertEquals(1, count("page"))
        assertEquals(2, count("token"))
        assertEquals(2, count("search"))
        assertEquals(6, asked.size)
    }

    @Test
    fun theWindowEndsOnItsOwnClock() = runBlocking<Unit> {
        serve { 200 to refused }
        Musixmatch.lyrics("Song", "Artist", 200_000)
        val before = asked.size
        clock += 9 * 60_000L
        Musixmatch.lyrics("Song", "Artist", 200_000)
        assertEquals(before, asked.size)
        clock += 2 * 60_000L
        Musixmatch.lyrics("Song", "Artist", 200_000)
        assertEquals(2, count("page"), "the dropped secret is derived again after the window")
    }

    @Test
    fun noReplyIsNotAVerdict() = runBlocking<Unit> {
        serve { null }
        repeat(2) { assertNull(Musixmatch.lyrics("Song", "Artist", 200_000)) }
        // Today each silent answer clears both credentials and fetches them again.
        assertEquals(1, count("page"))
        assertEquals(1, count("token"))
        assertEquals(2, count("search"))
    }

    @Test
    fun oneRefusalTriesAFreshTokenWithTheSameSecret() = runBlocking<Unit> {
        serve { n -> if (n == 0) 200 to refused else 200 to noRows }
        assertNull(Musixmatch.lyrics("Song", "Artist", 200_000))
        assertEquals(1, count("page"))
        assertEquals(2, count("token"))
        // The good answer cleared the count: one more refusal is not a window.
        serve { n -> if (n == 0) 200 to refused else 200 to noRows }
        Musixmatch.lyrics("Song", "Artist", 200_000)
        assertEquals(1, count("page"))
        assertEquals(4, count("search"))
    }
}
