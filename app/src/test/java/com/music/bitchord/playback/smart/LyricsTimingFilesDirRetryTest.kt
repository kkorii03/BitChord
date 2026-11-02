package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A cold start can ask for the timing store before the files directory exists.
 * A store built then has no file, so it must not be remembered: once the
 * directory appears, the next update has to reach the disk.
 */
class LyricsTimingFilesDirRetryTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-lyrics-retry").toFile()
        context = FakeContext.create(FakePreferences(), filesDir = null)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    @Test
    fun `a store built without a files directory is retried once it exists`() = runBlocking {
        LyricsTiming.setManual(context, "early", 1_000L)
        assertNull("a store that cannot save must not be cached", storeField.get(LyricsTiming))

        FakeContext.setFilesDir(directory)
        LyricsTiming.setManual(context, "late", 2_000L)
        delay(1_500L)

        val text = File(directory, "lyrics_timing_v1.json").readText()
        assertTrue("the update after the directory appeared must be saved", text.contains("late"))
    }

    @Test
    fun `an export without a files directory does not read a relative path`() = runBlocking {
        val relative = File("lyrics_timing_v1.json")
        relative.writeText("""{"entries":{"wrong":{"manualMs":1}}}""")
        try {
            assertNull(LyricsTiming.exportBackupText(context))
        } finally {
            relative.delete()
        }
    }
}
