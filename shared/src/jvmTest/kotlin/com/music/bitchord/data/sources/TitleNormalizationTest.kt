package com.music.bitchord.data.sources

import com.music.bitchord.data.lyrics.LyricsQueryVariants
import com.music.bitchord.data.model.ArtistNameIndex
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The listing's packaging is not the song's name.
 *
 * A playlist row prints a track number in front ("3.여수밤바다") and a
 * translation after a dash (" - Yeosu Night Sea"); a byline writes several
 * artists with "및" and a stage name in brackets ("강현준(Lil tachi)"). Every
 * one of those made the lyrics providers answer not_found, and the player fell
 * back to unsynced YouTube Music lyrics.
 */
class TitleNormalizationTest {

    @Before
    fun forget() = ArtistNameIndex.forget()

    private fun song(
        title: String,
        artist: String,
        duration: String? = "4:30",
    ) = Song(
        videoId = "$title|$artist",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    @Test
    fun `track number and translation leave the korean title`() {
        val parts = TrackMatcher.parseTitle("3.여수밤바다 - Yeosu Night Sea", "장범준")
        assertEquals("여수밤바다", parts.core)
        assertTrue(parts.cores().contains("yeosunightsea"))
        assertEquals(
            listOf("여수밤바다", "yeosu night sea"),
            TrackMatcher.titleVariants("3.여수밤바다 - Yeosu Night Sea", "장범준"),
        )
        assertEquals(
            listOf("여수밤바다 장범준", "여수밤바다", "yeosu night sea"),
            TrackMatcher.queries(TrackMatcher.Target("3.여수밤바다 - Yeosu Night Sea", "장범준")),
        )
    }

    @Test
    fun `zero padded and prefixed track numbers are removed`() {
        assertEquals("ditto", TrackMatcher.parseTitle("03 Ditto", "aespa").core)
        assertEquals("ditto", TrackMatcher.parseTitle("3) Ditto", "aespa").core)
        assertEquals("ditto", TrackMatcher.parseTitle("Track 3 - Ditto", "aespa").core)
        // A number that is part of the title stays there.
        assertEquals("7rings", TrackMatcher.parseTitle("7 Rings", "Ariana Grande").core)
    }

    @Test
    fun `an existing bracketed korean reading is still packaging`() {
        val parts = TrackMatcher.parseTitle("Lazenca, Save Us (우리 동네 음악대장)", "넥스트")
        assertEquals("lazencasaveus", parts.core)
        assertTrue(parts.altCores.isEmpty())
        assertEquals(
            listOf("lazenca save us 넥스트", "lazenca save us"),
            TrackMatcher.queries(TrackMatcher.Target("Lazenca, Save Us (우리 동네 음악대장)", "넥스트")),
        )
    }

    @Test
    fun `a version marker is still part of the query`() {
        assertEquals(
            "shape of you acoustic",
            TrackMatcher.queries(TrackMatcher.Target("Shape of You (Acoustic)", "")).single(),
        )
    }

    @Test
    fun `an english catalogue row matches the korean target that names it`() {
        assertTrue(
            TrackMatcher.matches(
                song("Yeosu Night Sea", "장범준"),
                title = "3.여수밤바다 - Yeosu Night Sea",
                artist = "장범준",
                durationSec = 270,
            ),
        )
    }

    @Test
    fun `byline separators and bracketed stage names all become candidates`() {
        assertEquals(
            listOf("강현준", "lil tachi", "김호진", "hotchkiss"),
            TrackMatcher.artistVariants("강현준(Lil tachi) 및 김호진(HOTCHKISS)"),
        )
        assertEquals(
            listOf("ph-1", "키드밀리", "루피", "loopy"),
            TrackMatcher.artistVariants("pH-1, 키드밀리 및 루피 (Loopy)"),
        )
        // A one-letter base is a name, not a stage alias: f(x) stays f(x).
        assertEquals(listOf("f(x)"), TrackMatcher.artistVariants("f(x)"))
    }

    @Test
    fun `hip hop production credit is packaging`() {
        assertEquals("눈", TrackMatcher.parseTitle("눈 (Prod. by 기리보이)", "강현준").core)
        assertEquals("goodday", TrackMatcher.parseTitle("Good Day (feat. 팔로알토 (Paloalto))").core)
    }

    @Test
    fun `lyrics variants use the clean title and every credited artist`() {
        val eye = LyricsQueryVariants.names(
            "눈 (Prod. by 기리보이)",
            "강현준(Lil tachi) 및 김호진(HOTCHKISS)",
        )
        assertEquals("눈", eye.first().title)
        assertEquals("강현준", eye.first().artist)
        assertTrue(eye.any { it.artist == "강현준, lil tachi, 김호진, hotchkiss" })
        assertTrue(eye.any { it.artist.isEmpty() })
        assertTrue("variants must stay capped", eye.size <= 4)

        val goodDay = LyricsQueryVariants.names(
            "Good Day (feat. 팔로알토 (Paloalto))",
            "pH-1, 키드밀리 및 루피 (Loopy)",
        )
        assertEquals("good day", goodDay.first().title)
        assertEquals("ph-1", goodDay.first().artist)
        assertTrue(goodDay.any { "키드밀리" in it.artist && "loopy" in it.artist })
    }

    @Test
    fun `a name is an alias only when the app holds the evidence`() {
        ArtistNameIndex.record(listOf(ArtistRef("장범준", "UC_solo"), ArtistRef("버스커 버스커", "UC_group")))
        assertFalse(ArtistAliasesProbe.forArtist("장범준").contains("버스커 버스커"))
    }

    @Test
    fun `the same channel makes two printed names one artist`() {
        ArtistNameIndex.record(listOf(ArtistRef("장범준", "UC_same"), ArtistRef("버스커 버스커", "UC_same")))
        assertTrue(ArtistAliasesProbe.forArtist("장범준").contains("버스커 버스커"))
        assertTrue(TrackMatcher.namesTheSameArtist("장범준", "버스커 버스커"))
    }

    @Test
    fun `an album's own credits supply the group name`() {
        ArtistNameIndex.record(
            listOf(ArtistRef("장범준", "UC_solo")),
            albumName = "버스커 버스커 1집",
        )
        ArtistNameIndex.record(
            listOf(ArtistRef("버스커 버스커", "UC_group")),
            albumName = "버스커 버스커 1집",
        )
        assertTrue(
            ArtistAliasesProbe.forArtist("장범준", album = "버스커 버스커 1집")
                .contains("버스커 버스커"),
        )
    }
}

/** The internal alias helper, reached through a public seam for the test. */
private object ArtistAliasesProbe {
    fun forArtist(artist: String, album: String? = null): List<String> =
        com.music.bitchord.data.lyrics.ArtistAliases.forArtist(artist, album)
}
