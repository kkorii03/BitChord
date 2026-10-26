package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An AI answer for a downloaded file is kept under the file's audio source,
 * not the stream's. Asking the "may I ask again?" question without that source
 * made the paid answer invisible and let the next pass buy it under the other
 * key.
 */
class LyricsTimingAiKeyTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    private val lines = listOf(
        LyricLine(1_000L, "one"),
        LyricLine(2_000L, "two"),
        LyricLine(3_000L, "three"),
    )

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-lyrics-ai-key").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun fileKey() = LyricsTiming.audioSourceKey("video", localFile = true)

    private fun writeAnswer() {
        val words = lines.joinToString("\n") { it.text }.hashCode()
        val key = 31 * fileKey().hashCode() + words
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"video":{
              "aiTextKey":$key,
              "ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":100},
              "aiEngine":"Deepgram"
            }}}
            """.trimIndent(),
        )
    }

    @Test
    fun `the file key sees the answer kept for that file`() = runBlocking {
        writeAnswer()

        assertTrue(LyricsTiming.hasAi(context, "video", lines, fileKey()))
        assertFalse(LyricsTiming.wantsAi(context, "video", lines, fileKey()))
    }

    @Test
    fun `the stream key is a different question, which is why callers must pass the source`() = runBlocking {
        writeAnswer()

        assertFalse(LyricsTiming.hasAi(context, "video", lines))
        assertTrue(LyricsTiming.wantsAi(context, "video", lines))
    }
}
