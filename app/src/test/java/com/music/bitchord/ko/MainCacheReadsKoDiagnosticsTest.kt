package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricsAiGuard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-20 15:40: the main thread froze ~29 s at a manual skip. Media3's SimpleCache
 * is synchronized on itself, and loaders hold that monitor across their disk
 * writes, so any cache read on main can wait behind another thread's IO. The
 * reads below sit on the upgrade swap and lyrics-screen paths; none of them can
 * run without a real player cache, so where they run is pinned on the source,
 * as HqVersionGuardWiringTest does.
 */
class MainCacheReadsKoDiagnosticsTest {

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
    }

    /** Every line that calls [call] runs it inside `withContext(Dispatchers.IO) { ... }`. */
    private fun assertEveryCallOffMain(text: String, call: String) {
        val lines = text.lines().filter { it.contains(call) && !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
        assertTrue("no call to $call found", lines.isNotEmpty())
        for (line in lines) {
            val before = line.substringBefore(call)
            assertTrue("$call on the caller's thread: ${line.trim()}", before.contains("withContext(Dispatchers.IO) {"))
        }
    }

    @Test
    fun `the upgrade swap logs the cached spans off the main thread`() {
        val service = source("playback/PlaybackService.kt")
        val audition = service.substringAfter("private suspend fun auditionUpgrade(")
            .substringBefore("return warmedThrough")
        assertTrue(audition.contains("AudioCache.cachedSummary("))
        assertEveryCallOffMain(audition, "AudioCache.cachedSummary(")
    }

    @Test
    fun `the lyrics screen reads the audio copy for the AI action off the main thread`() {
        val controller = source("ui/LyricsController.kt")
        assertEveryCallOffMain(controller, "VideoAudio.audioReadyForTiming(")
        assertTrue(controller.contains("private suspend fun aiTimingAvailable("))
        // The value set synchronously as the sheet loads reads no cache.
        val start = controller.substringAfter("private fun startTiming(")
            .substringBefore("val hasRequest = request != null")
        assertFalse(start.contains("aiTimingAvailable("))
        assertTrue(start.contains("aiTimingWithoutAudio(request, lines) == true"))
        // An untimed sheet still gets the read, off main, cancelled with the next one.
        val untimed = controller.substringAfter("lyrics-timing: no timing pass")
            .substringBefore("val generation = lyricsGeneration")
        assertTrue(untimed.contains("lyricsTimingJob = scope.launch(timingFailureHandler)"))
        assertTrue(untimed.contains("aiTimingAvailable(request, lines)"))
    }

    @Test
    fun `the lyrics screen watches completion and asks worth-measuring off the main thread`() {
        val controller = source("ui/LyricsController.kt")
        assertEveryCallOffMain(controller, "AudioCache.watchRenditionCompletion(")
        assertEveryCallOffMain(controller, "LyricsTiming.worthMeasuring(")
        val hearing = controller.substringAfter("private fun startEarlyHearing(")
            .substringBefore("LyricsTiming.hearVideoAtStart(")
        assertTrue(hearing.contains("earlyHearingJob = scope.launch(Dispatchers.IO)"))
    }

    /**
     * The main-thread answer skips the audio read whenever it cannot change the
     * result. That holds only while the audio term can turn the action on and
     * never off.
     */
    @Test
    fun `the audio term only ever turns the AI timing action on`() {
        val sheets = listOf(
            listOf("one", "two", "three", "four"),
            listOf("하나", "둘", "셋", "넷"),
            listOf("one", "", "two"),
        )
        val names = listOf("Song" to "Singer", "노래" to "가수", "Song (Instrumental)" to "Singer")
        for (texts in sheets) for ((title, artist) in names) for (duration in listOf(0L, 200_000L, 3_600_000L))
            for (gemini in listOf(false, true)) for (deepgram in listOf(false, true)) {
                fun available(audio: Boolean) = LyricsAiGuard.manualTimingAvailable(
                    geminiUsable = gemini,
                    deepgramUsable = deepgram,
                    audioAvailable = audio,
                    durationMs = duration,
                    title = title,
                    artist = artist,
                    lyricsTexts = texts,
                )
                if (available(audio = false)) assertEquals(true, available(audio = true))
            }
    }
}
