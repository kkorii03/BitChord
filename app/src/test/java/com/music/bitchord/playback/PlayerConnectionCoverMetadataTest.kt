package com.music.bitchord.playback

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What the lock screen, the notification and Android Auto draw comes from the
 * MediaItem's own metadata — see PlaybackService's session player. A cover must
 * read there exactly as the player reads it: the song's title, and the byline
 * "원곡자 · 가수 커버".
 */
class PlayerConnectionCoverMetadataTest {

    private val singers = CoverTitles.knownSingers
    private val original = CoverTitles.knownOriginal

    @Before
    fun isolate() {
        CoverTitles.knownSingers = { emptySet() }
        CoverTitles.knownOriginal = { null }
    }

    @After
    fun restore() {
        CoverTitles.knownSingers = singers
        CoverTitles.knownOriginal = original
    }

    @Test
    fun `a cover's session metadata shows the song and its byline`() {
        val upload = Song(
            videoId = "cover-meta-1",
            title = "아이묭 - Marigold / cover by 오화요",
            artist = "Ohwayo",
            thumbnailUrl = null,
            isVideo = true,
        )

        val metadata = upload.toMediaItem().mediaMetadata

        assertEquals("Marigold", metadata.title?.toString())
        assertEquals("아이묭 · 오화요 커버", metadata.artist?.toString())
    }

    @Test
    fun `an ordinary soundtrack row is still cleaned`() {
        val upload = Song(
            videoId = "ost-meta-1",
            title = "Gary Glitter - Rock & Roll Part II | Joker OST - Original Soundtrack",
            artist = "Original Soundtrack",
            thumbnailUrl = null,
        )

        val metadata = upload.toMediaItem().mediaMetadata

        assertEquals("Rock & Roll Part II", metadata.title?.toString())
        assertEquals("Gary Glitter", metadata.artist?.toString())
    }
}
