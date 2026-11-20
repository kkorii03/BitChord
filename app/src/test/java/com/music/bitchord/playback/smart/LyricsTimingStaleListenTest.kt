package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TR2 T1: a refine listen runs for up to several minutes without the song-check
 * gate. When the download is replaced meanwhile ([LyricsTiming.resetSongCheck]),
 * the words it brings back are the old bytes'. Saved as the new file's complete
 * transcript, they stopped the new file from ever being listened to again.
 */
class LyricsTimingStaleListenTest {

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
    private val key = lines.joinToString("\n") { it.text }.hashCode()
    private val words = lines.map { LyricsTiming.SavedWord(it.text, it.timeMs + 300L, it.timeMs + 450L) }
    private val untimed = LyricsTiming.Timing(base = null, manualMs = 0L)

    @Before
    fun setUp() {
        LyricsTiming.resetForTest()
        directory = Files.createTempDirectory("bitchord-stale-listen").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private suspend fun save(started: Long) = LyricsTiming.saveRefineHeard(
        context,
        "video",
        null,
        key,
        words,
        LyricsTiming.Clock.Unknown,
        200.0,
        extended = true,
        startedGeneration = started,
    )

    @Test
    fun `words heard from a file replaced during the listen are not kept`() = runBlocking {
        // The listen begins on the old file...
        val started = LyricsTiming.songSyncGenerationOf("video")
        // ...and the download is replaced before its words come back.
        LyricsTiming.resetSongCheck(context, "video")

        assertNull(save(started))
        // Old code saved them as the whole song's words: "known", never heard again.
        assertFalse(LyricsTiming.refineHeardKnown(context, "video", lines, untimed))
    }

    @Test
    fun `a listen with no replacement still keeps its words`() = runBlocking {
        val started = LyricsTiming.songSyncGenerationOf("video")

        assertNotNull(save(started))
        assertTrue(LyricsTiming.refineHeardKnown(context, "video", lines, untimed))
    }

    /**
     * Phone 11-20 itBbxKe6Z50: the wrong recording was repaired while the refine listened,
     * the drop was right, but nothing listened again, so the song stayed sync=none all play.
     */
    @Test
    fun `a listen dropped by a replacement is heard once more on the new file`() = runBlocking {
        val calls = AtomicInteger()
        val started = LyricsTiming.songSyncGenerationOf("video")
        val heard = LyricsTiming.refineListenOnce(context, "video", started, null) { generation, _ ->
            if (calls.incrementAndGet() == 1) LyricsTiming.resetSongCheck(context, "video")
            save(generation)
        }

        assertEquals(2, calls.get())
        assertNotNull(heard)
        assertEquals(LyricsTiming.songSyncGenerationOf("video"), heard!!.second)
        assertTrue(LyricsTiming.refineHeardKnown(context, "video", lines, untimed))
    }

    @Test
    fun `a second replacement is not chased with a third listen`() = runBlocking {
        val calls = AtomicInteger()
        val started = LyricsTiming.songSyncGenerationOf("video")
        val heard = LyricsTiming.refineListenOnce(context, "video", started, null) { generation, _ ->
            calls.incrementAndGet()
            LyricsTiming.resetSongCheck(context, "video")
            save(generation)
        }

        assertEquals(2, calls.get())
        assertNull(heard)
        assertFalse(LyricsTiming.refineHeardKnown(context, "video", lines, untimed))
    }

    @Test
    fun `a listen that heard nothing without a replacement is not repeated`() = runBlocking {
        val calls = AtomicInteger()
        val started = LyricsTiming.songSyncGenerationOf("video")
        val heard = LyricsTiming.refineListenOnce(context, "video", started, null) { _, _ ->
            calls.incrementAndGet()
            null
        }

        assertEquals(1, calls.get())
        assertNull(heard)
    }

    @Test
    fun `a listen that began after the replacement keeps its words`() = runBlocking {
        LyricsTiming.resetSongCheck(context, "video")
        val started = LyricsTiming.songSyncGenerationOf("video")

        assertNotNull(save(started))
        assertTrue(LyricsTiming.refineHeardKnown(context, "video", lines, untimed))
    }
}
