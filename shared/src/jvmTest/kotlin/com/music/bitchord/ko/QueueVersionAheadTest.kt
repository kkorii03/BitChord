package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The queue look-ahead swaps MV rows for the song versions it resolved, but a
 * cover the listener chose stays the cover and an unresolved row stays as it
 * was. Pure list application, no player and no network.
 */
class QueueVersionAheadTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Artist",
        isVideo: Boolean = true,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    @Test
    fun `resolved rows become their song versions`() {
        val rows = listOf(song("mv1", "One"), song("mv2", "Two"), song("mv3", "Three"))
        val versions = listOf(0 to song("song1", "One"), 1 to song("song2", "Two"))
        val out = KoSongVersion.applyQueueVersions(rows, versions)
        assertEquals(listOf("song1", "song2", "mv3"), out.map { it.videoId })
    }

    @Test
    fun `a cover row keeps the video the listener chose`() {
        val rows = listOf(song("cover", "Song (Cover)"), song("mv", "Song"))
        val versions = listOf(0 to song("song0", "Song"), 1 to song("song1", "Song"))
        val out = KoSongVersion.applyQueueVersions(rows, versions)
        assertEquals(listOf("cover", "song1"), out.map { it.videoId })
    }

    @Test
    fun `an unresolved row is left untouched and the same list is returned`() {
        val rows = listOf(song("mv1", "One"), song("mv2", "Two"))
        val out = KoSongVersion.applyQueueVersions(rows, listOf(0 to null, 1 to null))
        assertSame(rows, out)
    }
}
