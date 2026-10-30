package com.music.bitchord.widget

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The snapshot is what every widget draws, including after the process that
 * published it is gone. These pin the two cases that used to disagree with the
 * notification: a track with no recorded thumbnail but a YouTube identity, and
 * a queue that has gone empty.
 */
class MediaWidgetSnapshotTest {

    @Test
    fun `a restored row with no thumbnail uses the video still`() {
        val song = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
        )

        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            MediaWidgetSnapshot.artworkUrlFor(song),
        )
    }

    @Test
    fun `a recorded thumbnail is kept exactly as it was`() {
        val song = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = "file:///data/user/0/app/files/download-artwork/x.jpg",
        )

        assertEquals(
            "file:///data/user/0/app/files/download-artwork/x.jpg",
            MediaWidgetSnapshot.artworkUrlFor(song),
        )
    }

    @Test
    fun `a local file with no YouTube identity has no invented cover`() {
        val song = Song(
            videoId = "content://media/external/audio/media/42",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = "content://media/external/audio/media/42",
        )

        assertNull(MediaWidgetSnapshot.artworkUrlFor(song))
    }

    @Test
    fun `an empty queue publishes the empty snapshot instead of the last track`() {
        val snapshot = MediaWidgetSnapshot.fromPlayback(
            song = null,
            isPlaying = false,
            hasPrevious = false,
            hasNext = false,
            isLiked = false,
            canLike = false,
            shuffleEnabled = false,
        )

        assertEquals(MediaWidgetSnapshot.EMPTY, snapshot)
    }

    @Test
    fun `a playing song carries every displayed field`() {
        val song = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
        )

        val snapshot = MediaWidgetSnapshot.fromPlayback(
            song = song,
            isPlaying = true,
            hasPrevious = true,
            hasNext = false,
            isLiked = true,
            canLike = true,
            shuffleEnabled = true,
        )

        assertEquals("dQw4w9WgXcQ", snapshot.mediaId)
        assertEquals("Song", snapshot.title)
        assertEquals("Artist", snapshot.artist)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", snapshot.artworkUrl)
        assertEquals(true, snapshot.isPlaying)
        assertEquals(true, snapshot.hasPrevious)
        assertEquals(false, snapshot.hasNext)
        assertEquals(true, snapshot.isLiked)
        assertEquals(true, snapshot.canLike)
        assertEquals(true, snapshot.shuffleEnabled)
    }
}
