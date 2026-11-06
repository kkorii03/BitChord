package com.music.bitchord.playback.smart

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 11-06 7PgA6kyqVTc: the sheet changed while the words were being heard. The
 * refit of the new sheet was dropped as an answer for the sheet it replaced,
 * so the new sheet kept no base. The newest sheet must be fitted from the
 * words already kept, without another request.
 */
class LyricsTimingRefitReplacedSheetTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-refit-replaced").toFile()
        val fake = FakeContext.create(FakePreferences(), directory)
        context = object : Application() {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                fake.getSharedPreferences(name, mode)
        }
        storeField.set(LyricsTiming, null)
        LyricsTiming.newestSongKey.clear()
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        LyricsTiming.newestSongKey.clear()
        directory.deleteRecursively()
    }

    /** The farm has already been asked for this video, so it makes no request. */
    private fun skipFarm(videoId: String) {
        val field = AlignFarm::class.java.getDeclaredField("asked").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(AlignFarm) as MutableSet<String>).add(videoId)
    }

    private fun seed(videoId: String, key: Int, words: String) {
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"$videoId":{"clockVersion":1,"refineHeardKey":$key,""" +
                """"refineHeardWords":[$words],"refineHeardClock":"yt","refineHeardSeconds":20.0,""" +
                """"refineHeardExtended":true}}}""",
        )
    }

    @Test
    fun `the newest sheet is fitted from kept words after a replacement`() = runBlocking {
        val newLines = listOf(
            LyricLine(10_000L, "alpha"),
            LyricLine(13_000L, "bravo"),
            LyricLine(16_000L, "charlie"),
            LyricLine(19_000L, "delta"),
        )
        val newKey = newLines.joinToString("\n") { it.text }.hashCode()
        val oldLines = listOf(
            LyricLine(5_000L, "one"),
            LyricLine(8_000L, "two"),
            LyricLine(11_000L, "three"),
            LyricLine(14_000L, "four"),
        )
        val oldKey = oldLines.joinToString("\n") { it.text }.hashCode()
        val words = newLines.joinToString(",") { line ->
            """{"t":"${line.text}","s":${line.timeMs},"e":${line.timeMs + 600}}"""
        }
        seed("video", newKey, words)
        skipFarm("video")
        // The last song check was asked about the sheet that was replaced.
        LyricsTiming.newestSongKey["video"] = oldKey

        val song = Song(
            videoId = "video",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
            isVideo = true,
        )
        val fitted = LyricsTiming.refit(context, song, newLines, null)

        assertTrue("the new sheet is fitted from the kept words", fitted)
        assertEquals(newKey, LyricsTiming.newestSongKey["video"])
    }
}
