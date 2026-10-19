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

    @Test
    fun `an SMTOWN quoted upload names the artist and the song`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "Hearts2Hearts 하츠투하츠 'The Chase' MV",
            "SMTOWN",
        )

        assertEquals("The Chase", cleaned.title)
        assertEquals("Hearts2Hearts", cleaned.artist)

        val doubled = YouTubeDisplayCleaner.clean(
            "Hearts2Hearts 하츠투하츠 ''The Chase'' MV",
            "SMTOWN",
        )

        assertEquals("The Chase", doubled.title)
        assertEquals("Hearts2Hearts", doubled.artist)
    }

    @Test
    fun `a HYBE LABELS special video drops the packaging and the label`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "ILLIT (아일릿) 'Almond Chocolate' Special Video",
            "HYBE LABELS",
        )

        assertEquals("Almond Chocolate", cleaned.title)
        assertEquals("ILLIT", cleaned.artist)
    }

    @Test
    fun `the 1theK underscore pattern keeps the artist and song`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "[MV] BTS(방탄소년단) _ Dynamite",
            "1theK (원더케이)",
        )

        assertEquals("Dynamite", cleaned.title)
        assertEquals("BTS", cleaned.artist)
    }

    @Test
    fun `an official video suffix is removed from a normal artist channel`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "Uptown Funk (Official Video)",
            "Mark Ronson",
        )

        assertEquals("Uptown Funk", cleaned.title)
        assertEquals("Mark Ronson", cleaned.artist)
    }

    @Test
    fun `a generic Entertainment channel is treated as an agency`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "aespa 에스파 'Supernova' MV",
            "Some Entertainment",
        )

        assertEquals("Supernova", cleaned.title)
        assertEquals("aespa", cleaned.artist)
    }

    @Test
    fun `a normal artist channel and a bare song title are untouched`() {
        val cleaned = YouTubeDisplayCleaner.clean("Dynamite", "BTS")

        assertEquals("Dynamite", cleaned.title)
        assertEquals("BTS", cleaned.artist)

        val official = YouTubeDisplayCleaner.clean("Dynamite", "BTS Official")

        assertEquals("Dynamite", official.title)
        assertEquals("BTS Official", official.artist)
    }

    @Test
    fun `unbracketed video tags are removed but a live take is kept`() {
        assertEquals(
            "Dynamite",
            YouTubeDisplayCleaner.clean("Dynamite Official Music Video", "BTS").title,
        )
        assertEquals(
            "Dynamite",
            YouTubeDisplayCleaner.clean("Dynamite 뮤직비디오", "BTS").title,
        )
        assertEquals(
            "Dynamite (Live)",
            YouTubeDisplayCleaner.clean("Dynamite (Live)", "BTS").title,
        )
    }
}
