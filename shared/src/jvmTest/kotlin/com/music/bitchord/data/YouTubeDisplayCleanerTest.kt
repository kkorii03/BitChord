package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `a cover reads as the song and its singer`() {
        val cover = song("Shape of You - Ed Sheeran (cover by Jane)", "Jane")

        val cleaned = YouTubeDisplayCleaner.clean(cover)

        assertEquals("Shape of You (cover)", cleaned.title)
        assertEquals("Jane", cleaned.artist)
    }

    @Test
    fun `the reported Marigold cover keeps the singer, not the uploader`() {
        val cover = song("\uC544\uC774\uBBAD - Marigold / cover by \uC624\uD654\uC694", "Ohwayo")

        val cleaned = YouTubeDisplayCleaner.clean(cover)

        assertEquals("Marigold (cover)", cleaned.title)
        assertEquals("\uC624\uD654\uC694", cleaned.artist)
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
    fun `only uploads are cleaned, an ATV catalogue row is untouched`() {
        val catalogue = Song(
            videoId = "v-atv",
            title = "Dynamite (Official Video)",
            artist = "BTS",
            thumbnailUrl = null,
            albumId = "MPREb_atv",
        )
        val upload = Song(
            videoId = "v-upload",
            title = "BTS - Dynamite (Official Video)",
            artist = "BTS",
            thumbnailUrl = null,
        )

        assertEquals(catalogue, YouTubeDisplayCleaner.cleanUpload(catalogue))
        assertEquals("Dynamite", YouTubeDisplayCleaner.cleanUpload(upload).title)
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

    @Test
    fun `a soundtrack fan upload reads its artist, title and work label`() {
        data class Case(val raw: String, val title: String, val artist: String)
        val cases = listOf(
            Case(
                "Gary Glitter - Rock & Roll Part II | Joker OST - Original Soundtrack",
                "Rock & Roll Part II",
                "Gary Glitter",
            ),
            Case(
                "The Guess Who - Laughing | Joker OST - Original Soundtrack",
                "Laughing",
                "The Guess Who",
            ),
            Case(
                "Hildur Gu\u00F0nad\u00F3ttir - Bathroom Dance | Joker OST",
                "Bathroom Dance",
                "Hildur Gu\u00F0nad\u00F3ttir",
            ),
        )
        for (case in cases) {
            val cleaned = YouTubeDisplayCleaner.clean(case.raw, "Original Soundtrack")
            assertEquals(case.title, cleaned.title, case.raw)
            assertEquals(case.artist, cleaned.artist, case.raw)
            assertEquals("Joker OST", cleaned.album, case.raw)
        }
    }

    @Test
    fun `a soundtrack title with no performer never credits the soundtrack label`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "Following Sophie | Joker OST",
            "Original Soundtrack",
        )
        assertEquals("Following Sophie", cleaned.title)
        assertEquals("", cleaned.artist)
        assertEquals("Joker OST", cleaned.album)

        val byComposer = YouTubeDisplayCleaner.clean(
            "Following Sophie | Joker OST",
            "Hildur Gu\u00F0nad\u00F3ttir",
        )
        assertEquals("Following Sophie", byComposer.title)
        assertEquals("Hildur Gu\u00F0nad\u00F3ttir", byComposer.artist)
        assertEquals("Joker OST", byComposer.album)

        val labelled = YouTubeDisplayCleaner.clean(
            "Original Soundtrack - Following Sophie | Joker OST",
            "Joker OST",
        )
        assertEquals("Following Sophie", labelled.title)
        assertEquals("", labelled.artist)
        assertEquals("Joker OST", labelled.album)
    }

    @Test
    fun `a bare soundtrack word inside a real title is left alone`() {
        val our = YouTubeDisplayCleaner.clean("Our Soundtrack", "The Band")
        assertEquals("Our Soundtrack", our.title)
        assertEquals("The Band", our.artist)
        assertNull(our.album)

        val my = YouTubeDisplayCleaner.clean("My OST", "The Band")
        assertEquals("My OST", my.title)
        assertEquals("The Band", my.artist)
        assertNull(my.album)

        val labelChannel = YouTubeDisplayCleaner.clean("My OST", "Original Soundtrack")
        assertEquals("My OST", labelChannel.title)
        assertEquals("", labelChannel.artist)
        assertNull(labelChannel.album)
    }

    @Test
    fun `a dash packaged soundtrack label still enters the soundtrack path`() {
        val cleaned = YouTubeDisplayCleaner.clean("Following Sophie - OST", "Original Soundtrack")
        assertEquals("Following Sophie", cleaned.title)
        assertEquals("", cleaned.artist)
        assertNull(cleaned.album)
    }

    @Test
    fun `a From work keeps words that are part of its name`() {
        val originalSin = YouTubeDisplayCleaner.clean(
            "Song (From \"Original Sin\")",
            "Artist",
        )
        assertEquals("Original Sin OST", originalSin.album)
        assertEquals(
            "original sin",
            TrackMatcher.audioSwitchIdentity(
                "Song (From \"Original Sin\")",
                "Artist",
            ).work,
        )

        val motionPicture = YouTubeDisplayCleaner.clean(
            "Song (From \"The Motion Picture\")",
            "Artist",
        )
        assertEquals("The Motion Picture OST", motionPicture.album)
        assertEquals(
            "the motion picture",
            TrackMatcher.audioSwitchIdentity(
                "Song (From \"The Motion Picture\")",
                "Artist",
            ).work,
        )
    }

    @Test
    fun `the display split matches the audio switch identity`() {
        val raw = "Gary Glitter - Rock & Roll Part II | Joker OST - Original Soundtrack"
        val display = YouTubeDisplayCleaner.clean(raw, "Original Soundtrack")
        val identity = TrackMatcher.audioSwitchIdentity(raw, "Original Soundtrack")
        assertEquals(identity.title, TrackMatcher.normalize(display.title))
        assertEquals(identity.artist, TrackMatcher.normalize(display.artist))
        assertEquals(identity.work, "joker")
    }
}
