package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The content-country chart is built from a parsed playlist page. Its rows used
 * to drop the parsed song's upload flag, so a video on that shelf played as a
 * plain song.
 */
class KoHomeFeedChartVideoTest {

    @Test
    fun `a video song stays a video on the chart shelf`() {
        val shelf = KoHomeFeed.chartShelf(
            title = "인기 뮤직비디오",
            songs = listOf(
                Song(videoId = "mv1", title = "Some MV", artist = "Channel", thumbnailUrl = null, isVideo = true),
                Song(videoId = "song1", title = "밤편지", artist = "IU", thumbnailUrl = null),
            ),
            moreBrowseId = null,
        )

        assertTrue(shelf.items[0].isVideo)
        assertFalse(shelf.items[1].isVideo)
    }
}
