package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A restore replaces the timing memory while the store may already have a
 * debounced save queued. The restored file has to win, otherwise a device
 * migration lands the old phone's entries again a moment after the import.
 */
class LyricsTimingRestoreBackupTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-lyrics-restore").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    @Test
    fun `a save queued before the restore cannot replace the restored file`() = runBlocking {
        // Schedules the store's debounced save (800 ms) with this entry.
        LyricsTiming.setManual(context, "old", 1_000L)

        val restored = """{"entries":{"fresh":{"manualMs":5000}}}"""
        assertTrue(LyricsTiming.restoreBackupText(context, restored))

        // Let the queued save fire and write whatever it decided to keep.
        delay(1_500L)

        val text = File(directory, "lyrics_timing_v1.json").readText()
        assertTrue("the restored entry must still be there", text.contains("fresh"))
        assertFalse("the pre-restore entry must not come back", text.contains("old"))
    }

    @Test
    fun `a malformed restore leaves the existing timing memory alone`() = runBlocking {
        LyricsTiming.setManual(context, "kept", 2_000L)
        delay(1_000L)

        assertFalse(LyricsTiming.restoreBackupText(context, "{not json"))

        val text = File(directory, "lyrics_timing_v1.json").readText()
        assertTrue(text.contains("kept"))
    }
}
