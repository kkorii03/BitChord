package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bugs is a two-step provider: a timed copy that the player prefers, and a
 * plain copy used only when the timed one has nothing. Asking for both used to
 * add a request per lookup, and a refusal from that extra request discarded
 * timed lyrics that had already succeeded.
 */
class BugsLyricsRequestTest {

    private val searchHtml = """<tr class="track" trackId="42" rowType="track">"""
    private val trackJson = """{"track":{"track_title":"Song","artist_nm":"Artist","len":"3:20"}}"""
    private val timedJson = """{"lyrics":"0.5|First line＃4.0|Second line"}"""
    private val plainJson = """{"lyrics":"First line\nSecond line"}"""

    @Test
    fun timedLyricsDoNotAlsoRequestThePlainCopy() = runBlocking {
        val requested = mutableListOf<String>()
        val lines = BugsLyrics.lyrics("Song", "Artist", 200_000L) { url ->
            requested += url
            when {
                "/search/track" in url -> searchHtml
                "/player/track/" in url -> trackJson
                "/player/lyrics/T/" in url -> timedJson
                "/player/lyrics/N/" in url -> plainJson
                else -> null
            }
        }
        assertNotNull(lines)
        assertEquals(listOf(500L, 4_000L), lines.map { it.timeMs })
        assertTrue(
            requested.none { "/player/lyrics/N/" in it },
            "the plain copy must not be fetched when timed lyrics already answered",
        )
    }

    @Test
    fun emptyTimedLyricsStillFallBackToThePlainCopy() = runBlocking {
        val plainRequests = mutableListOf<String>()
        val lines = BugsLyrics.lyrics("Song", "Artist", 200_000L) { url ->
            when {
                "/search/track" in url -> searchHtml
                "/player/track/" in url -> trackJson
                "/player/lyrics/T/" in url -> """{"lyrics":""}"""
                "/player/lyrics/N/" in url -> {
                    plainRequests += url
                    plainJson
                }
                else -> null
            }
        }
        assertNotNull(lines)
        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        assertEquals(1, plainRequests.size)
    }
}
