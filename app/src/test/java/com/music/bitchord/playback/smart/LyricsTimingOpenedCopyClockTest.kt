package com.music.bitchord.playback.smart

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceStream
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
    fun `a non alt copy stays youtube even while a substitute plays`() {
        playingSubstitute()

        val copy = VideoAudio.SongAudio(ByteArray(0), "audio", fromFile = false)

        assertEquals(LyricsTiming.Clock.YouTube, LyricsTiming.clockForSongAudio(song, copy))
    }
}
