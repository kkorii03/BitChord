package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.DownloadedSyncCheck
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Replacing a downloaded file has to make it checkable again: the version that
 * said "already checked" is dropped, and the automatic override that still
 * held the old file's sheet goes with it. A listener's own override is kept.
 */
class LyricsTimingResetSongCheckTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }
    private val lines = listOf(
        LyricLine(1_000L, "one"),
        LyricLine(2_000L, "two"),
        LyricLine(3_000L, "three"),
    )

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-lyrics-reset-check").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
        AppSettings.syncSafetyDownloadRecheck.value = true
    }

    @After
    fun tearDown() {
        AppSettings.syncSafetyDownloadRecheck.value = false
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun writeCheckedEntry(videoId: String) {
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"$videoId":{"syncCheckVersion":1}}}""",
        )
    }

    @Test
    fun `a replaced file is unverified again and its automatic override is gone`() = runBlocking {
        writeCheckedEntry("video")
        LyricsOverrideStore.write(
            context,
            "video",
            lines,
            LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.RECHECK,
        )

        LyricsTiming.resetSongCheck(context, "video")

        assertNull(LyricsOverrideStore.read(context, "video"))
        val recheck = LyricsTiming.syncDiagnostics(
            context = context,
            videoId = "video",
            lines = lines,
            audioSource = null,
            downloaded = true,
            providerUnknown = false,
        ).second
        assertEquals(DownloadedSyncCheck.Recheck.UNVERIFIED.token, recheck)
    }

    @Test
    fun `a listener override is not removed by the file reset`() = runBlocking {
        writeCheckedEntry("video")
        LyricsOverrideStore.write(
            context,
            "video",
            lines,
            LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.USER,
        )

        LyricsTiming.resetSongCheck(context, "video")

        assertEquals(
            LyricsOverrideFile.Kind.USER,
            LyricsOverrideStore.read(context, "video")?.kind,
        )
    }
}
