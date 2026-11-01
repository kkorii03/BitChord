package com.music.bitchord.playback

import com.music.bitchord.download.SavedSongMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-ahead replacement must ask the same download-safety question as
 * ordinary playback before it attaches a saved file to the catalogue release.
 *
 * Before this, a legacy record with no provenance was judged by run time only,
 * so an equal-length video file was attached and the listener heard the MV.
 * A file whose provenance named a different, non-video song was streamed for
 * no reason, wasting the download the listener already paid for.
 */
class VersionReplacementSavedFileTest {

    private fun saved(
        title: String = "Song",
        artist: String = "Artist",
        duration: String = "3:26",
        soundSource: String? = null,
        isVideo: Boolean? = false,
        typeUnknown: Boolean = false,
    ) = SavedSongMetadata(
        videoId = "song",
        title = title,
        artist = artist,
        durationText = duration,
        uri = "file:///song.webm",
        soundSourceVideoId = soundSource,
        isVideo = isVideo,
        typeUnknown = typeUnknown,
    )

    private fun uses(
        saved: SavedSongMetadata?,
        source: SavedSongMetadata? = null,
    ) = VersionReplacementFilePolicy.usesDownloadedFile(
        playId = "song",
        saved = saved,
        source = source,
        catalogDurationMs = 206_000L,
        online = true,
    )

    @Test
    fun `a legacy video file of the same length is not attached`() {
        assertFalse(
            uses(saved(title = "Song (Official Video)", isVideo = null)),
        )
    }

    @Test
    fun `a legacy file recorded as a video is not attached`() {
        assertFalse(uses(saved(isVideo = true)))
    }

    @Test
    fun `a legacy file with an unknown upload type is not attached`() {
        assertFalse(uses(saved(isVideo = null, typeUnknown = true)))
    }

    @Test
    fun `a plain legacy song file of the same length is still attached`() {
        assertTrue(uses(saved(isVideo = null)))
    }

    @Test
    fun `a file from another song recording is attached`() {
        assertTrue(
            uses(
                saved(soundSource = "quality"),
                source = SavedSongMetadata(
                    videoId = "quality",
                    title = "Song",
                    artist = "Artist",
                    uri = "file:///quality.webm",
                    isVideo = false,
                ),
            ),
        )
    }

    @Test
    fun `a file from another video recording is not attached`() {
        assertFalse(
            uses(
                saved(soundSource = "video"),
                source = SavedSongMetadata(
                    videoId = "video",
                    title = "Song (Official Video)",
                    artist = "Artist",
                    uri = "file:///video.webm",
                    isVideo = true,
                ),
            ),
        )
    }
}
