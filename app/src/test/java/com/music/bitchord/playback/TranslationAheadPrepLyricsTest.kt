package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakeContext
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAheadPrepLyricsTest {

    @Test
    fun `next prep reads a per-song override before the file's own lyrics`() = runBlocking {
        val root = File(
            System.getProperty("java.io.tmpdir"),
            "bitchord-prep-${System.nanoTime()}",
        ).apply { mkdirs() }
        val packageDir = File(root, "offline").apply { mkdirs() }
        val playlist = File(packageDir, "track.m3u8").apply { writeText("#EXTM3U\n") }
        File(packageDir, "lyrics.lrc").writeText("[00:01.00]file line")

        val context = FakeContext.create(filesDir = File(root, "files"))
        val song = Song(
            videoId = "video-id",
            title = "title",
            artist = "artist",
            thumbnailUrl = null,
            localUri = playlist.toURI().toString(),
        )
        LyricsOverrideStore.write(
            context = context,
            videoId = song.videoId,
            lines = listOf(LyricLine(timeMs = 2_000L, text = "override line")),
            source = LyricsSource.LRCLIB,
        )

        val found = TranslationAhead.lyricsForPrep(context, song, allowHeavy = false)

        assertEquals(listOf("override line"), found.lines?.map { it.text })
        assertEquals(LyricsSource.LRCLIB, found.source)
        assertTrue(found.fromFile)
    }
}
