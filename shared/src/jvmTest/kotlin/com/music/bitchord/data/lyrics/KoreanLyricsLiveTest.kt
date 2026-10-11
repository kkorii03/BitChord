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
