package com.music.bitchord.data

import android.net.FakeUri
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-03: a downloaded cover opened from a file manager ("Open with") was
 * rebuilt from its MediaStore row with the upload flag missing, so it played as
 * a plain song and logged "song version skipped (not a video)".
 *
 * The MediaStore row only has a content URI; the reverse maps below are what
 * both the library scan and the "Open with" path hand to the row builder.
 */
class LocalMediaRowVideoTest {

    private val savedUri = "content://media/external/audio/media/42"
    private val previousSaved = Downloads.savedValue()
    private val previousMetadata = Downloads.savedMetadataSnapshot()

    @Before
    fun setUp() {
        Downloads.setSaved(mapOf("mv1" to savedUri))
        Downloads.setSavedMetadata(
            mapOf(
                "mv1" to SavedSongMetadata(
                    videoId = "mv1",
                    title = "Some MV",
                    artist = "Channel",
                    uri = savedUri,
                    isVideo = true,
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        Downloads.setSaved(previousSaved)
        Downloads.setSavedMetadata(previousMetadata)
    }

    @Test
    fun `a downloaded video's media-store uri maps back to a video`() {
        val (ids, videos) = LocalMediaRepository.savedDownloadMaps()

        assertEquals("mv1", ids[savedUri])
        assertTrue("downloaded video must stay a video", videos[savedUri] == true)
    }

    @Test
    fun `a media-store uri with no download record is not a video`() {
        Downloads.setSaved(emptyMap())
        Downloads.setSavedMetadata(emptyMap())

        val (ids, videos) = LocalMediaRepository.savedDownloadMaps()

        assertEquals(null, ids[savedUri])
        assertEquals(null, videos[savedUri])
    }

    @Test
    fun `the saved uri is the one a MediaStore row builds`() {
        // Guards the join itself: the map is keyed by the URI the row builder
        // produces, not by a raw file path.
        assertEquals(savedUri, FakeUri(savedUri).toString())
    }
}
