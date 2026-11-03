package com.music.bitchord.playback

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What the lock screen, the notification and Android Auto draw comes from the
 * queue item's [androidx.media3.common.MediaMetadata], built by [toMediaItem].
 * A cover has to read as its song there too, not as the cleaned upload row.
 */
class MediaSessionMetadataCoverTest {

    private val singers = CoverTitles.knownSingers

    @Before
    fun setUp() {
        CoverTitles.knownSingers = { emptySet() }
    }

    @After
    fun tearDown() {
        CoverTitles.knownSingers = singers
    }

    @Test
    fun `a cover's session metadata is the song and the singer byline`() {
        val item = Song(
            videoId = "cover-1",
            title = "아이묭 - Marigold / cover by 오화요",
            artist = "Ohwayo",
            thumbnailUrl = null,
            isVideo = true,
        ).toMediaItem()

        assertEquals("Marigold", item.mediaMetadata.title.toString())
        assertEquals("아이묭 · 오화요 커버", item.mediaMetadata.artist.toString())
    }

    @Test
    fun `a stale stored row is still cleaned before it reaches the session`() {
        val item = Song(
            videoId = "joker-1",
            title = "Gary Glitter - Rock & Roll Part II | Joker OST - Original Soundtrack",
            artist = "Original Soundtrack",
            thumbnailUrl = null,
        ).toMediaItem()

        assertEquals("Rock & Roll Part II", item.mediaMetadata.title.toString())
        assertEquals("Gary Glitter", item.mediaMetadata.artist.toString())
    }
}
