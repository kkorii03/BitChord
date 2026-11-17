package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L note on PaxSenix's Apple token: a scrape that finds no token is remembered
 * for a while instead of downloading Apple's page again on every lookup, and a
 * token Apple refuses is dropped instead of kept until the app restarts.
 */
class PaxSenixTokenTest {

    private val pages: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var searches = 0
    private var clock = 5_000_000L
    private val savedPageGet = PaxSenix.pageGet
    private val savedSearch = PaxSenix.appleSearch

    private val page = """<script src="/assets/index~abc.js"></script>"""
    private val script = """const t="eyJhbGc.eyJpc3M.sig";"""

    @BeforeTest
    fun setUp() {
        PaxSenix.resetForTest()
        PaxSenix.now = { clock }
    }

    @AfterTest
    fun restore() {
        PaxSenix.pageGet = savedPageGet
        PaxSenix.appleSearch = savedSearch
        PaxSenix.now = System::currentTimeMillis
        PaxSenix.resetForTest()
    }

    private fun servePages(pageBody: String?) {
        PaxSenix.pageGet = { url ->
            pages += url
            if ("index~" in url) script else pageBody
        }
    }

    @Test
    fun aPageWithoutTheTokenIsNotDownloadedOnEveryLookup() = runBlocking<Unit> {
        servePages("<html>a new layout</html>")
        PaxSenix.appleSearch = { _, _ -> searches++; 200 to "{}" }
        repeat(3) { assertNull(PaxSenix.lyrics("Song", "Artist", 200_000)) }
        // Today: the page is fetched on each of the three lookups.
        assertEquals(1, pages.size)
        clock += 31 * 60_000L
        PaxSenix.lyrics("Song", "Artist", 200_000)
        assertEquals(2, pages.size)
        assertEquals(0, searches)
    }

    @Test
    fun aRefusedTokenIsDroppedAndScrapedAgain() = runBlocking<Unit> {
        servePages(page)
        PaxSenix.appleSearch = { _, _ -> searches++; 401 to null }
        repeat(3) { assertNull(PaxSenix.lyrics("Song", "Artist", 200_000)) }
        // Today the first token is kept for good: one scrape (2 pages), 3 searches.
        // Now: refused -> scraped again -> refused again -> no more tries for a while.
        assertEquals(4, pages.size)
        assertEquals(2, searches)
    }

    @Test
    fun aFailedDownloadIsNotAVerdict() = runBlocking<Unit> {
        servePages(null)
        repeat(2) { PaxSenix.lyrics("Song", "Artist", 200_000) }
        assertEquals(2, pages.size)
    }
}
