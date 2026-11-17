package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * LOG-1117 G7: 1437 `base-choice` lines in five days, 735 of them an identical
 * repeat of the previous one within a second, because every timing pass asks
 * for the base again. The choice itself is unchanged; only the repeat is quiet.
 */
class LyricsTimingBaseChoiceLogTest {

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

    @Before
    fun setUp() {
        LyricsTiming.resetForTest()
        directory = Files.createTempDirectory("bitchord-base-choice").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
        val key = lines.joinToString("\n") { it.text }.hashCode()
        val words = lines.joinToString(",") { line ->
            """{"t":"${line.text}","s":${line.timeMs + 300},"e":${line.timeMs + 450}}"""
        }
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"song":{"autoVersion":${LyricsTiming.AUTO_VERSION},""" +
                """"auto":{"knots":[0,1999],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":100},""" +
                """"refineHeardKey":$key,"refineHeardWords":[$words],""" +
                """"heardCheckKey":$key,"heardCheckWords":[$words]}}}""",
        )
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    @Test
    fun `the same base choice asked twice is logged once`() {
        val logged = mutableListOf<String>()
        val previous = TrackLog.echo
        TrackLog.echo = { _, _, message, _ ->
            if ("base-choice:" in message) synchronized(logged) { logged += message }
        }
        val (bases, afterFirst) = try {
            runBlocking {
                val first = LyricsTiming.timing(context, "song", lines, baseAllowed = true, isVideo = true)?.base
                val count = synchronized(logged) { logged.size }
                val second = LyricsTiming.timing(context, "song", lines, baseAllowed = true, isVideo = true)?.base
                listOf(first, second) to count
            }
        } finally {
            TrackLog.echo = previous
        }
        // The decision is the same both times; the second pass is quiet.
        assertEquals(bases[0], bases[1])
        assertTrue(logged.toString(), afterFirst >= 1)
        assertEquals(logged.toString(), afterFirst, synchronized(logged) { logged.size })
    }
}
