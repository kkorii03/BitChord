package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ko.KoSavedLyrics
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
 * TR2 T5: a base decision is kept per (song, source, generation, sheet) so a
 * caller with less evidence cannot flip it. When the AI answer it was kept for
 * is removed from the entry (forgetSavedSheetCheck, refit's dropStale), the old
 * map object must not keep moving the lines until the app restarts.
 */
class LyricsTimingDroppedBaseTest {

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
        directory = Files.createTempDirectory("bitchord-dropped-base").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
        val key = lines.joinToString("\n") { it.text }.hashCode()
        // The words were sung 400 ms after the sheet's times: the AI answer
        // (+400 ms) is right and the measurement (+1999 ms) is not.
        val words = lines.joinToString(",") { line ->
            """{"t":"${line.text}","s":${line.timeMs + 400},"e":${line.timeMs + 550}}"""
        }
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"video":{"autoVersion":${LyricsTiming.AUTO_VERSION},""" +
                """"auto":{"knots":[0,1999],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":100},""" +
                """"aiTextKey":$key,""" +
                """"ai":{"knots":[0,400],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":90},""" +
                """"syncCheckVersion":1,""" +
                """"refineHeardKey":$key,"refineHeardWords":[$words]}}}""",
        )
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private suspend fun baseFor(sheet: List<LyricLine>) =
        LyricsTiming.timing(context, "video", sheet, baseAllowed = true, isVideo = true)?.base

    @Test
    fun `an AI answer the entry dropped does not stay on screen through the kept base`() = runBlocking {
        val before = baseFor(lines)!!
        assertTrue(before.byAi)
        assertEquals(400L, LyricsTiming.shownShiftMs(lines, before))

        assertEquals(
            KoSavedLyrics.SavedCheck.FORGET_ANSWER,
            LyricsTiming.forgetSavedSheetCheck(context, "video", lines, isVideo = true),
        )

        // Old code: the kept decision named the AI map, the measurement and
        // "none" have less evidence, so the dropped +400 ms map was shown on.
        val after = baseFor(lines)
        assertFalse("dropped AI map still applied", after?.byAi == true)
    }

    @Test
    fun `a form of the sheet without the answer under its key still keeps the AI base`() = runBlocking {
        assertTrue(baseFor(lines)!!.byAi)
        // The same words with a provider's blank gap line: another text key with
        // no answer and no words of its own, the 1WZ1tfpyu3M caller that must
        // not flip the base while the entry still holds the answer.
        val gapped = lines.take(4) + LyricLine(4_500L, "") + lines.drop(4)

        val kept = baseFor(gapped)!!
        assertTrue(kept.byAi)
        assertEquals(400L, LyricsTiming.shownShiftMs(gapped, kept))
    }
}
