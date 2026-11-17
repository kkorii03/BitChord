package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Revert to original" reopens YouTube's upload at the same moment of the
 * song, not at the same number (sol-pairing Q3): a verified copy plays at
 * `offset + rate x youtube`, so its raw playhead is that much off YouTube's.
 */
class HqRevertPositionTest {

    @Test
    fun `a verified copy goes back through its own verdict`() {
        // The lossless file starts 2.4 s later than the upload: 62.4 s on it is 60 s on YouTube.
        assertEquals(60_000L, HqSwapPolicy.revertPositionMs(62_400L, 2_400L, 1.0, substitute = true))
        // A copy running 1 % fast: 101 s on it is 100 s on YouTube.
        assertEquals(100_000L, HqSwapPolicy.revertPositionMs(101_000L, 0L, 1.01, substitute = true))
        // Same arithmetic as the swap into the copy, inverted.
        val there = HqSwapPolicy.targetPositionMs(75_000L, 2_400L, 1.0)
        assertEquals(75_000L, HqSwapPolicy.revertPositionMs(there, 2_400L, 1.0, substitute = true))
    }

    @Test
    fun `YouTube already playing keeps its position and an unverified substitute is unknown`() {
        assertEquals(62_400L, HqSwapPolicy.revertPositionMs(62_400L, null, null, substitute = false))
        assertNull(HqSwapPolicy.revertPositionMs(62_400L, null, null, substitute = true))
    }

    // C4c-3: a failing HQ copy that falls back to YouTube mid-song lands on the
    // same moment, not the same number.
    @Test
    fun `a mid-song fallback to YouTube goes back through the failing copy's verdict`() {
        assertEquals(118_200L, HqSwapPolicy.fallbackSeekMs(120_000L, 1_800L, 1.0))
        assertEquals(121_800L, HqSwapPolicy.fallbackSeekMs(120_000L, -1_800L, 1.0))
        assertEquals(120_000L, HqSwapPolicy.fallbackSeekMs(120_000L, null, null))
        assertEquals(0L, HqSwapPolicy.fallbackSeekMs(1_000L, 1_800L, 1.0))
        // "From the top" (the wrong-length exit) stays at the top.
        assertEquals(0L, HqSwapPolicy.fallbackSeekMs(0L, -1_800L, 1.0))
    }

    // NR-1: a 429/503 resume on another verified copy goes through both verdicts.
    @Test
    fun `a rate-limited resume maps the failed copy's clock onto the next one`() {
        // Failed copy +3 s, resumed on YouTube: 60 s on it is 57 s on YouTube.
        assertEquals(57_000L, HqSwapPolicy.resumeAtMs(60_000L, 3_000L, 1.0, null, null))
        // Failed copy +3 s, resumed on a copy +1.5 s: 57 s on YouTube is 58.5 s there.
        assertEquals(58_500L, HqSwapPolicy.resumeAtMs(60_000L, 3_000L, 1.0, 1_500L, 1.0))
        // No stored verdict for the failed copy: its clock is taken as YouTube's.
        assertEquals(61_500L, HqSwapPolicy.resumeAtMs(60_000L, null, null, 1_500L, 1.0))
        assertEquals(60_000L, HqSwapPolicy.resumeAtMs(60_000L, null, null, null, null))
    }

    @Test
    fun `both YouTube fallback exits seek the mapped position`() {
        val service = source("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        // NR-1: both 429/503 exits (another verified copy, and YouTube).
        val rateLimited = service
            .substringAfter("private fun resumeRateLimited(")
            .substringBefore("private fun fallbackFailedAlternativeToYouTube(")
        assertEquals(2, Regex("""HqSwapPolicy\.resumeAtMs\(""").findAll(rateLimited).count())
        assertFalse(
            "a 429/503 resume must not seek to the failed copy's raw position",
            "seekTo(live.currentMediaItemIndex, position)" in rateLimited,
        )
        val replace = service
            .substringAfter("private fun replaceWithYouTube(")
            .substringBefore("Swaps a track whose downloaded file")
        assertFalse(
            "the failed-stream exit must not seek YouTube to the copy's raw position",
            "seekTo(live.currentMediaItemIndex, position)" in replace,
        )
        val read = replace.indexOf("currentPlayingVerification(mediaId)")
        assertTrue("the verdict is read before the forgets clear it", read in 0 until replace.indexOf("QualityUpgrade.forget(mediaId)"))
        assertTrue(replace.indexOf("fallbackSeekMs(") in 0 until replace.indexOf("QualityUpgrade.forget(mediaId)"))
    }

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText().replace("\r\n", "\n")
    }
}
