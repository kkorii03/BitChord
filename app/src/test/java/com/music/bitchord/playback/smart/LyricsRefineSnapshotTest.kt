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
 * A caller that listens for words before refining captures the entry first
 * ([LyricsTiming.refineSnapshot]). If another path saves the same song while
 * it listens, the refined result is stale and must not overwrite that newer
 * correction. A hand correction still wins in every case.
 */
class LyricsRefineSnapshotTest {

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
        directory = Files.createTempDirectory("bitchord-lyrics-refine-snapshot").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun heardEntry(manualMs: Long? = null): String {
        val words = lines.joinToString(",") { line ->
            val start = line.timeMs + shiftMs
            """{"t":"${line.text}","s":$start,"e":${start + 150}}"""
        }
        val manual = manualMs?.let { ""","manualMs":$it""" } ?: ""
        return """
            "refineHeardKey":$lyricsKey,
            "refineHeardWords":[$words],
            "refineHeardSeconds":90.0,
            "refineHeardExtended":true$manual
        """.trimIndent()
    }

    private fun writeHeardWords(videoId: String, manualMs: Long? = null) {
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"$videoId":{${heardEntry(manualMs)}}}}""",
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
    fun `a snapshot taken before a newer write makes the refine stale`() = runBlocking {
        writeHeardWords("video")
        // The caller captures this before its long listen.
        val snapshot = LyricsTiming.refineSnapshot(context, "video")
        // A newer correction lands while the caller listens.
        LyricsTiming.setManual(context, "video", 1_234L)

        val result = LyricsTiming.refineWithSnapshot(
            context,
            "video",
            lines,
            LyricsTiming.Timing(base = null, manualMs = 0L),
            snapshot = snapshot,
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
    fun `a snapshot with no newer write still saves the refine`() = runBlocking {
        writeHeardWords("video")
        val snapshot = LyricsTiming.refineSnapshot(context, "video")

        val result = LyricsTiming.refineWithSnapshot(
            context,
            "video",
            lines,
            LyricsTiming.Timing(base = null, manualMs = 0L),
            snapshot = snapshot,
        )

        assertTrue(result.applied)
        val text = awaitFile { it.contains("\"refineMs\":$shiftMs") }
        assertTrue(text.contains("\"refineMs\":$shiftMs"))
    }

    @Test
    fun `a snapshot never overwrites a manual correction`() = runBlocking {
        writeHeardWords("video", manualMs = -420L)
        val snapshot = LyricsTiming.refineSnapshot(context, "video")

        val result = LyricsTiming.refineWithSnapshot(
            context,
            "video",
            lines,
            LyricsTiming.Timing(base = null, manualMs = -420L),
            snapshot = snapshot,
        )

        assertTrue(result.applied)
        val text = awaitFile { it.contains("\"refineMs\":$shiftMs") }
        assertTrue(text.contains("\"manualMs\":-420"))
        assertEquals(-420L, LyricsTiming.manual(context, "video"))
    }

    @Test
    fun `a just written refine survives eviction from an oversized store`() = runBlocking {
        // More entries than the cap, with "video" as the oldest insertion.
        val others = (1..3_001).joinToString(",") { """"v$it":{}""" }
        assertTrue(
            LyricsTiming.restoreBackupText(
                context,
                """{"entries":{"video":{${heardEntry()}},$others}}""",
            ),
        )
        val snapshot = LyricsTiming.refineSnapshot(context, "video")

        val result = LyricsTiming.refineWithSnapshot(
            context,
            "video",
            lines,
            LyricsTiming.Timing(base = null, manualMs = 0L),
            snapshot = snapshot,
        )

        assertTrue(result.applied)
        val text = awaitFile { it.contains("\"refineMs\":$shiftMs") }
        assertTrue("the just written entry must not be the one evicted", text.contains("\"video\""))
    }
}
