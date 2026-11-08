package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.data.lyrics.LyricsPerLine
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
        LyricsTiming.resetForTest()
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

    @Test
    fun `a sheet with no AI answer still sweeps from the refine's transcript`() = runBlocking {
        val words = lines.joinToString(",") { line ->
            val start = line.timeMs + lateBy
            """{"t":"${line.text}","s":$start,"e":${start + 600}}"""
        }
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"plain":{"refineHeardKey":$key,"refineHeardWords":[$words],"refineHeardSeconds":90.0,"refineHeardExtended":true}}}""",
        )

        val timing = LyricsTiming.timing(context, "plain", lines, baseAllowed = true)!!

        assertEquals(null, timing.base)
        assertEquals(lines.map { it.timeMs + lateBy }, timing.heardWords.map { it.startMs })
    }

    @Test
    fun `a refused song check still sweeps the sheet from the words it kept`() = runBlocking {
        // 11-07 3JWTaaS7LdU: a 56% check kept 160 words, its +1.52 s fit was refused,
        // and the refine and per-line layers, which read only the other word lists,
        // never saw them. The strict score needed +1.44 s: 10 lines, none within 0.3 s.
        // 1.6 s is past the refine's 1.5 s window, so this can only pass on the
        // per-line layer's longer move; three words a line allow it.
        val refusedBy = 1_600L
        val refusedLines = (0 until 8).map { i ->
            LyricLine(10_000L + i * 3_000L, "alpha${i} bravo${i} charlie${i}")
        }
        val refusedKey = refusedLines.joinToString("\n") { it.text }.hashCode()
        val words = refusedLines.joinToString(",") { line ->
            line.text.split(" ").mapIndexed { i, text ->
                val start = line.timeMs + refusedBy + i * 900L
                """{"t":"$text","s":$start,"e":${start + 300}}"""
            }.joinToString(",")
        }
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"refused":{"heardCheckKey":$refusedKey,"heardCheckWords":[$words],"heardCheckClock":"yt"}}}""",
        )

        val timing = LyricsTiming.timing(context, "refused", refusedLines, baseAllowed = true)!!

        assertEquals(null, timing.base)
        assertEquals(refusedLines[0].timeMs + refusedBy, timing.heardWords.first().startMs)
        assertTrue("the per-line layer ran", timing.perLine != null)
        assertEquals(refusedLines[0].timeMs + refusedBy, LyricsTiming.apply(refusedLines, timing)[0].timeMs)
    }

    /**
     * Three-word lines under an AI base that moves nothing, with three lines in a row heard
     * 0.5 s late: too few for the refine or the base to follow, too many for the good-sheet gate.
     */
    private suspend fun shownWithSomeLinesHeardLate(sheet: List<LyricLine>): List<LyricLine> {
        val sheetKey = sheet.joinToString("\n") { it.text }.hashCode()
        val words = sheet.withIndex().joinToString(",") { (index, line) ->
            val late = if (index in 3..5) 500L else 0L
            line.text.split(" ").mapIndexed { i, text ->
                val start = line.timeMs + late + i * 900L
                """{"t":"$text","s":$start,"e":${start + 300}}"""
            }.joinToString(",")
        }
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"sheet":{
              "clockVersion":1,
              "aiTextKey":$sheetKey,
              "ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":100},
              "aiClock":"yt",
              "aiEngine":"Deepgram Whisper",
              "heardClock":"yt",
              "heardWords":[$words]
            }}}
            """.trimIndent(),
        )
        val timing = LyricsTiming.timing(context, "sheet", sheet, baseAllowed = true, isVideo = true)!!
        assertTrue("a base fit", timing.base != null)
        return LyricsTiming.apply(sheet, timing)
    }

    private val threeWordLines = (0 until 8).map { i -> LyricLine(10_000L + i * 3_000L, "alpha${i} bravo${i} charlie${i}") }

    @Test
    fun `a sheet that states its own word times keeps them after the whole-song fit`() = runBlocking {
        val wordTimed = threeWordLines.map { line ->
            line.copy(words = line.text.split(" ").mapIndexed { i, text ->
                LyricWord(line.timeMs + i * 400L, line.timeMs + i * 400L + 300L, text)
            })
        }
        assertEquals(wordTimed[4].timeMs, shownWithSomeLinesHeardLate(wordTimed)[4].timeMs)
    }

    @Test
    fun `a line-timed sheet still moves onto the words`() = runBlocking {
        val shown = shownWithSomeLinesHeardLate(threeWordLines)[4].timeMs
        assertTrue("moved to $shown", shown >= threeWordLines[4].timeMs + 300L)
    }

    @Test
    fun `guessed word times do not count as the sheet's own`() {
        val guessed = threeWordLines.map { line ->
            line.copy(words = listOf(LyricWord(line.timeMs, line.timeMs + 300L, line.text)), wordsEstimated = true)
        }
        assertFalse(LyricsPerLine.statesItsOwnWords(guessed))
        assertFalse(LyricsPerLine.statesItsOwnWords(threeWordLines))
    }

    @Test
    fun `a refused check on Groq's words moves no line`() = runBlocking {
        val refusedLines = (0 until 8).map { i ->
            LyricLine(10_000L + i * 3_000L, "alpha${i} bravo${i} charlie${i}")
        }
        val refusedKey = refusedLines.joinToString("\n") { it.text }.hashCode()
        val words = refusedLines.joinToString(",") { line ->
            line.text.split(" ").mapIndexed { i, text ->
                val start = line.timeMs + 1_600L + i * 900L
                """{"t":"$text","s":$start,"e":${start + 300}}"""
            }.joinToString(",")
        }
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"refused":{"heardCheckKey":$refusedKey,"heardCheckWords":[$words],"heardCheckClock":"yt","heardCheckRough":true}}}""",
        )

        // With nothing to apply there may be no timing at all; either way no line has moved.
        val timing = LyricsTiming.timing(context, "refused", refusedLines, baseAllowed = true)

        assertEquals(null, timing?.perLine)
        assertEquals(null, timing?.base)
    }

    @Test
    fun `an AI's line times are no transcript to sweep from`() = runBlocking {
        val words = lines.joinToString(",") { line ->
            """{"t":"${line.text}","s":${line.timeMs},"e":${line.timeMs + 600}}"""
        }
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"gemini":{"aiTextKey":$key,"aiEngine":"Gemini","heardWords":[$words]}}}""",
        )

        assertTrue(LyricsTiming.timing(context, "gemini", lines, baseAllowed = true)?.heardWords.orEmpty().isEmpty())
    }
}