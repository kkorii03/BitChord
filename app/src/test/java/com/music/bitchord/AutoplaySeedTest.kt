package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.autoplayRefillNeedsLoad
import com.music.bitchord.playback.needsYoutubeSeedLookup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplaySeedTest {

    private fun song(videoId: String, localUri: String? = null) =
        Song(
            videoId = videoId,
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = localUri,
        )

    @Test
    fun `device and file-server rows need a real youtube seed`() {
        assertTrue(needsYoutubeSeedLookup(song("content://media/external/audio/media/42", "content://media/external/audio/media/42")))
        assertTrue(needsYoutubeSeedLookup(song("file:///storage/emulated/0/Music/a.flac", "file:///storage/emulated/0/Music/a.flac")))
        assertTrue(needsYoutubeSeedLookup(song("smb:nas/Music/a.flac", "smb://nas/Music/a.flac")))
        assertTrue(needsYoutubeSeedLookup(song("webdav:https://nas/Music/a.flac", "https://nas/Music/a.flac")))
    }

    @Test
    fun `a refill starts a load unless one is already running for this track`() {
        assertTrue(autoplayRefillNeedsLoad(loadActive = false, loadedSeed = null, mediaId = "current"))
        assertTrue(autoplayRefillNeedsLoad(loadActive = true, loadedSeed = "previous", mediaId = "current"))
        assertFalse(autoplayRefillNeedsLoad(loadActive = true, loadedSeed = "current", mediaId = "current"))
        assertTrue(autoplayRefillNeedsLoad(loadActive = true, loadedSeed = null, mediaId = "current"))
    }

    @Test
    fun `a downloaded youtube track already carries its seed`() {
        assertFalse(needsYoutubeSeedLookup(song("dQw4w9WgXcQ", "content://media/external/audio/media/42")))
        assertFalse(needsYoutubeSeedLookup(song("dQw4w9WgXcQ")))
    }
}
