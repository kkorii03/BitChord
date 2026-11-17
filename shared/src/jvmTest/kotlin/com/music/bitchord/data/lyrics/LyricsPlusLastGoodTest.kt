package com.music.bitchord.data.lyrics

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * L14: with a mirror known to answer, [LyricsPlus.lyrics] asks it alone first
 * instead of starting all six mirrors on every lookup.
 */
class LyricsPlusLastGoodTest {

    private val body = """{"lyrics":[{"time":1000,"duration":2000,"text":"la la"}]}"""
    private val savedGet = LyricsPlus.httpGet
    private val savedAlone = LyricsPlus.aloneMs
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val good = "https://lyricsplus.binimum.org"
    private val other = "https://lyricsplus.atomix.one"

    private fun host(url: String) = url.substringBefore("/v2/")

    private fun answer(replies: Map<String, Pair<Long, String?>>) {
        LyricsPlus.httpGet = { url ->
            val mirror = host(url)
            asked += mirror
            val (wait, reply) = replies[mirror] ?: (0L to null)
            if (wait > 0) delay(wait)
            reply
        }
    }

    @AfterTest
    fun restore() {
        LyricsPlus.httpGet = savedGet
        LyricsPlus.aloneMs = savedAlone
        LyricsPlus.lastGood.set(null)
    }

    @Test
    fun aKnownGoodMirrorThatAnswersIsTheOnlyRequest() = runBlocking<Unit> {
        LyricsPlus.lastGood.set(good)
        // Today all six start at once: the five others answer nothing at once,
        // the good one after 100 ms, and six requests have been made.
        answer(mapOf(good to (100L to body)))
        assertNotNull(LyricsPlus.lyrics("Song", "Artist", 200_000))
        assertEquals(listOf(good), asked.toList())
    }

    @Test
    fun aKnownMirrorWithNothingHandsOverToTheRace() = runBlocking<Unit> {
        LyricsPlus.lastGood.set(good)
        answer(mapOf(other to (0L to body)))
        assertNotNull(LyricsPlus.lyrics("Song", "Artist", 200_000))
        assertEquals(good, asked.first())
        assertEquals(other, LyricsPlus.lastGood.get())
    }

    @Test
    fun aSlowKnownMirrorStillWinsWhenTheOthersHaveNothing() = runBlocking<Unit> {
        LyricsPlus.lastGood.set(good)
        LyricsPlus.aloneMs = 50
        answer(mapOf(good to (400L to body)))
        assertNotNull(LyricsPlus.lyrics("Song", "Artist", 200_000))
        assertEquals(6, asked.size)
        assertEquals(good, LyricsPlus.lastGood.get())
    }

    @Test
    fun withNoKnownMirrorAllAreRacedAndTheWinnerRemembered() = runBlocking<Unit> {
        answer(mapOf(other to (100L to body)))
        assertNotNull(LyricsPlus.lyrics("Song", "Artist", 200_000))
        assertEquals(6, asked.size)
        assertEquals(other, LyricsPlus.lastGood.get())
    }
}
