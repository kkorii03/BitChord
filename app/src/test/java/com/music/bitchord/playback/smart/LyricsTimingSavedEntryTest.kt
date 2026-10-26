package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The on-disk timing entry predates several fields. An old file has to keep
 * loading, a malformed file has to read as empty rather than throw, and a
 * current write has to survive a fresh store.
 */
class LyricsTimingSavedEntryTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-lyrics-timing").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun write(text: String) {
        File(directory, "lyrics_timing_v1.json").writeText(text)
    }

    @Test
    fun `a ko 21 entry with only the old fields still loads`() {
        val lines = listOf(
            LyricLine(0L, "one"),
            LyricLine(1_000L, "two"),
            LyricLine(2_000L, "three"),
        )
        val textKey = lines.joinToString("\n") { it.text }.hashCode()
        write(
            """
            {"entries":{"old":{
              "autoChecked":true,
              "autoShiftMs":4200,
              "aiTextKey":$textKey,
              "aiShiftMs":800,
              "aiStarts":[0,1000,2000],
              "manualMs":-250
            }}}
            """.trimIndent(),
        )

        runBlocking {
            assertEquals(-250L, LyricsTiming.manual(context, "old"))
            assertFalse(LyricsTiming.measured(context, "old"))
            assertFalse(LyricsTiming.measuredTiming(context, "old"))

            val timing = LyricsTiming.timing(context, "old", lines, baseAllowed = true)
            assertEquals(LyricsTiming.Adjustment.Shift(800L, byAi = true), timing?.base)
            assertEquals(-250L, timing?.manualMs)
        }
    }

    @Test
    fun `unknown fields are ignored rather than invalidating the entry`() {
        write(
            """
            {"entries":{"video":{
              "manualMs":1500,
              "futureField":{"nested":true},
              "futureList":[1,2,3]
            }}}
            """.trimIndent(),
        )

        runBlocking {
            assertEquals(1500L, LyricsTiming.manual(context, "video"))
        }
    }

    @Test
    fun `an entry with no manual correction is zero`() {
        write("""{"entries":{"video":{"autoComplete":true}}}""")

        runBlocking {
            assertEquals(0L, LyricsTiming.manual(context, "video"))
        }
    }

    @Test
    fun `malformed json reads as an empty store`() {
        write("not json at all")

        runBlocking {
            assertEquals(0L, LyricsTiming.manual(context, "video"))
            assertFalse(LyricsTiming.measured(context, "video"))
        }
    }

    @Test
    fun `a file with the wrong shape reads as an empty store`() {
        write("""{"entries":[]}""")

        runBlocking {
            assertEquals(0L, LyricsTiming.manual(context, "video"))
        }
    }

    @Test
    fun `a manual correction written now survives a fresh store`() {
        runBlocking {
            LyricsTiming.setManual(context, "new-video", 1500L)
        }

        val file = File(directory, "lyrics_timing_v1.json")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (file.exists() && file.readText().contains("\"manualMs\":1500")) break
            Thread.sleep(25L)
        }

        assertEquals(true, file.exists())
        assertEquals(true, file.readText().contains("\"manualMs\":1500"))

        storeField.set(LyricsTiming, null)

        runBlocking {
            assertEquals(1500L, LyricsTiming.manual(context, "new-video"))
        }
    }

    @Test
    fun `a manual correction is clamped to the public maximum`() {
        runBlocking {
            LyricsTiming.setManual(context, "new-video", LyricsTiming.MAX_MANUAL_MS * 2)
        }

        val file = File(directory, "lyrics_timing_v1.json")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (file.exists() && file.readText().contains("\"manualMs\":60000")) break
            Thread.sleep(25L)
        }

        storeField.set(LyricsTiming, null)

        runBlocking {
            assertEquals(LyricsTiming.MAX_MANUAL_MS, LyricsTiming.manual(context, "new-video"))
        }
    }

    @Test
    fun `an entry without an id is not returned for another id`() {
        write("""{"entries":{"a":{"manualMs":100}}}""")

        runBlocking {
            assertEquals(100L, LyricsTiming.manual(context, "a"))
            assertEquals(0L, LyricsTiming.manual(context, "b"))
            assertNull(LyricsTiming.timing(context, "b", emptyList(), baseAllowed = true))
        }
    }

    @Test
    fun `replacing the file forgets the measurement and the sync check too`() {
        write(
            """
            {"entries":{"replaced":{
              "autoVersion":4,
              "auto":{"knots":[0,1000],"headRate":1.0,"tailRate":1.0,"rate":1.0},
              "autoComplete":true,
              "autoVideoBytes":123,
              "autoVideoSeconds":60.0,
              "autoNote":"old file",
              "searchVersion":3,
              "syncCheckVersion":1,
              "manualMs":250
            }}}
            """.trimIndent(),
        )

        runBlocking {
            LyricsTiming.resetSongCheck(context, "replaced")

            assertFalse(LyricsTiming.measured(context, "replaced"))
            assertFalse(LyricsTiming.measuredTiming(context, "replaced"))
            assertNull(LyricsTiming.note(context, "replaced"))
            assertEquals(250L, LyricsTiming.manual(context, "replaced"))
        }

        val file = File(directory, "lyrics_timing_v1.json")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (file.exists() && !file.readText().contains("\"syncCheckVersion\"")) break
            Thread.sleep(25L)
        }

        assertEquals(false, file.readText().contains("\"syncCheckVersion\""))
    }
}
