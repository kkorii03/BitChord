package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 11-02 sync-g: the late song-version event must reuse an answer only when it
 * was measured against the very original it now found. A stored answer from a
 * guessed original, or an opening-only pass, must not stop the authoritative
 * pairing from being measured once.
 *
 * The store is seeded with raw JSON so this test compiles against the code
 * before the original was kept too; there the answer was reused for any
 * original, so the first test fails.
 */
class LyricsTimingLateVersionReuseTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-late-version").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun seedAnswer(original: String?) {
        val id = original?.let { "\"$it\"" } ?: "null"
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"video":{"autoVersion":4,"autoComplete":true,""" +
                """"auto":{"knots":[0,4200],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":90},""" +
                """"autoOriginal":$id}}}""",
        )
    }

    private fun video() = Song(
        videoId = "video",
        title = "Song",
        artist = "Artist",
        thumbnailUrl = null,
        isVideo = true,
        localUri = File(directory, "missing.opus").absolutePath,
    )

    private fun original(id: String) = Song(
        videoId = id,
        title = "Song",
        artist = "Artist",
        thumbnailUrl = null,
    )

    @Test
    fun `the authoritative original is measured over an answer from another one`() = runBlocking {
        seedAnswer("guessed")
        val before = TrackLog.everything().size
        LyricsTiming.measureLateVersion(context, video(), original("authoritative"), 200_000L)
        val lines = TrackLog.everything().drop(before)
        assertTrue(lines.any { it.contains("late version authoritative -> measuring") })
    }

    @Test
    fun `an answer already kept for this original is reused`() = runBlocking {
        seedAnswer("authoritative")
        val before = TrackLog.everything().size
        LyricsTiming.measureLateVersion(context, video(), original("authoritative"), 200_000L)
        val lines = TrackLog.everything().drop(before)
        assertFalse(lines.any { it.contains("-> measuring") })
    }
}
