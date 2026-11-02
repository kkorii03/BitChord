package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A cover row can lose its video flag, but [CoverTitles.lyricsQuery] still
 * says it is a video. The next-song preparation must guard its stored answer
 * with that same flag: the screen reads the answer on the video key and
 * refuses a shift beyond the video limit, so preparation accepting it under
 * the record rule would keep an answer the screen can never show (11-02,
 * v59g4HRNIkc).
 */
class LyricsTimingNextPrepVideoGuardTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    private val song = Song(
        videoId = "v59g4HRNIkc",
        title = "에피톤 프로젝트 - 회전목마 / cover by 오화요(허니즈)",
        artist = "젤리의 노래저장소",
        thumbnailUrl = null,
        isVideo = false,
    )

    private val lines = listOf(
        LyricLine(1_000L, "one"),
        LyricLine(2_000L, "two"),
        LyricLine(3_000L, "three"),
    )

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-next-prep-video").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun writeAnswer() {
        val key = lines.joinToString("\n") { it.text }.hashCode()
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"v59g4HRNIkc":{
              "autoVersion":4,
              "autoComplete":true,
              "aiTextKey":$key,
              "ai":{"knots":[0,25000],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":97},
              "aiEngine":"Gemini"
            }}}
            """.trimIndent(),
        )
    }

    @Test
    fun `next prep does not accept a video answer beyond the video shift limit`() = runBlocking {
        writeAnswer()

        val timing = LyricsTiming.prepareNextFree(
            context = context,
            song = song,
            lines = lines,
            fromFile = false,
            source = LyricsSource.KUGOU,
            searchedAs = null,
        )

        assertNull("the record-rule guard accepted an answer the screen rejects", timing)
    }
}
