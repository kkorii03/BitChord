package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.autoplayRowsAfterCoverFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cover seed's station is other covers. When the seed's own title carries no
 * marker, the marker filter used to empty the whole batch and AutoPlay found
 * nothing to add. The same-artist rows are the seed's own world and must stay.
 */
class AutoplayCoverSeedTest {

    private fun song(
        title: String,
        artist: String,
        originalTitle: String? = null,
        originalArtist: String? = null,
    ) = Song(
        videoId = title + artist,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        originalTitle = originalTitle,
        originalArtist = originalArtist,
    )

    @Test
    fun `a cover seed without a marker keeps its own artist's covers`() {
        val seed = song(
            title = "밤편지",
            artist = "가수",
            originalTitle = "밤편지 (Cover)",
            originalArtist = "싱어",
        )
        val rows = listOf(
            song("밤편지 (Cover)", "가수"),
            song("밤편지 (Cover)", "다른 이름"),
        )
        val kept = autoplayRowsAfterCoverFilter(rows, seed)
        assertEquals(listOf("가수"), kept.map { it.artist })
    }

    @Test
    fun `karaoke and tribute uploads from other names are still dropped`() {
        val seed = song("Wannabe", "Spice Girls")
        val rows = listOf(
            song("Wannabe (Karaoke Version)", "Sing King"),
            song("Wannabe", "Tribute Band"),
            song("Wannabe", "Spice Girls"),
        )
        val kept = autoplayRowsAfterCoverFilter(rows, seed)
        assertEquals(listOf("Spice Girls"), kept.map { it.artist })
    }

    @Test
    fun `a cover seed with only karaoke rows still yields nothing`() {
        val seed = song(
            title = "밤편지",
            artist = "가수",
            originalTitle = "밤편지 (Cover)",
            originalArtist = "싱어",
        )
        val kept = autoplayRowsAfterCoverFilter(
            listOf(song("밤편지 (Karaoke Version)", "노래방")),
            seed,
        )
        assertTrue(kept.isEmpty())
    }
}
