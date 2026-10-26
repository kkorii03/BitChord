package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.DownloadedSyncCheck
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Replacing a downloaded file must forget the old file's check: the version
 * that makes recheck a no-op, and the re-check override that would keep the
 * old sheet. A listener's own override stays.
 */
class LyricsTimingResetSongCheckTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-reset-song-check").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun writeTiming(videoId: String, version: Int) {
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"$videoId":{"syncCheckVersion":$version}}}""",
        )
    }

    private fun lines() = listOf(
        LyricLine(0L, "one"),
        LyricLine(1_000L, "two"),
        LyricLine(2_000L, "three"),
    )

    @Test
    fun `a replaced file is rechecked again`() = runBlocking {
        writeTiming("file-id", DownloadedSyncCheck.SYNC_CHECK_VERSION)
        val source = LyricsTiming.audioSourceKey("file-id", localFile = true)
        assertEquals(
            DownloadedSyncCheck.Recheck.NONE.token,
            LyricsTiming.syncDiagnostics(
                context, "file-id", lines(), source, downloaded = true, providerUnknown = false,
            ).second,
        )

        LyricsTiming.resetSongCheck(context, "file-id")

        assertEquals(
            DownloadedSyncCheck.Recheck.UNVERIFIED.token,
            LyricsTiming.syncDiagnostics(
                context, "file-id", lines(), source, downloaded = true, providerUnknown = false,
            ).second,
        )
    }

    @Test
    fun `a replaced file drops the recheck override`() = runBlocking {
        LyricsOverrideStore.write(
            context,
            "recheck-id",
            lines(),
            LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.RECHECK,
        )
        assertNotNull(LyricsOverrideStore.read(context, "recheck-id"))

        LyricsTiming.resetSongCheck(context, "recheck-id")

        assertNull(LyricsOverrideStore.read(context, "recheck-id"))
    }

    @Test
    fun `a replaced file keeps the listener's own override`() = runBlocking {
        LyricsOverrideStore.write(
            context,
            "user-id",
            lines(),
            LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.USER,
        )

        LyricsTiming.resetSongCheck(context, "user-id")

        assertEquals(
            LyricsOverrideFile.Kind.USER,
            LyricsOverrideStore.read(context, "user-id")?.kind,
        )
    }
}
