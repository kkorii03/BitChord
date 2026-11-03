package com.music.bitchord.playback.smart

import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.ko.downloads.KoDownloadRecords
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * A measurement read straight off a downloaded file is on that file's clock.
 * An HQ download's file is the substitute it was verified against; tagging it
 * YouTube made [LyricsTiming.timing] apply the verified YouTube→HQ map again,
 * turning a correct cover measurement into a confident whole-song shift.
 */
class LyricsTimingMeasurementClockTest {

    private val videoId = "cover"

    @Before
    @After
    fun reset() {
        KoDownloadRecords.ensureOpen()
        Downloads.setSavedMetadata(emptyMap())
    }

    private fun download(sourceConfigId: String?, trackId: String?) {
        Downloads.setSavedMetadata(
            mapOf(
                videoId to SavedSongMetadata(
                    videoId = videoId,
                    title = "t",
                    artist = "a",
                    uri = "file:///$videoId.flac",
                    hqSourceConfigId = sourceConfigId,
                    hqTrackId = trackId,
                ),
            ),
        )
    }

    @Test
    fun `a measurement read off an hq download is on that file's clock`() {
        download("src", "track")

        assertEquals(
            LyricsTiming.Clock.Substitute("src", "track"),
            LyricsTiming.clockForRendition(videoId, "local:$videoId"),
        )
    }

    @Test
    fun `a measurement of a plain download stays on youtube's clock`() {
        download(null, null)

        assertEquals(
            LyricsTiming.Clock.YouTube,
            LyricsTiming.clockForRendition(videoId, "local:$videoId"),
        )
    }
}
