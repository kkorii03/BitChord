package com.music.bitchord.data

import com.music.bitchord.data.cover.CoverTitles
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

        assertEquals("Shape of You", cleaned.title)
        assertEquals("Ed Sheeran · Jane 커버", cleaned.artist)
    }

    // Phone 11-03: each pass turned "(cover)" into "( )" and added another
    // "(cover)", so the title grew "( ) ( ) ( )" and lyrics search failed.
    @Test
    fun `a title ending in (cover) stays the same however often it is cleaned`() {
        val once = YouTubeDisplayCleaner.clean(song("화조도 花鳥圖 (cover)", "오화요"))
        assertEquals("화조도 花鳥圖", once.title)
        assertEquals("오화요 커버", once.artist)
        assertEquals(once.title, YouTubeDisplayCleaner.clean(once).title)
    }

    @Test
    fun `a title saved with empty brackets is mended`() {
        val cleaned = YouTubeDisplayCleaner.clean(song("Night We Shine ( ) ( ) (cover)", "오화요"))
        assertEquals("Night We Shine", cleaned.title)
    }

    @Test
    fun `cleaning a video row keeps its artwork url`() {
        val video = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Shape of You - Ed Sheeran (cover by Jane)",
            artist = "Jane",
            thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            isVideo = true,
        )

        val cleaned = YouTubeDisplayCleaner.clean(video)

        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", cleaned.thumbnailUrl)
    }

    @Test
    fun `the reported Marigold cover keeps the singer, not the uploader`() {
        val cover = song("\uC544\uC774\uBBAD - Marigold / cover by \uC624\uD654\uC694", "Ohwayo")

        val cleaned = YouTubeDisplayCleaner.clean(cover)

        assertEquals("Marigold", cleaned.title)
        assertEquals("\uC544\uC774\uBBAD \u00B7 \uC624\uD654\uC694 \uCEE4\uBC84", cleaned.artist)
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
    fun `a publisher channel that is not in the table is dropped for the title's own credit`() {
        // Phone 11-06: ILLIT's MV on the HYPE UNIVERSE channel read
        // artist="HYPE UNIVERSE" and kept the upload's channel in the title.
        val cleaned = YouTubeDisplayCleaner.clean(
            "ILLIT (아일릿) 'You're Under Arrest' - HYPE UNIVERSE",
            "HYPE UNIVERSE",
        )

        assertEquals("You're Under Arrest", cleaned.title)
        assertEquals("ILLIT", cleaned.artist)
    }

    @Test
    fun `a topic channel reads the quoted credit out of the title`() {
        val cleaned = YouTubeDisplayCleaner.clean(
            "ILLIT (아일릿) 'You're Under Arrest' Official MV",
            "ILLIT - Topic",
        )

        assertEquals("You're Under Arrest", cleaned.title)
        assertEquals("ILLIT", cleaned.artist)
    }

    @Test
    fun `an upload on the artist's own channel keeps that channel as the artist`() {
        val cleaned = YouTubeDisplayCleaner.clean("Jane 'Shape of You'", "Jane")

        assertEquals("Shape of You", cleaned.title)
        assertEquals("Jane", cleaned.artist)
    }

    @Test
    fun `a publisher channel is never the artist when the title states no credit`() {
        val cleaned = YouTubeDisplayCleaner.clean("Almond Chocolate", "HYBE LABELS")

        assertEquals("Almond Chocolate", cleaned.title)
        assertEquals("", cleaned.artist)

        val universe = YouTubeDisplayCleaner.clean("ILLIT Teaser", "HYPE UNIVERSE")

        assertEquals("ILLIT Teaser", universe.title)
        assertEquals("", universe.artist)
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

    /** The reported row: "King Gnu - 一途" on the Korean channel "킹 누" (phone 11-04). */
    @Test
    fun `a credit in the other script is not repeated in the title`() {
        val cleaned = YouTubeDisplayCleaner.clean("King Gnu - 一途", "킹 누")

        assertEquals("一途", cleaned.title)
        assertEquals("킹 누", cleaned.artist)
    }

    @Test
    fun `a one-word left side is part of the title, not a credit`() {
        val cleaned = YouTubeDisplayCleaner.clean("Dynamite - Remix", "아이유")

        assertEquals("Dynamite - Remix", cleaned.title)
        assertEquals("아이유", cleaned.artist)
    }

    @Test
    fun `a packaging phrase on the left keeps the whole title`() {
        val cleaned = YouTubeDisplayCleaner.clean("Live at Budokan - Song", "킹 누")

        assertEquals("Live at Budokan - Song", cleaned.title)
        assertEquals("킹 누", cleaned.artist)
    }

    // Phone 11-04: a venue before the dash was dropped as if it were the act.
    @Test
    fun `a venue before the dash keeps the whole title`() {
        val dome = YouTubeDisplayCleaner.clean("Tokyo Dome - Song Title", "킹 누")
        assertEquals("Tokyo Dome - Song Title", dome.title)
        assertEquals("킹 누", dome.artist)

        val korean = YouTubeDisplayCleaner.clean("올림픽공원 체조경기장 - 봄날", "BTS")
        assertEquals("올림픽공원 체조경기장 - 봄날", korean.title)
        assertEquals("BTS", korean.artist)
    }

    // Phone 11-04: a cleaned byline was cleaned again, so the artist read
    // "오화요 커버 커버 커버 커버".
    @Test
    fun `a byline keeps one cover word however often it is cleaned`() {
        var row = YouTubeDisplayCleaner.clean(song("화조도 花鳥圖 (cover)", "오화요"))
        assertEquals("오화요 커버", row.artist)
        repeat(3) { row = YouTubeDisplayCleaner.clean(row) }
        assertEquals("오화요 커버", row.artist)
        // A row already saved with the repeated word is mended on the way out.
        assertEquals("오화요 커버", YouTubeDisplayCleaner.clean(song("화조도 花鳥圖", "오화요 커버 커버 커버")).artist)
    }

    // Phone 11-04: "Memories / 오화요" kept the singer in the title, so the
    // lyrics were searched for another song and the sync ran far off.
    @Test
    fun `a known singer after a slash leaves the title`() {
        val singers = CoverTitles.knownSingers
        try {
            CoverTitles.knownSingers = { setOf("오화요") }
            val row = Song(
                videoId = "v",
                title = "Memories / 오화요",
                artist = "허니즈 HONEYZ",
                thumbnailUrl = null,
                isVideo = true,
            )

            val cleaned = YouTubeDisplayCleaner.clean(row)

            assertEquals("Memories", cleaned.title)
            assertEquals("오화요 커버", cleaned.artist)
        } finally {
            CoverTitles.knownSingers = singers
        }
    }

    // The same defect for a singer no cover had named before: the slash tail
    // left the title only once the name was already known.
    @Test
    fun `a new singer after a slash leaves the title on the first sight`() {
        val row = Song(
            videoId = "v",
            title = "Something / 오화요",
            artist = "허니즈 HONEYZ",
            thumbnailUrl = null,
            isVideo = true,
        )

        val cleaned = YouTubeDisplayCleaner.clean(row)

        assertEquals("Something", cleaned.title)
        assertEquals("오화요 커버", cleaned.artist)
    }

    // Phone 11-06: a cover channel's upload named the artist in its title, and
    // the channel ("너의 이름은。 커버") was shown as the artist instead.
    @Test
    fun `a cover channels title credit is the artist, not the channel`() {
        val cleaned = YouTubeDisplayCleaner.clean("아무것도 아니야 / RADWIMPS", "너의 이름은。 커버")

        assertEquals("아무것도 아니야", cleaned.title)
        assertEquals("RADWIMPS", cleaned.artist)
    }

    // The other way round: an act's own channel carries no cover word, so the
    // channel is the credit whatever the title's slash names.
    @Test
    fun `an artists own channel stays the credit`() {
        val cleaned = YouTubeDisplayCleaner.clean("Memories / 윤미래", "오화요")

        assertEquals("Memories / 윤미래", cleaned.title)
        assertEquals("오화요", cleaned.artist)
    }

    // A `- Topic` channel is the artist's own upload, never a cover channel.
    @Test
    fun `a topic channel is not read as a cover channel`() {
        val cleaned = YouTubeDisplayCleaner.clean("아무것도 아니야 / RADWIMPS", "RADWIMPS - Topic")

        assertEquals("아무것도 아니야 / RADWIMPS", cleaned.title)
        assertEquals("RADWIMPS", cleaned.artist)
    }
}
