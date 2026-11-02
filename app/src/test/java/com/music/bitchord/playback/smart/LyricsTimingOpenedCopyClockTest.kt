package com.music.bitchord.playback.smart

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.StreamChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The measured clock is the copy that was opened, not whatever the cache would
 * answer after the listen. A YouTube copy finishing mid-listen must not retag
 * words that were heard off the substitute.
 */
class LyricsTimingOpenedCopyClockTest {

    private val song = Song(videoId = "clock-copy", title = "t", artist = "a", thumbnailUrl = null)

    @Before
    @After
    fun reset() {
        StreamChoice.forget("clock-copy")
        Downloads.setSavedMetadata(emptyMap())
    }

    private fun playingSubstitute() {
        StreamChoice.remember(
            "clock-copy",
            SourceStream(
                url = "https://example.test/alt",
                sourceConfigId = "src",
                trackId = "track",
            ),
            substituted = true,
        )
    }

    @Test
    fun `the alt copy that was opened is the substitute's clock`() {
        playingSubstitute()

        val copy = VideoAudio.SongAudio(ByteArray(0), "audio#alt", fromFile = false)

        assertEquals(
            LyricsTiming.Clock.Substitute("src", "track"),
            LyricsTiming.clockForSongAudio(song, copy),
        )
    }

    @Test
    fun `a noatmos or upgrade copy is still the substitute's clock`() {
        playingSubstitute()

        listOf("audio#alt-noatmos", "audio#hifi", "audio#hifi-2").forEach { key ->
            val copy = VideoAudio.SongAudio(ByteArray(0), key, fromFile = false)
            assertEquals(
                key,
                LyricsTiming.Clock.Substitute("src", "track"),
                LyricsTiming.clockForSongAudio(song, copy),
            )
        }
    }

    @Test
    fun `a local file keeps the clock captured when its source was opened`() {
        KoDownloadRecords.ensureOpen()
        Downloads.setSavedMetadata(
            mapOf(
                "clock-copy" to SavedSongMetadata(
                    videoId = "clock-copy",
                    title = "t",
                    artist = "a",
                    uri = "file:///clock-copy.flac",
                    hqSourceConfigId = "repair",
                    hqTrackId = "later",
                ),
            ),
        )
        val copy = VideoAudio.SongAudio(
            ByteArray(0),
            renditionKey = null,
            fromFile = true,
            localSourceConfigId = "src",
            localTrackId = "track",
        )

        assertEquals(
            LyricsTiming.Clock.Substitute("src", "track"),
            LyricsTiming.clockForSongAudio(song, copy),
        )
    }

    @Test
    fun `a non alt copy stays youtube even while a substitute plays`() {
        playingSubstitute()

        val copy = VideoAudio.SongAudio(ByteArray(0), "audio", fromFile = false)

        assertEquals(LyricsTiming.Clock.YouTube, LyricsTiming.clockForSongAudio(song, copy))
    }
}
