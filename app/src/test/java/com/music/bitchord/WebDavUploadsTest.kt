package com.music.bitchord

import com.music.bitchord.data.webdav.WebDavUploads
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavUploadsTest {

    @Test
    fun aFinishedYouTubeDownloadCanBeUploaded() {
        // Regression: the song menu also required SongOrigins.isLocalFile, which
        // is false for a finished download, so its upload row disappeared even
        // though the saved content URI is exactly what the uploader accepts.
        assertTrue(WebDavUploads.isUploadableSource("dQw4w9WgXcQ", "content"))
        assertTrue(WebDavUploads.isUploadableSource("dQw4w9WgXcQ", "file"))
    }

    @Test
    fun aDeviceFileCanBeUploaded() {
        assertTrue(WebDavUploads.isUploadableSource("content://media/audio/1", "content"))
        assertTrue(WebDavUploads.isUploadableSource("/storage/emulated/0/Music/a.mp3", "file"))
    }

    @Test
    fun aStreamOrRemoteFileLibraryRowCannotBeUploaded() {
        assertFalse(WebDavUploads.isUploadableSource("dQw4w9WgXcQ", null))
        assertFalse(WebDavUploads.isUploadableSource("smb://server/share/a.mp3", "smb"))
        assertFalse(WebDavUploads.isUploadableSource("https://example.com/a.mp3", "https"))
    }

    @Test
    fun aRowAlreadyOnTheServerCannotBeUploadedAgain() {
        assertFalse(WebDavUploads.isUploadableSource("webdav:https://cloud/a.mp3", "content"))
    }
}
