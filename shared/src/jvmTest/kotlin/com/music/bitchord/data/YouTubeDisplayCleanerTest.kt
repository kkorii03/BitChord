package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The youtube.com display cleaner: label uploads, OST/MV packaging, aliases,
 * a known artist on a personal channel, and a cover that must stay untouched.
 */
class YouTubeDisplayCleanerTest {

    private fun song(title: String, artist: String) =
        Song(videoId = "v", title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun `a label upload becomes the song and its artist`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "[MV] IU(아이유) _ Friday(금요일에 만나요) (Feat. Jang Yi-jeong)",
            "1theK (원더케이)",
        )

        assertEquals("Friday(금요일에 만나요) (Feat. Jang Yi-jeong)", cleaned.title)
        assertEquals("IU", cleaned.artist)
    }

    @Test
    fun `a dash-separated label upload drops the alias`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "헤이즈 (Heize) - 비도 오고 그래서 (You, Clouds, Rain)",
            "STONE MUSIC",
        )

        assertEquals("비도 오고 그래서 (You, Clouds, Rain)", cleaned.title)
        assertEquals("헤이즈", cleaned.artist)
    }

    @Test
    fun `a leading OST tag and a nested alias are removed`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "[선재 업고 튀어 OST Part 3] 민니 ((여자)아이들) - 꿈결같아서",
            "STONE MUSIC",
        )

        assertEquals("꿈결같아서", cleaned.title)
        assertEquals("민니", cleaned.artist)
    }

    @Test
    fun `official video packaging and a topic channel are cleaned`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "Dynamite (Official Video)",
            "BTS - Topic",
        )

        assertEquals("Dynamite", cleaned.title)
        assertEquals("BTS", cleaned.artist)
    }

    @Test
    fun `a known artist on a personal channel still reads artist first`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "BTS - Dynamite",
            "Random Channel",
            knownArtists = setOf("BTS"),
        )

        assertEquals("Dynamite", cleaned.title)
        assertEquals("BTS", cleaned.artist)
    }

    @Test
    fun `a cover keeps the cover singer and its title`() {
        val cover = song("Shape of You - Ed Sheeran (cover by Jane)", "Jane")

        val cleaned = YouTubeDisplayCleaner.clean(cover)

        assertEquals(cover.title, cleaned.title)
        assertEquals("Jane", cleaned.artist)
    }
}
