package com.music.bitchord.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The part of a playlist edit that used to fail without telling anyone: a
 * Spotify import that lost a batch past the first fifty.
 */
class PlaylistEditRulesTest {

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
}
