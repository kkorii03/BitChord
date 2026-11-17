package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KO] C6a-1: a device file's menu offers Like and the device playlist rows,
 * signed in or not. The old gate `signedIn && !isLocalFile` hid them for good,
 * so a device playlist made on the Device tab could never be filled.
 */
class KoSongMenuRowsTest {

    private val deviceUri = "content://media/external/audio/media/1"
    private val deviceFile = Song(
        videoId = deviceUri,
        title = "t",
        artist = "a",
        thumbnailUrl = null,
        localUri = deviceUri,
    )
    private val streamed = Song(videoId = "dQw4w9WgXcQ", title = "t", artist = "a", thumbnailUrl = null)

    @Test
    fun `device file offers its playlists signed out and signed in`() {
        assertTrue(KoSongMenuRows.showsAddToPlaylist(false, deviceFile))
        assertTrue(KoSongMenuRows.showsAddToPlaylist(true, deviceFile))
        assertTrue(KoSongMenuRows.showsRemoveFromPlaylist(false, deviceFile))
    }

    @Test
    fun `device file can be liked into the device Liked list`() {
        assertTrue(KoSongMenuRows.showsLike(false, deviceFile))
        assertTrue(KoSongMenuRows.showsLike(true, deviceFile))
    }

    @Test
    fun `signed out stream keeps the account rows hidden`() {
        assertFalse(KoSongMenuRows.showsAddToPlaylist(false, streamed))
        assertFalse(KoSongMenuRows.showsRemoveFromPlaylist(false, streamed))
        assertFalse(KoSongMenuRows.showsLike(false, streamed))
        assertTrue(KoSongMenuRows.showsAddToPlaylist(true, streamed))
        assertTrue(KoSongMenuRows.showsLike(true, streamed))
    }

    @Test
    fun `a remote library row is not sent to a playlist picker that cannot take it`() {
        val smb = Song(
            videoId = "smb://nas/music/a.flac",
            title = "t",
            artist = "a",
            thumbnailUrl = null,
            localUri = "smb://nas/music/a.flac",
        )
        assertFalse(KoSongMenuRows.showsAddToPlaylist(true, smb))
        assertFalse(KoSongMenuRows.showsAddToPlaylist(false, smb))
    }
}
