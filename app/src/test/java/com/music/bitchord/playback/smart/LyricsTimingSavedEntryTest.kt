package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `an ai shift past what a sheet can be off by waits for the measurement`() {
        // 11-04 ekzHIouo8Q4: a stored AI answer moved the sheet 11.78 s with
        // match=100% and nothing heard to confirm it, while the measurement that
        // landed twelve seconds later found -38 ms. Held, the provider's own
        // times stand until the measurement proves the move.
        val lines = listOf(
            LyricLine(0L, "one"),
            LyricLine(1_000L, "two"),
            LyricLine(2_000L, "three"),
        )
        val textKey = lines.joinToString("\n") { it.text }.hashCode()
        write("""{"entries":{"video":{"aiTextKey":$textKey,"aiShiftMs":11780}}}""")

        runBlocking {
            assertNull(LyricsTiming.timing(context, "video", lines, baseAllowed = true)?.base)
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

    // 11-06 cCfPDrRQp9k: a song check begun on the stream finished after the
    // switch to the verified file and kept its answer under the stream key;
    // the file-keyed screen chose ai in base-choice but applied none.
    private val songLines = listOf(
        LyricLine(1_000L, "alpha"),
        LyricLine(2_000L, "bravo"),
        LyricLine(3_000L, "charlie"),
        LyricLine(4_000L, "delta"),
        LyricLine(5_000L, "echoes"),
        LyricLine(6_000L, "foxtrot"),
        LyricLine(7_000L, "golfing"),
        LyricLine(8_000L, "hotels"),
    )
    private val fileSource = LyricsTiming.audioSourceKey("song", localFile = true)
    private val streamSource = LyricsTiming.audioSourceKey("song", localFile = false)
    private fun keyOf(source: String?) =
        source?.let { 31 * it.hashCode() + songLines.joinToString("\n") { it.text }.hashCode() }
            ?: songLines.joinToString("\n") { it.text }.hashCode()
    private val altClock = "alt\\u0001cfg\\u0001trk"

    private fun playSubstitute() {
        StreamChoice.remember(
            "song",
            SourceStream("https://alt/flac", sourceConfigId = "cfg", trackId = "trk"),
            substituted = true,
        )
    }

    @After
    fun forgetChoice() {
        StreamChoice.forget("song")
    }

    @Test
    fun `an ai answer kept under the stream key applies on the verified file`() {
        playSubstitute()
        write("""{"entries":{"song":{"aiTextKey":${keyOf(streamSource)},"aiShiftMs":800,"aiClock":"$altClock"}}}""")

        runBlocking {
            val timing = LyricsTiming.timing(context, "song", songLines, baseAllowed = true, audioSource = fileSource)
            assertEquals(LyricsTiming.Adjustment.Shift(800L, byAi = true), timing?.base)
        }
    }

    @Test
    fun `an untagged answer from the other source is not applied`() {
        playSubstitute()
        write("""{"entries":{"song":{"aiTextKey":${keyOf(streamSource)},"aiShiftMs":800}}}""")

        runBlocking {
            assertNull(
                LyricsTiming.timing(context, "song", songLines, baseAllowed = true, audioSource = fileSource)?.base,
            )
        }
    }

    @Test
    fun `an other-source answer past the hold threshold stays none`() {
        playSubstitute()
        write("""{"entries":{"song":{"aiTextKey":${keyOf(streamSource)},"aiShiftMs":11780,"aiClock":"$altClock"}}}""")

        runBlocking {
            assertNull(
                LyricsTiming.timing(context, "song", songLines, baseAllowed = true, audioSource = fileSource)?.base,
            )
        }
    }

    @Test
    fun `an other-source answer does not replace this source's measurement`() {
        playSubstitute()
        write(
            """{"entries":{"song":{"autoVersion":${LyricsTiming.AUTO_VERSION},"autoNoChange":true,""" +
                """"autoClock":"$altClock","aiTextKey":${keyOf(streamSource)},"aiShiftMs":800,"aiClock":"$altClock"}}}""",
        )

        runBlocking {
            val timing = LyricsTiming.timing(context, "song", songLines, baseAllowed = true, audioSource = fileSource)
            assertEquals(LyricsTiming.Adjustment.Shift(0L, byAi = false), timing?.base)
        }
    }

    @Test
    fun `a song with no base still gets its refine`() {
        val words = songLines.joinToString(",") { line ->
            """{"t":"${line.text}","s":${line.timeMs + 300},"e":${line.timeMs + 450}}"""
        }
        write(
            """{"entries":{"song":{"refineHeardKey":${keyOf(fileSource)},"refineHeardWords":[$words],""" +
                """"refineHeardSeconds":90.0,"refineHeardExtended":true,""" +
                """"aiTextKey":${keyOf(streamSource)},"aiShiftMs":800}}}""",
        )

        runBlocking {
            val timing = LyricsTiming.timing(context, "song", songLines, baseAllowed = true, audioSource = fileSource)
            assertNull(timing?.base)
            assertEquals(300L, timing?.refineMs)
        }
    }

    /** The base-apply lines [block] logs, with the once-per-change memory cleared first. */
    private fun baseApplyLines(block: () -> Unit): List<String> {
        (LyricsTiming::class.java.getDeclaredField("loggedBaseApply").apply { isAccessible = true }
            .get(LyricsTiming) as MutableMap<*, *>).clear()
        val logged = mutableListOf<String>()
        val previous = TrackLog.echo
        TrackLog.echo = { _, _, message, _ ->
            if ("base-apply:" in message) synchronized(logged) { logged += message }
        }
        try {
            block()
        } finally {
            TrackLog.echo = previous
        }
        return synchronized(logged) { logged.toList() }
    }

    @Test
    fun `this source's own ai answer is applied without consulting the other source`() {
        playSubstitute()
        write("""{"entries":{"song":{"aiTextKey":${keyOf(fileSource)},"aiShiftMs":300,"aiClock":"$altClock"}}}""")

        val logged = baseApplyLines {
            runBlocking {
                val timing = LyricsTiming.timing(context, "song", songLines, baseAllowed = true, audioSource = fileSource)
                assertEquals(LyricsTiming.Adjustment.Shift(300L, byAi = true), timing?.base)
            }
        }
        assertTrue(logged.toString(), logged.any { "base=ai" in it })
        assertFalse(logged.toString(), logged.any { "sibling" in it })
    }

    @Test
    fun `a refused base names the caller's gate`() {
        write("""{"entries":{"song":{"aiTextKey":${keyOf(fileSource)},"aiShiftMs":300}}}""")

        val logged = baseApplyLines {
            runBlocking {
                LyricsTiming.timing(
                    context, "song", songLines, baseAllowed = false,
                    audioSource = fileSource, deniedBy = "file-own-timing@test",
                )
            }
        }
        assertTrue(logged.toString(), logged.any { "(chosen by not-allowed(reason=file-own-timing@test))" in it })
    }

    @Test
    fun `nothing says where the song check stands`() {
        // 11-06 rlYOsUfpi3M: both song-check replies spent and refused.
        write(
            """{"entries":{"song":{"aiAttemptedKey":${keyOf(streamSource)},""" +
                """"aiAttemptedEngines":["Deepgram Whisper","Deepgram Whisper retry"],"refitVersion":${com.music.bitchord.data.lyrics.SongCheckWindow.FIT_VERSION}}}}""",
        )

        val logged = baseApplyLines {
            runBlocking {
                LyricsTiming.timing(context, "song", songLines, baseAllowed = true, audioSource = streamSource)
            }
        }
        assertTrue(logged.toString(), logged.any { "(chosen by nothing, song-check=spent 2/2, sibling=no-ai)" in it })
    }

    @Test
    fun `a refused refit's kept words keep none over the file's stored measurement`() {
        // 11-07 m7-home 3JWTaaS7LdU: the refit refused the sheet and kept the
        // words it judged; base-choice then said the provider's own times beat
        // the stored +1999 ms measurement, but the video-file fallback applied
        // that measurement anyway in fixed order.
        val words = songLines.joinToString(",") { line ->
            """{"t":"${line.text}","s":${line.timeMs + 300},"e":${line.timeMs + 450}}"""
        }
        write(
            """{"entries":{"song":{"autoVersion":${LyricsTiming.AUTO_VERSION},""" +
                """"auto":{"knots":[0,1999],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":100},""" +
                """"refineHeardKey":${keyOf(null)},"refineHeardWords":[$words],""" +
                """"heardCheckKey":${keyOf(null)},"heardCheckWords":[$words]}}}""",
        )

        runBlocking {
            val timing = LyricsTiming.timing(context, "song", songLines, baseAllowed = true, isVideo = true)
            assertNull(timing?.base)
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
}
