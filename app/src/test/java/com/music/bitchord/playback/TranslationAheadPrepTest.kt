package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
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
 * The next-song bundle must read the same sheet the player will show. A
 * re-checked override used to be skipped here, so the bundle timed the file's
 * old tags under the old key and the new sheet started with no sync.
 */
class TranslationAheadPrepTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val lines = listOf(
        LyricLine(1_000L, "one"),
        LyricLine(2_000L, "two"),
        LyricLine(3_000L, "three"),
    )

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-ahead-prep").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        AppSettings.syncSafetyDownloadRecheck.value = true
    }

    @After
    fun tearDown() {
        AppSettings.syncSafetyDownloadRecheck.value = false
        directory.deleteRecursively()
    }

    @Test
    fun `a rechecked override is the sheet the bundle times`() = runBlocking {
        LyricsOverrideStore.write(
            context,
            "video",
            lines,
            LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.RECHECK,
        )
        val song = Song(
            videoId = "video",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = "file:///tmp/video.m4a",
        )

        val found = TranslationAhead.lyricsForPrep(context, song, allowHeavy = false)

        assertEquals(lines, found.lines)
        assertTrue(found.fromFile)
        assertTrue(found.recheckOverride)
    }

    @Test
    fun `a listener override is still not marked as a recheck`() = runBlocking {
        LyricsOverrideStore.write(
            context,
            "video",
            lines,
            LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.USER,
        )
        val song = Song(
            videoId = "video",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = "file:///tmp/video.m4a",
        )

        val found = TranslationAhead.lyricsForPrep(context, song, allowHeavy = false)

        assertEquals(lines, found.lines)
        assertFalse(found.recheckOverride)
    }
}
