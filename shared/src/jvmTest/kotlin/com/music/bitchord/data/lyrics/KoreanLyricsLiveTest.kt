package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asks the real services once. Runs only with LIVE_KR_LYRICS=1, so ordinary
 * test runs stay offline.
 */
class KoreanLyricsLiveTest {
    private val live = System.getenv("LIVE_KR_LYRICS") == "1"

    @Test
    fun vibeFindsTheEpitoneCarousel() {
        if (!live) return
        val lines = assertNotNull(runBlocking { VibeLyrics.lyrics("회전목마", "에피톤 프로젝트", 296_000L) })
        assertTrue(lines.any { it.text.contains("애드벌룬") } && lines.any { it.timeMs > 0 })
    }

    @Test
    fun bugsFindsTheEpitoneCarousel() {
        if (!live) return
        val lines = assertNotNull(runBlocking { BugsLyrics.lyrics("회전목마", "에피톤 프로젝트", 296_000L) })
        assertTrue(lines.any { it.text.contains("애드벌룬") } && lines.any { it.timeMs > 0 })
    }

    @Test
    fun bothFindDonggeo() {
        if (!live) return
        val vibe = assertNotNull(runBlocking { VibeLyrics.lyrics("동거", "선우정아", 259_000L) })
        val bugs = assertNotNull(runBlocking { BugsLyrics.lyrics("동거", "선우정아", 259_000L) })
        assertTrue(vibe.any { it.text.contains("맨발") } && bugs.any { it.text.contains("맨발") })
    }
}

/** Times whole lookups against the real services. LIVE_KR_LYRICS=1 only. */
class LyricsLookupTimingLiveTest {
    private val live = System.getenv("LIVE_KR_LYRICS") == "1"

    private fun timed(label: String, block: () -> LyricsRepository.Result?): LyricsRepository.Result? {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("LOOKUP_MS $label ${ms}ms source=${result?.source} lines=${result?.lines?.size}")
        return result
    }

    @Test
    fun perSourceTimesForAKoreanLineOnlySong() {
        if (!live) return
        val start = System.nanoTime()
        val sources = LyricsSource.offered.toSet() - LyricsSource.PAXSENIX_SPOTIFY - LyricsSource.PAXSENIX_MUSIXMATCH
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        runBlocking {
            LyricsRepository.lyrics(
                "live-carousel-sources", "회전목마", "에피톤 프로젝트", 313_000L,
                sources = sources, prioritizeSyllableSync = true,
                onSourceResult = { source, result ->
                    val ms = (System.nanoTime() - start) / 1_000_000
                    seen += "${source.name}=${ms}${if (result != null) "+" else "-"}"
                },
            )
        }
        println("LOOKUP_MS sources ${seen.joinToString(" ")} TOTAL=${(System.nanoTime() - start) / 1_000_000}")
    }

    @Test
    fun wholeLookupsAndTheMemo() {
        if (!live) return
        val sources = LyricsSource.offered.toSet() - LyricsSource.PAXSENIX_SPOTIFY - LyricsSource.PAXSENIX_MUSIXMATCH
        val songs = listOf(
            Triple("회전목마", "에피톤 프로젝트", 313_000L),
            Triple("밤편지", "아이유", 253_000L),
            Triple("Shape of You", "Ed Sheeran", 234_000L),
        )
        for ((title, artist, ms) in songs) {
            val first = timed("first:$title") {
                runBlocking {
                    LyricsRepository.lyrics("live-$title", title, artist, ms, sources = sources, prioritizeSyllableSync = true)
                }
            }
            assertNotNull(first)
            val again = timed("memo:$title") {
                runBlocking {
                    LyricsRepository.lyrics("live-$title", title, artist, ms, sources = sources, prioritizeSyllableSync = true)
                }
            }
            assertTrue(again === first)
        }
    }
}
