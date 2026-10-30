package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.videoradio.AutoplayMode
import com.music.bitchord.playback.pickVideoRadio
import com.music.bitchord.playback.videoRadioModeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRadioAutoplayTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        isVideo: Boolean = true,
        originalArtist: String? = null,
        duration: String = "3:20",
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        isVideo = isVideo,
        originalArtist = originalArtist,
    )

    @Test
    fun `a cover and a fan-clip upload select video radio`() {
        assertEquals(
            AutoplayMode.VIDEO_RADIO,
            videoRadioModeFor(song("a", "Dance Monkey (Cover)", "오화요")),
        )
        assertEquals(
            AutoplayMode.VIDEO_RADIO,
            videoRadioModeFor(
                song(
                    "b",
                    "Not a Dream",
                    "아카네 리제",
                    originalArtist = "리제 노래 아카이브",
                ),
            ),
        )
    }

    @Test
    fun `a topic upload and an official MV select normal mode`() {
        assertEquals(
            AutoplayMode.NORMAL,
            videoRadioModeFor(song("a", "Dance Monkey", "Tones and I - Topic")),
        )
        assertEquals(
            AutoplayMode.NORMAL,
            videoRadioModeFor(song("b", "Dance Monkey (Official MV)", "Tones and I")),
        )
    }

    @Test
    fun `video picking keeps covers and does not drift to catalogue rows`() {
        val seed = song("seed", "Dance Monkey (Cover)", "오화요")
        val radio = listOf(
            seed,
            song("cover-1", "Other Song (Cover)", "A", originalArtist = "A"),
            song("live-1", "Third Song Live", "B", originalArtist = "B"),
            song("cover-2", "Fourth Song (Cover)", "C", originalArtist = "C"),
            song("topic", "Fifth Song", "D - Topic", originalArtist = "D - Topic"),
        )
        val pick = pickVideoRadio(seed, radio, existing = listOf(seed))
        assertEquals(
            listOf("cover-1", "live-1", "cover-2"),
            pick.ordered.map { it.videoId },
        )
        assertTrue(pick.filter.dropped.isNotEmpty())
    }

    @Test
    fun `a thin batch is recognised as needing replacement`() {
        val seed = song("seed", "Dance Monkey (Cover)", "오화요")
        val radio = listOf(seed, song("one", "Other Song (Cover)", "A", originalArtist = "A"))
        val pick = pickVideoRadio(seed, radio, existing = listOf(seed))
        assertEquals(1, pick.ordered.size)
    }
}
