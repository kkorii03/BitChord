package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The local-first rules for Recently played: ordering, de-duplication, and when
 * youtube.com history is allowed to fill the rest.
 */
class RecentShelfTest {

    private fun song(id: String, cover: String? = null) =
        Song(videoId = id, title = "Song $id", artist = "Artist", thumbnailUrl = cover)

    private fun play(id: String, at: Long, cover: String? = null) = LocalPlay(song(id, cover), at)

    @Test
    fun `local plays are newest first and distinct by video id`() {
        val songs = RecentShelf.localSongs(
            listOf(
                play("a", 100, cover = "old-a"),
                play("b", 300),
                play("a", 400, cover = "new-a"),
                play("c", 200),
            ),
            limit = 3,
        )

        assertEquals(listOf("a", "b", "c"), songs.map { it.videoId })
        // The cover of the most recent play is the one kept.
        assertEquals("new-a", songs.first().thumbnailUrl)
    }

    @Test
    fun `the local shelf stops at twenty`() {
        val plays = (1..25).map { play("v$it", it.toLong()) }

        val songs = RecentShelf.localSongs(plays)

        assertEquals(20, songs.size)
        assertEquals("v25", songs.first().videoId)
    }

    @Test
    fun `a local shelf of eight is not topped up from youtube history`() {
        val local = (1..8).map { song("l$it", cover = "local-$it") }
        val remote = (1..5).map { song("r$it") }

        val merged = RecentShelf.merge(local, remote)

        assertEquals(local.map { it.videoId }, merged.map { it.videoId })
        assertEquals("local-1", merged.first().thumbnailUrl)
    }

    @Test
    fun `a short local shelf is topped up without replacing local covers`() {
        val local = listOf(song("a", cover = "local-a"), song("b", cover = "local-b"))
        val remote = listOf(song("a", cover = "remote-a"), song("c"), song("d"))

        val merged = RecentShelf.merge(local, remote)

        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.videoId })
        assertEquals("local-a", merged.first().thumbnailUrl)
    }

    @Test
    fun `duplicate local rows do not make a full shelf`() {
        val local = listOf(song("a"), song("a"), song("b"), song("b"), song("c"))
        val remote = listOf(song("d"), song("e"))

        val merged = RecentShelf.merge(local, remote)

        assertEquals(listOf("a", "b", "c", "d", "e"), merged.map { it.videoId })
    }

    @Test
    fun `the merged list stops at the limit`() {
        val local = listOf(song("a"))
        val remote = (1..10).map { song("r$it") }

        val merged = RecentShelf.merge(local, remote, limit = 5)

        assertEquals(5, merged.size)
        assertEquals(listOf("a", "r1", "r2", "r3", "r4"), merged.map { it.videoId })
    }

    @Test
    fun `the just-played track leads local newest first, then remote without repeats`() {
        val plays = listOf(
            play("old-local", 100),
            play("just-played", 500, cover = "local-cover"),
            play("middle-local", 300),
        )
        val remote = listOf(
            song("just-played", cover = "remote-cover"),
            song("remote-older"),
            song("middle-local"),
            song("remote-newer"),
        )

        val merged = RecentShelf.merge(RecentShelf.localSongs(plays), remote)

        assertEquals(
            listOf("just-played", "middle-local", "old-local", "remote-older", "remote-newer"),
            merged.map { it.videoId },
        )
        // The local row wins even when the account history also has that id.
        assertEquals("local-cover", merged.first().thumbnailUrl)
    }
}
