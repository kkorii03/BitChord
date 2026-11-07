package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTimeMap
import com.music.bitchord.data.lyrics.LyricsWordAlign
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
 * A playing video's quick opening answer (11-08 WwFUZS8dv_4: the whole-song request
 * alone answered after the play). It is shown at once, and never written over an exact
 * fit of the same sheet or the listener's own ask.
 */
class LyricsTimingVideoQuickListenTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val lines = listOf(LyricLine(1_000L, "one"), LyricLine(2_000L, "two"), LyricLine(3_000L, "three"))
    private val key = lines.joinToString("\n") { it.text }.hashCode()
    private val fit = LyricsTiming.Adjustment.Mapped(LyricsTimeMap.shift(500L), byAi = true, rate = 1.0, matchPercent = 90)
    private val heard = listOf(
        LyricsWordAlign.Heard("one", 1_500L, 1_700L),
        LyricsWordAlign.Heard("two", 2_500L, 2_700L),
        LyricsWordAlign.Heard("three", 3_500L, 3_700L),
    )

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-video-quick").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun kept(id: String, before: String?, rough: Boolean = true): Boolean = runBlocking {
        before?.let { File(directory, "lyrics_timing_v1.json").writeText("""{"entries":{"$id":{$it}}}""") }
        storeField.set(LyricsTiming, null)
        LyricsTiming.keepQuickVideoAnswer(context, id, key, fit, heard, LyricsTiming.Clock.YouTube, rough)
    }

    @Test
    fun `a quick answer with nothing kept is shown at once`() = runBlocking {
        assertTrue(kept("fresh", before = null))
        val base = LyricsTiming.timing(context, "fresh", lines, baseAllowed = true, measuredAllowed = true, isVideo = true)?.base
        assertTrue(base?.byAi == true)
        assertEquals(500L, LyricsTiming.shownShiftMs(lines, base!!))
    }

    @Test
    fun `an exact fit of the same sheet stands`() {
        val exact = """"aiTextKey":$key,"aiEngine":"Deepgram Whisper","aiRough":false,""" +
            """"ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":95}"""
        assertFalse(kept("exact", exact))
    }

    @Test
    fun `a rough answer kept before is replaced`() {
        val rough = """"aiTextKey":$key,"aiEngine":"Deepgram Whisper","aiRough":true,""" +
            """"ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":95}"""
        assertTrue(kept("rough", rough))
    }

    @Test
    fun `the listener's own ask stands`() {
        val asked = """"aiTextKey":$key,"aiPreferred":true,"aiRough":true,""" +
            """"ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":95}"""
        assertFalse(kept("asked", asked))
    }

    private fun evidence(id: String, before: String?, rough: Boolean = true): Boolean = runBlocking {
        before?.let { File(directory, "lyrics_timing_v1.json").writeText("""{"entries":{"$id":{$it}}}""") }
        storeField.set(LyricsTiming, null)
        LyricsTiming.keepQuickVideoEvidence(context, id, key, heard, LyricsTiming.Clock.YouTube, rough)
    }

    /** 11-08 KChyOsjqmfM: the quick words fit no line and were thrown away; the wrong sheet stayed. */
    @Test
    fun `quick words that fit no line are kept as sheet evidence`() = runBlocking {
        assertTrue(evidence("unfit", before = null))
        assertEquals(heard.size, LyricsTiming.heardWordsFor(context, "unfit").size)
    }

    @Test
    fun `quick evidence never replaces an answer or exact evidence for the sheet`() {
        val answered = """"aiTextKey":$key,"aiRough":false,""" +
            """"ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":95}"""
        assertFalse(evidence("answered", answered))
        val exact = """"heardCheckKey":$key,"heardCheckRough":false,"heardCheckWords":[{"t":"one","s":1000,"e":1200}]"""
        assertFalse(evidence("exact-evidence", exact))
        assertTrue(evidence("exact-evidence-again", exact, rough = false))
    }

    /** Sol review F3: an opening that fits must not wipe the whole song's wrong-sheet evidence. */
    @Test
    fun `a quick answer leaves the kept wrong-sheet evidence alone`() = runBlocking {
        val words = (1..5).joinToString(",") { """{"t":"w$it","s":${it * 30_000},"e":${it * 30_000 + 200}}""" }
        val evidence = """"heardCheckKey":$key,"heardCheckRough":false,"heardCheckWords":[$words]"""
        assertTrue(kept("evidence-stays", evidence))
        assertEquals(5, LyricsTiming.heardWordsFor(context, "evidence-stays").size)
    }
}