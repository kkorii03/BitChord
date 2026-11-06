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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The words a transcript heard are the sung moments the refine and the per-line
 * layer move the lines onto. Moving the words by those same corrections counted
 * them twice: 11-06 6rS7OUGXUik had a 1.1 s refine on an AI base, so its sweep
 * started 1.1 s after the line it belongs to. Only the hand's correction moves
 * them, since it moves everything the listener sees.
 */
class LyricsTimingHeardWordsTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    private val lateBy = 500L
    private val lines = listOf("alpha", "bravo", "charlie", "delta", "echoes", "foxtrot", "golfing", "hotels")
        .mapIndexed { index, text -> LyricLine(10_000L + index * 3_000L, text) }
    private val key = lines.joinToString("\n") { it.text }.hashCode()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-heard-words").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    /** An AI base that moves nothing, with words sung [lateBy] after every line. */
    private fun write(video: String, manualMs: Long? = null) {
        val words = lines.joinToString(",") { line ->
            val start = line.timeMs + lateBy
            """{"t":"${line.text}","s":$start,"e":${start + 600}}"""
        }
        val manual = manualMs?.let { """"manualMs":$it,"manualClock":"yt",""" }.orEmpty()
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"$video":{
              "clockVersion":1,
              "aiTextKey":$key,
              "ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":100},
              "aiClock":"yt",
              "aiEngine":"Deepgram Whisper",
              "heardClock":"yt",
              $manual
              "heardWords":[$words]
            }}}
            """.trimIndent(),
        )
    }

    private suspend fun timing(video: String) =
        LyricsTiming.timing(context, video, lines, baseAllowed = true, isVideo = true)!!

    @Test
    fun `a refine moves the line onto the words, and leaves the words where they were heard`() = runBlocking {
        write("refined")

        val timing = timing("refined")
        val shown = LyricsTiming.apply(lines, timing)

        assertTrue("the refine applied", timing.refineMs != 0L)
        assertEquals(lines[0].timeMs + lateBy, shown[0].timeMs)
        assertEquals(lines[0].timeMs + lateBy, timing.heardWords.first().startMs)
    }

    @Test
    fun `the hand moves the line and its words together`() = runBlocking {
        write("nudged", manualMs = 200L)

        val timing = timing("nudged")
        val shown = LyricsTiming.apply(lines, timing)

        assertEquals(lines[0].timeMs + lateBy + 200L, shown[0].timeMs)
        assertEquals(lines[0].timeMs + lateBy + 200L, timing.heardWords.first().startMs)
    }
}
