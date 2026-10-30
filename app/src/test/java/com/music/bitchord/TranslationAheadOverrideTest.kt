package com.music.bitchord

import android.content.ContextWrapper
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.TranslationAhead
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A re-checked sheet must be what the next-song bundle prepares from, not the file's older tags. */
class TranslationAheadOverrideTest {

    @Test
    fun anOverrideBeatsTheDownloadedFilesOwnTags() = runBlocking {
        val dir = Files.createTempDirectory("lyrics-override").toFile()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
            override fun getApplicationContext() = this
        }
        val oldSources = AppSettings.lyricsSources.value
        val oldRecheck = AppSettings.syncSafetyDownloadRecheck.value
        try {
            AppSettings.syncSafetyDownloadRecheck.value = true
            AppSettings.lyricsSources.value = emptySet()
            val override = listOf(LyricLine(timeMs = 1_000L, text = "override"))
            assertTrue(
                LyricsOverrideStore.write(
                    context,
                    videoId = "video",
                    lines = override,
                    source = LyricsSource.LRCLIB,
                ),
            )
            val song = Song(
                videoId = "video",
                title = "Song",
                artist = "Artist",
                thumbnailUrl = null,
                localUri = "file:///does-not-exist",
            )
            val ahead = TranslationAhead.lyricsForPrep(context, song, allowHeavy = false)
            assertEquals("override", ahead.lines?.single()?.text)
            assertTrue(ahead.fromFile)
        } finally {
            AppSettings.lyricsSources.value = oldSources
            AppSettings.syncSafetyDownloadRecheck.value = oldRecheck
            dir.deleteRecursively()
        }
    }
}
