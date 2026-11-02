package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phone 11-03: a cover video tapped from Recents played as a plain song — no
 * song version, and a music radio that picked nothing — because the shelf
 * dropped the row's video flag. Its title also grew "( )" on every repaint.
 */
class KoHomeFeedRecentsTest {

    private val cover = Song(
        videoId = "VlKfF_ct59c",
        title = "화조도 花鳥圖 (cover)",
        artist = "오화요",
        thumbnailUrl = null,
        isVideo = true,
    )

    @Test
    fun `a video row stays a video on the recents shelf`() {
        assertTrue(KoHomeFeed.recentsShelf(listOf(cover)).items.single().isVideo)
    }

    @Test
    fun `repainting the shelf does not change the title`() {
        val first = KoHomeFeed.recentsShelf(listOf(cover)).items.single()
        val again = KoHomeFeed.recentsShelf(
            listOf(cover.copy(title = first.title, artist = first.subtitle)),
        ).items.single()
        assertEquals("화조도 花鳥圖 (cover)", first.title)
        assertEquals(first.title, again.title)
    }
}
