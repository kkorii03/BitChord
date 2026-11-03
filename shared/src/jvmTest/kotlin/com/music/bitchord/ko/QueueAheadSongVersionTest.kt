package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A queue set converts a bounded window of upcoming videos, then leaves the rest alone. */
class QueueAheadSongVersionTest {

    private fun video(index: Int) = Song(
        videoId = "v$index",
        title = "Song $index",
        artist = "Artist $index",
        thumbnailUrl = null,
        isVideo = true,
    )

    @Test
    fun `a queue set resolves the current and next five video rows`() = runBlocking {
        val rows = (0 until 10).map(::video)
        val lookedUp = java.util.Collections.synchronizedList(mutableListOf<String>())
        val out = KoSongVersion.resolveQueueAhead(rows, 0) { song ->
            lookedUp += song.videoId
            song.copy(videoId = "song-${song.videoId}")
        }

        assertEquals((0..5).map { "song-v$it" }, out.take(6).map { it.videoId })
        assertEquals((6..9).map { "v$it" }, out.drop(6).map { it.videoId })
        assertEquals((0..5).map { "v$it" }, lookedUp.sorted())
    }

    @Test
    fun `rows that are already songs are not looked up`() = runBlocking {
        val rows = listOf(video(0), video(1).copy(isVideo = false), video(2))
        val lookedUp = java.util.Collections.synchronizedList(mutableListOf<String>())
        val out = KoSongVersion.resolveQueueAhead(rows, 0) { song ->
            lookedUp += song.videoId
            song.copy(videoId = "song-${song.videoId}")
        }

        assertEquals(listOf("v0", "v2"), lookedUp.sorted())
        assertEquals("song-v0", out[0].videoId)
        assertEquals("v1", out[1].videoId)
        assertEquals("song-v2", out[2].videoId)
    }
}
