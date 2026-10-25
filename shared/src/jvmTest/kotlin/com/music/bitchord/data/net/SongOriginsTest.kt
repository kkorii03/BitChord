package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigins
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows keep their YouTube account actions.
 *
 * A finished download and the listener's own file both have a `localUri`, and
 * treating those alike is what emptied a downloaded track's menu. The id, not
 * the file, is what says whether the account can act on it.
 */
class SongOriginsTest {

    private fun song(
        videoId: String,
        localUri: String? = null,
        thumbnailUrl: String? = null,
    ) = Song(
        videoId = videoId,
        title = "Unholy",
        artist = "Sam Smith, Kim Petras",
        thumbnailUrl = thumbnailUrl,
        localUri = localUri,
    )

    @Test
    fun `a downloaded YouTube track keeps its account actions`() {
        val downloaded = song(
            videoId = "1V6p7E2v2Xo",
            localUri = "content://media/external/audio/media/41",
            thumbnailUrl = "content://media/external/audio/albumart/7",
        )

        assertTrue(SongOrigins.isDownloadedYouTube(downloaded))
        assertFalse(SongOrigins.isLocalFile(downloaded))
        assertTrue(SongOrigins.showsAccountActions(downloaded))
    }

    @Test
    fun `a local mp3 hides the account actions`() {
        val local = song(
            videoId = "content://media/external/audio/media/42",
            localUri = "content://media/external/audio/media/42",
            thumbnailUrl = "content://media/external/audio/albumart/8",
        )

        assertFalse(SongOrigins.isDownloadedYouTube(local))
        assertTrue(SongOrigins.isLocalFile(local))
        assertFalse(SongOrigins.showsAccountActions(local))
    }

    @Test
    fun `an SMB file with no video id hides the account actions`() {
        val smb = song(
            videoId = "smb://server/Music/Artist/Album/track.flac",
            localUri = "smb://server/Music/Artist/Album/track.flac",
        )

        assertFalse(SongOrigins.isDownloadedYouTube(smb))
        assertTrue(SongOrigins.isLocalFile(smb))
        assertFalse(SongOrigins.showsAccountActions(smb))
    }

    @Test
    fun `a streamed track keeps its account actions`() {
        val streamed = song(videoId = "wnRde8OQB6s")

        assertFalse(SongOrigins.isDownloadedYouTube(streamed))
        assertFalse(SongOrigins.isLocalFile(streamed))
        assertTrue(SongOrigins.showsAccountActions(streamed))
    }
}
