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
        LyricsTiming.resetForTest()
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

    private fun sheet(vararg texts: String) = texts.mapIndexed { i, text -> LyricLine(1_000L + i * 4_000L, text) }

    private fun keyOf(lines: List<LyricLine>) = lines.joinToString("\n") { it.text }.hashCode()

    private fun video(id: String) = Song(videoId = id, title = "Youngblood", artist = "Artist", thumbnailUrl = null, isVideo = true)

    /**
     * 11-09 -RJSbO8UZVY (loop 220-c): the quick answer for SyncLRC's sheet (94% agree,
     * +18.4 s, an MV intro) landed while the screen still claimed the VIBE sheet, and was
     * dropped; 0.16 s later the sheet judge showed SyncLRC with no base, 18.6 s early for
     * the whole play. That answer is SyncLRC's own fit, made by the same floors, so it is
     * taken back once that sheet is on screen: no request, nothing carried across sheets.
     */
    @Test
    fun `a quick answer dropped while another sheet was newest is taken back when its sheet shows`() = runBlocking {
        val shownFirst = sheet("one", "two", "three", "four")
        val shownNext = sheet("alpha", "bravo", "charlie", "delta")
        val fit = LyricsTiming.Adjustment.Mapped(
            com.music.bitchord.data.lyrics.LyricsTimeMap.shift(18_000L),
            byAi = true,
            rate = 1.0,
            matchPercent = 94,
        )
        val heard = shownNext.map {
            com.music.bitchord.data.lyrics.LyricsWordAlign.Heard(it.text, it.timeMs + 18_000L, it.timeMs + 18_400L)
        }
        skipFarm("swap")
        LyricsTiming.newestSongKey["swap"] = keyOf(shownFirst)

        val keptThen = LyricsTiming.keepQuickVideoAnswer(
            context, "swap", keyOf(shownNext), fit, heard, LyricsTiming.Clock.YouTube, rough = true,
        )
        assertEquals("another sheet was newest: not written then", false, keptThen)

        assertTrue("its sheet on screen takes it back", LyricsTiming.refit(context, video("swap"), shownNext, null))
        val base = LyricsTiming.timing(context, "swap", shownNext, baseAllowed = true, isVideo = true)?.base
        assertTrue("an AI base for the sheet showing", base?.byAi == true)
        assertEquals(18_000L, LyricsTiming.shownShiftMs(shownNext, base!!))
    }

    /** The answer is one sheet's fit: a third sheet coming up gets nothing from it. */
    @Test
    fun `a dropped quick answer never times another sheet`() = runBlocking {
        val shownFirst = sheet("one", "two", "three", "four")
        val dropped = sheet("alpha", "bravo", "charlie", "delta")
        val third = sheet("echo", "foxtrot", "golf", "hotel")
        val fit = LyricsTiming.Adjustment.Mapped(
            com.music.bitchord.data.lyrics.LyricsTimeMap.shift(18_000L),
            byAi = true,
            rate = 1.0,
            matchPercent = 94,
        )
        val heard = dropped.map {
            com.music.bitchord.data.lyrics.LyricsWordAlign.Heard(it.text, it.timeMs + 18_000L, it.timeMs + 18_400L)
        }
        skipFarm("other")
        LyricsTiming.newestSongKey["other"] = keyOf(shownFirst)
        LyricsTiming.keepQuickVideoAnswer(context, "other", keyOf(dropped), fit, heard, LyricsTiming.Clock.YouTube, rough = true)

        assertEquals(false, LyricsTiming.refit(context, video("other"), third, null))
        assertEquals(null, LyricsTiming.timing(context, "other", third, baseAllowed = true, isVideo = true)?.base)
    }
}
