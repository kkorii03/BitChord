package com.music.bitchord.ui

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parts of a playlist edit that used to fail without telling anyone: a
 * Spotify import that lost a batch past the first fifty, and a "remove from
 * playlist" on a row with no set-video-id.
 */
class PlaylistEditRulesTest {

    private fun song(setVideoId: String? = null) = Song(
        videoId = "video",
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        setVideoId = setVideoId,
    )

    @Test
    fun `a failed remainder chunk contributes all of its songs to the shortfall`() = runBlocking {
        val videoIds = (1..120).map { "v$it" }
        val batches = mutableListOf<Int>()

        val failed = addPlaylistRemainderChunks(videoIds) { chunk ->
            batches += chunk.size
            if (batches.size == 2) {
                Result.failure<Unit>(IllegalStateException("rate limited"))
            } else {
                Result.success(Unit)
            }
        }

        assertEquals(listOf(50, 20), batches)
        assertEquals(20, failed)
    }

    @Test
    fun `a remainder that is all accepted reports no shortfall`() = runBlocking {
        val videoIds = (1..120).map { "v$it" }

        val failed = addPlaylistRemainderChunks(videoIds) {
            Result.success(Unit)
        }

        assertEquals(0, failed)
    }

    @Test
    fun `the first fifty songs are not sent again by the remainder step`() = runBlocking {
        val videoIds = (1..50).map { "v$it" }
        var calls = 0

        val failed = addPlaylistRemainderChunks(videoIds) {
            calls += 1
            Result.success(Unit)
        }

        assertEquals(0, calls)
        assertEquals(0, failed)
    }

    @Test
    fun `a removal row without a set video id reports failure instead of vanishing`() {
        var failures = 0

        val entry = playlistRemovalEntry(song(setVideoId = null), signedIn = true) { failures += 1 }

        assertNull(entry)
        assertEquals(1, failures)
    }

    @Test
    fun `a removal row with a set video id is sent unchanged`() {
        var failures = 0

        val entry = playlistRemovalEntry(song(setVideoId = "set-1"), signedIn = true) { failures += 1 }

        assertEquals("set-1" to "video", entry)
        assertEquals(0, failures)
    }

    @Test
    fun `signed out removal stays behind the sign-in gate`() {
        var failures = 0

        val entry = playlistRemovalEntry(song(setVideoId = null), signedIn = false) { failures += 1 }

        assertNull(entry)
        assertEquals(0, failures)
    }
}
