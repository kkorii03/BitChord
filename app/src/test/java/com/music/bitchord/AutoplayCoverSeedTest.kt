package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.autoplayRowsAfterCoverFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cover seed's station is other covers. When the seed's own shown title
 * carries no marker, the marker filter used to empty the whole batch and
 * AutoPlay found nothing to add. The upload title kept in [Song.originalTitle]
 * is what tells the ordinary covers apart from karaoke/tribute uploads.
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
    fun `a cover seed keeps ordinary covers by any artist`() {
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
        assertEquals(setOf("가수", "다른 이름"), kept.map { it.artist }.toSet())
    }

    @Test
    fun `a production cover seed with a markerless upload title keeps English covers`() {
        // What CoverTitles.applyTo leaves on a queue row: the shown title is
        // the song, the byline names the original and the singer with 커버,
        // and originalTitle is the upload's own markerless title.
        val seed = song(
            title = "밤편지",
            artist = "아이유 · 싱어 커버",
            originalTitle = "밤편지 - 싱어",
            originalArtist = "싱어",
        )
        val rows = listOf(
            song("밤편지 (Cover)", "다른 이름"),
            song("밤편지 (커버)", "또 다른 이름"),
            song("밤편지 (Karaoke Version)", "노래방"),
        )
        val kept = autoplayRowsAfterCoverFilter(rows, seed)
        assertEquals(
            listOf("밤편지 (Cover)", "밤편지 (커버)"),
            kept.map { it.title },
        )
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
    fun `a cover seed drops karaoke and tribute even from its own artist`() {
        val seed = song(
            title = "밤편지",
            artist = "가수",
            originalTitle = "밤편지 (Cover)",
        )
        val rows = listOf(
            song("밤편지 (Cover)", "다른 이름"),
            song("밤편지 (Karaoke Version)", "가수"),
            song("밤편지", "Tribute Band"),
        )
        val kept = autoplayRowsAfterCoverFilter(rows, seed)
        assertEquals(listOf("밤편지 (Cover)"), kept.map { it.title })
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
