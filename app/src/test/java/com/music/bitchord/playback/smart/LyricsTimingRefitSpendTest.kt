package com.music.bitchord.playback.smart

import android.content.Context
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
 * review-fit finding 1: refit judges words already kept and makes no request, yet it
 * marked Whisper as spent and cleared a waiting retry, so the song check that would have
 * heard the whole song never ran.
 */
class LyricsTimingRefitSpendTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val videoId = "refitSpendId"
    private val texts = listOf(
        "alpha bravo", "charlie delta", "echo foxtrot", "golf hotel", "india juliet", "kilo lima",
        "mike november", "oscar papa", "quebec romeo", "sierra tango", "uniform victor", "whiskey xray",
    )
    private val lines = texts.mapIndexed { i, t -> LyricLine(5_000L + i * 5_000L, t) }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-refit-spend").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    @Test
    fun `a refit spends no engine and keeps the waiting retry`() = runBlocking {
        val key = lines.joinToString("\n") { it.text }.hashCode()
        val words = lines.flatMap { line ->
            line.text.split(" ").mapIndexed { i, w ->
                val start = line.timeMs + i * 600L
                """{"t":"$w","s":$start,"e":${start + 500}}"""
            }
        }.joinToString(",")
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"$videoId":{
              "refineHeardKey":$key,
              "refineHeardWords":[$words],
              "songRetryKey":$key,
              "songRetryAtMs":123
            }}}
            """.trimIndent(),
        )

        LyricsTiming.refit(context, Song(videoId = videoId, title = "t", artist = "a", thumbnailUrl = null), lines, null)

        val store = storeField.get(LyricsTiming)
        val entries = store.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(store) as Map<*, *>
        val entry = entries[videoId] as LyricsTiming.Entry
        assertEquals("the refit ran on the kept words", key, entry.refitKey)
        assertTrue("no request was made", entry.aiAttemptedEngines.isEmpty())
        assertEquals(key, entry.songRetryKey)
    }
}
