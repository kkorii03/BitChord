package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A refine computes from the entry it read, then writes back after store I/O.
 * Another path may save the same video meanwhile (a newer refine, an AI answer
 * or a hand correction). The older result must not be written over it; a hand
 * correction in particular is never a refine's to change.
 */
class LyricsRefineStaleWriteTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }
    private val lines = listOf(
        LyricLine(1_000L, "alpha"),
        LyricLine(2_000L, "bravo"),
        LyricLine(3_000L, "charlie"),
        LyricLine(4_000L, "delta"),
        LyricLine(5_000L, "echoes"),
        LyricLine(6_000L, "foxtrot"),
        LyricLine(7_000L, "golfing"),
        LyricLine(8_000L, "hotels"),
    )
    private val shiftMs = 300L
    private val lyricsKey = lines.joinToString("\n") { it.text }.hashCode()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-lyrics-refine-stale").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun writeHeardWords(videoId: String, manualMs: Long? = null) {
        val words = lines.joinToString(",") { line ->
            val start = line.timeMs + shiftMs
            """{"t":"${line.text}","s":$start,"e":${start + 150}}"""
        }
        val manual = manualMs?.let { ""","manualMs":$it""" } ?: ""
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"$videoId":{
              "refineHeardKey":$lyricsKey,
              "refineHeardWords":[$words],
              "refineHeardSeconds":90.0,
              "refineHeardExtended":true$manual
            }}}
            """.trimIndent(),
        )
    }

    private fun fileText(): String = File(directory, "lyrics_timing_v1.json").readText()

    private fun awaitFile(predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val text = runCatching { fileText() }.getOrElse { "" }
            if (predicate(text)) return text
            Thread.sleep(25L)
        }
        return runCatching { fileText() }.getOrElse { "" }
    }

    @Test
    fun `a refine with no newer write is saved`() = runBlocking {
        writeHeardWords("video")

        val result = LyricsTiming.refine(
            context,
            "video",
            lines,
            LyricsTiming.Timing(base = null, manualMs = 0L),
        )

        assertTrue(result.applied)
        assertEquals(shiftMs, result.medianMs)
        val text = awaitFile { it.contains("\"refineMs\":$shiftMs") }
        assertTrue(text.contains("\"refineMs\":$shiftMs"))
        assertEquals(shiftMs, result.medianMs)
    }

    @Test
    fun `a refine whose stored entry changed meanwhile is dropped`() = runBlocking {
        writeHeardWords("video")

        val result = LyricsTiming.refine(
            context,
            "video",
            lines,
            LyricsTiming.Timing(base = null, manualMs = 0L),
            stillCurrent = {
                runBlocking { LyricsTiming.setManual(context, "video", 1_234L) }
                true
            },
        )

        assertTrue(result.applied)
        val text = awaitFile { it.contains("\"manualMs\":1234") }
        assertTrue(text.contains("\"manualMs\":1234"))
        assertFalse(text.contains("\"refineMs\""))
        assertFalse(text.contains("\"refineLines\""))
        assertFalse(text.contains("\"refineMad\""))
        assertEquals(1_234L, LyricsTiming.manual(context, "video"))
    }

    @Test
    fun `a refine never overwrites a manual correction`() = runBlocking {
        writeHeardWords("video", manualMs = -420L)

        val result = LyricsTiming.refine(
            context,
            "video",
            lines,
            LyricsTiming.Timing(base = null, manualMs = -420L),
        )

        assertTrue(result.applied)
        val text = awaitFile { it.contains("\"refineMs\":$shiftMs") }
        assertTrue(text.contains("\"manualMs\":-420"))
        assertEquals(-420L, LyricsTiming.manual(context, "video"))
    }
}
