package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phone 11-03: a downloaded cover played from Home Recents lost its video flag
 * on the way through history, so the lyrics screen looked its stored timing up
 * under the song key and found nothing. The saved row's own "(cover)" title
 * still says what it is.
 */
class CoverTitlesDownloadedCoverTest {

    private fun row(title: String, artist: String, isVideo: Boolean = false) = Song(
        videoId = "vRjfYdwM5L4",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    @Test
    fun `a saved cover title stays a video after the flag is lost`() {
        val cover = row("Marigold (cover)", "오화요")
        assertTrue(CoverTitles.isCover(cover))
        assertTrue(CoverTitles.lyricsQuery(cover).isVideo)
    }

    @Test
    fun `an ordinary song without the marker is still a song`() {
        val song = row("IU - 밤편지", "IU")
        assertFalse(CoverTitles.isCover(song))
        assertFalse(CoverTitles.lyricsQuery(song).isVideo)
    }
}
