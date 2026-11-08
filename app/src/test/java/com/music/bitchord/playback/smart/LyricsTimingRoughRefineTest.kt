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

/** A fit from Groq's rough words must still let Deepgram's refine start. */
class LyricsTimingRoughRefineTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val lines = listOf(LyricLine(1_000L, "one"), LyricLine(2_000L, "two"), LyricLine(3_000L, "three"))

    @Before
    fun setUp() {
        LyricsTiming.resetForTest()
        directory = Files.createTempDirectory("bitchord-lyrics-rough").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun known(rough: Boolean): Boolean = runBlocking {
        val key = lines.joinToString("\n") { it.text }.hashCode()
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"video":{"aiTextKey":$key,"aiEngine":"deepgram-whisper","aiRough":$rough,""" +
                """"heardWords":[{"t":"one","s":0,"e":100000}]}}}""",
        )
        storeField.set(LyricsTiming, null)
        LyricsTiming.refineHeardKnown(context, "video", lines, LyricsTiming.Timing(base = null, manualMs = 0L))
    }

    private fun timingWith(rough: Boolean): LyricsTiming.Timing? = runBlocking {
        val key = lines.joinToString("\n") { it.text }.hashCode()
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"video":{"aiTextKey":$key,"aiEngine":"deepgram-whisper","aiRough":$rough,""" +
                """"ai":{"knots":[0,500],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":90},""" +
                """"heardWords":[{"t":"one","s":1500,"e":1700},{"t":"two","s":2500,"e":2700},{"t":"three","s":3500,"e":3700}]}}}""",
        )
        storeField.set(LyricsTiming, null)
        LyricsTiming.timing(context, "video", lines, baseAllowed = true, measuredAllowed = true)
    }

    @Test
    fun `no line moves on rough words but exact ones may`() {
        assertTrue(timingWith(rough = false)?.perLine != null)
        assertTrue(timingWith(rough = true)?.perLine == null)
    }

    @Test
    fun `exact words reaching past the last line need no refine`() {
        assertTrue(known(rough = false))
    }

    @Test
    fun `rough words reaching past the last line still need the refine`() {
        assertFalse(known(rough = true))
    }
}
