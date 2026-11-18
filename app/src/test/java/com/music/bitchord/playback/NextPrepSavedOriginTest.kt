package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OPT2 spaghetti-3: next-prep reads a saved sheet's origin as the service and the lyrics
 * screen do (PlaybackService readSaved, LyricsController's KoLockSheet.chose), and times it
 * by the same rule (KoLockSheet.Sheet), so its first-frame lines carry the stored correction.
 */
class NextPrepSavedOriginTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val oldSources = AppSettings.lyricsSources.value
    private val oldFileLyrics = TranslationAhead.fileLyrics
    private val oldRecheck = AppSettings.syncSafetyDownloadRecheck.value
    private val lines = listOf(
        LyricLine(1_000L, "alpha"),
        LyricLine(2_000L, "bravo"),
        LyricLine(3_000L, "charlie"),
        LyricLine(4_000L, "delta"),
    )

    @Before
    fun setUp() {
        LyricsTiming.resetForTest()
        directory = Files.createTempDirectory("bitchord-next-prep-origin").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
        AppSettings.lyricsSources.value = emptySet() // no provider lookup in a unit test
        AppSettings.syncSafetyDownloadRecheck.value = true // the override store's read/write switch
    }

    @After
    fun tearDown() {
        AppSettings.syncSafetyDownloadRecheck.value = oldRecheck
        TranslationAhead.fileLyrics = oldFileLyrics
        AppSettings.lyricsSources.value = oldSources
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun song(id: String, localUri: String?) =
        Song(videoId = id, title = "Song", artist = "Artist", thumbnailUrl = null, localUri = localUri)

    @Test
    fun `a local file's own sheet is prepared as a re-check, as the service reads it`() = runBlocking {
        TranslationAhead.fileLyrics = { _, _ -> EmbeddedLyrics.Embedded(lines, LyricsSource.LRCLIB, "own") }

        val found = TranslationAhead.lyricsForPrep(context, song("own", "file:///tmp/own.m4a"), allowHeavy = false)

        assertEquals(lines, found.lines)
        assertTrue(found.fromFile)
        assertTrue(found.recheckOverride) // old code: false
    }

    @Test
    fun `a streamed song's own pick is not prepared as a file's sheet`() = runBlocking {
        assertTrue(LyricsOverrideStore.write(context, "streamed", lines, LyricsSource.LRCLIB, kind = LyricsOverrideFile.Kind.USER))

        val found = TranslationAhead.lyricsForPrep(context, song("streamed", localUri = null), allowHeavy = false)

        assertEquals(lines, found.lines)
        assertFalse(found.fromFile) // old code: true, so its stored sync was refused as file-timed
    }

    @Test
    fun `a downloaded song's override still counts as the file's`() = runBlocking {
        assertTrue(LyricsOverrideStore.write(context, "local", lines, LyricsSource.LRCLIB, kind = LyricsOverrideFile.Kind.RECHECK))

        val found = TranslationAhead.lyricsForPrep(context, song("local", "file:///tmp/local.m4a"), allowHeavy = false)

        assertTrue(found.fromFile)
        assertTrue(found.recheckOverride)
    }

    private fun writeFileKeyShift(videoId: String, shiftMs: Long) {
        val fileKey = LyricsTiming.audioSourceKey(videoId, localFile = true)
        val key = 31 * fileKey.hashCode() + lines.joinToString("\n") { it.text }.hashCode()
        File(directory, "lyrics_timing_v1.json")
            .writeText("""{"entries":{"$videoId":{"aiTextKey":$key,"aiShiftMs":$shiftMs}}}""")
    }

    @Test
    fun `a re-checked file sheet is prepared with the stored base the screen applies`() = runBlocking {
        val id = "rechecked"
        writeFileKeyShift(id, 800L)

        val timing = LyricsTiming.prepareNextFree(
            context = context,
            song = song(id, "file:///tmp/$id.m4a"),
            lines = lines,
            fromFile = true,
            source = LyricsSource.LRCLIB,
            searchedAs = null,
            recheckOverride = true,
        )

        // Old code: baseAllowed = !fromFile || isVideo = false, so base was null (file-own-timing@next-free).
        assertEquals(LyricsTiming.Adjustment.Shift(800L, byAi = true), timing?.base)
    }

    @Test
    fun `a file's sheet that no re-check wrote keeps today's refusal`() = runBlocking {
        val id = "own-timing"
        writeFileKeyShift(id, 800L)

        val timing = LyricsTiming.prepareNextFree(
            context = context,
            song = song(id, "file:///tmp/$id.m4a"),
            lines = lines,
            fromFile = true,
            source = LyricsSource.LRCLIB,
            searchedAs = null,
        )

        assertNull(timing?.base)
    }
}
