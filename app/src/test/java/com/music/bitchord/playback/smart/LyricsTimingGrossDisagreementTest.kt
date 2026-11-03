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
 * 11-04: the same song passed or failed depending on which provider's times the
 * app accepted, because a transcript that heard the words eight seconds away
 * left an accepted answer untouched. Enough heard lines beyond the narrow
 * refine cap must keep the timing unsettled so the AI fallback may still fix
 * it, instead of silently accepting the source timing.
 */
class LyricsTimingGrossDisagreementTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    private val texts = listOf(
        "alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
        "golf", "hotel", "india", "juliet", "kilo", "lima",
    )
    private val lines = texts.mapIndexed { index, text -> LyricLine(index * 5_000L, text) }
    private val key = texts.joinToString("\n").hashCode()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-lyrics-gross").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun writeAcceptedAnswerHeardEightSecondsLate() {
        val heard = lines.joinToString(",") { line ->
            val start = line.timeMs + 8_000L
            """{"t":"${line.text}","s":$start,"e":${start + 500L}}"""
        }
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"video":{
              "autoVersion":4,
              "auto":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":90},
              "autoComplete":true,
              "aiTextKey":$key,
              "ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":95},
              "aiEngine":"Gemini",
              "refineHeardKey":$key,
              "refineHeardWords":[$heard]
            }}}
            """.trimIndent(),
        )
    }

    @Test
    fun `an accepted answer eight seconds from the transcript is not settled`() = runBlocking {
        writeAcceptedAnswerHeardEightSecondsLate()

        assertFalse(LyricsTiming.measurementGaveUp(context, "video"))
        assertTrue(LyricsTiming.measurementGaveUp(context, "video", lines))
        assertTrue(LyricsTiming.wantsAi(context, "video", lines))
    }

    @Test
    fun `an unsettled accepted answer asks the other engine once`() {
        val whisper = AiTimingKnowledge(answerKey = key, answerEngine = "Whisper", answerMatch = 95)
        assertFalse(AiTimingPolicy.shouldAskAgain(whisper, key, manual = false, measurementGaveUp = false))
        assertTrue(
            AiTimingPolicy.shouldAskAgain(
                whisper,
                key,
                manual = false,
                measurementGaveUp = true,
                unsettled = true,
            ),
        )
        assertEquals(
            listOf("Gemini"),
            AiTimingPolicy.nextEngines(whisper, key, manual = false, measurementGaveUp = true, unsettled = true),
        )
    }
}
