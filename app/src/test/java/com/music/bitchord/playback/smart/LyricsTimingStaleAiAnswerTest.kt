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
 * TR2 T2: the AI ask and a video's quick listen run without the song-check gate.
 * When the download is replaced while they hear it ([LyricsTiming.resetSongCheck]),
 * what they bring back is about the old bytes. Written under the same key, the
 * old file's map became the new file's answer, and the ask was never made again.
 */
class LyricsTimingStaleAiAnswerTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val lines = listOf(LyricLine(1_000L, "p1"), LyricLine(2_000L, "p2"), LyricLine(3_000L, "p3"))
    private val key = lines.joinToString("\n") { it.text }.hashCode()
    private val fit = LyricsTiming.Adjustment.Mapped(LyricsTimeMap.shift(500L), byAi = true, rate = 1.0, matchPercent = 90)
    private val heard = listOf(
        LyricsWordAlign.Heard("p1", 1_500L, 1_700L),
        LyricsWordAlign.Heard("p2", 2_500L, 2_700L),
        LyricsWordAlign.Heard("p3", 3_500L, 3_700L),
    )

    @Before
    fun setUp() {
        LyricsTiming.resetForTest()
        directory = Files.createTempDirectory("bitchord-stale-ai").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private suspend fun aiBase() =
        LyricsTiming.timing(context, "video", lines, baseAllowed = true, measuredAllowed = true, isVideo = true)?.base

    @Test
    fun `a quick answer heard from a file replaced meanwhile is not kept`() = runBlocking {
        // The quick listen begins on the old file...
        val started = LyricsTiming.songSyncGenerationOf("video")
        // ...and the download is replaced before its answer comes back.
        LyricsTiming.resetSongCheck(context, "video")

        assertFalse(
            LyricsTiming.keepQuickVideoAnswer(
                context, "video", key, fit, heard, LyricsTiming.Clock.YouTube, rough = false, generation = started,
            ),
        )
        // Old code wrote the old file's +500 ms map as the new file's answer.
        assertFalse(aiBase()?.byAi == true)
    }

    @Test
    fun `quick words heard from a file replaced meanwhile are not kept as evidence`() = runBlocking {
        val started = LyricsTiming.songSyncGenerationOf("video")
        LyricsTiming.resetSongCheck(context, "video")

        assertFalse(
            LyricsTiming.keepQuickVideoEvidence(
                context, "video", key, heard, LyricsTiming.Clock.YouTube, rough = false, generation = started,
            ),
        )
        assertTrue(LyricsTiming.heardWordsFor(context, "video").isEmpty())
    }

    // Review 11-18: the automatic flight key had no generation, so an ask for
    // the new file joined the old file's flight, whose write is dropped.
    @Test
    fun `an ask after the file is replaced does not join the old file's flight`() = runBlocking {
        val before = LyricsTiming.aiFlightKey("video", key, automatic = true)
        assertEquals(before, LyricsTiming.aiFlightKey("video", key, automatic = true))
        LyricsTiming.resetSongCheck(context, "video")
        val after = LyricsTiming.aiFlightKey("video", key, automatic = true)
        assertFalse(before == after)
        assertFalse(after == LyricsTiming.aiFlightKey("video", key, automatic = false))
        // The busy check still finds the flight by its video prefix.
        assertTrue(after.startsWith("video:"))
        var root = File(System.getProperty("user.dir")).absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) root = root.parentFile ?: error("repo root not found")
        val source = File(root, "app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt").readText()
        assertTrue("placeWithAi keys its flight by generation", "val flight = aiFlightKey(videoId, key, automatic)" in source)
    }

    @Test
    fun `a quick answer from the file still there is kept`() = runBlocking {
        LyricsTiming.resetSongCheck(context, "video")
        val started = LyricsTiming.songSyncGenerationOf("video")

        assertTrue(
            LyricsTiming.keepQuickVideoAnswer(
                context, "video", key, fit, heard, LyricsTiming.Clock.YouTube, rough = false, generation = started,
            ),
        )
        val base = aiBase()
        assertTrue(base?.byAi == true)
        assertEquals(500L, LyricsTiming.shownShiftMs(lines, base!!))
    }
}
