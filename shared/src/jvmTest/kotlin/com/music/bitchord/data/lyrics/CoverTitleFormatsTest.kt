package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The title shapes reported from real cover and fan-clip uploads. */
class CoverTitleFormatsTest {

    private val originalSingers = CoverTitles.knownSingers

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = originalSingers
    }

    private fun song(title: String, channel: String) = Song(
        videoId = "test",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        isVideo = true,
    )

    private fun cover(title: String, channel: String) = assertNotNull(CoverTitles.read(title, channel, null), title)

    @Test
    fun singerFirstOriginalInBracketsWithDateIsACover() {
        val read = cover("달콤레나 - 고추잠자리(조용필) [26.09.06]", "레나의 노래 일기")
        assertEquals("고추잠자리", read.title)
        assertEquals("조용필", read.originalArtist)
        assertEquals("달콤레나", read.singer)

        val query = CoverTitles.lyricsQuery(song("달콤레나 - 고추잠자리(조용필) [26.09.06]", "레나의 노래 일기"))
        assertEquals("고추잠자리", query.title)
        assertEquals("조용필", query.artist)
        assertTrue(query.isVideo)
        assertTrue(query.artistKnown)
        assertTrue(query.alternatives.contains("고추잠자리" to "달콤레나"))
    }

    @Test
    fun theOtherReportedCoverShapesAreRead() {
        val undated = cover("달콤레나 - 고추잠자리(조용필)", "레나의 노래 일기")
        assertEquals("고추잠자리", undated.title)
        assertEquals("조용필", undated.originalArtist)
        assertEquals("달콤레나", undated.singer)

        val credited = cover("달콤레나 - 고추잠자리 (원곡: 조용필)", "레나의 노래 일기")
        assertEquals("고추잠자리", credited.title)
        assertEquals("조용필", credited.originalArtist)

        val slash = cover("달콤레나 - 고추잠자리 / 조용필", "레나의 노래 일기")
        assertEquals("고추잠자리", slash.title)
        assertEquals("조용필", slash.originalArtist)

        val leading = cover("[커버] 고추잠자리 - 조용필", "레나의 노래 일기")
        assertEquals("고추잠자리", leading.title)
        assertEquals("조용필", leading.originalArtist)
        assertEquals("레나의 노래 일기", leading.singer)

        val by = cover("고추잠자리 (cover by 달콤레나)", "아무 채널")
        assertEquals("고추잠자리", by.title)
        assertEquals("달콤레나", by.singer)

        val bare = cover("고추잠자리 cover", "아무 채널")
        assertEquals("고추잠자리", bare.title)
        assertEquals("아무 채널", bare.singer)

        val korean = cover("고추잠자리 커버", "아무 채널")
        assertEquals("고추잠자리", korean.title)

        val leadingSinger = cover("【달콤레나】고추잠자리", "피엔나야 클립따줘")
        assertEquals("고추잠자리", leadingSinger.title)
        assertEquals("달콤레나", leadingSinger.singer)
    }

    @Test
    fun trailingDatesAndTagsAreDropped() {
        val titles = listOf(
            "달콤레나 - 고추잠자리(조용필) [26.09.06]",
            "달콤레나 - 고추잠자리(조용필) (2026.09.06)",
            "달콤레나 - 고추잠자리(조용필) [노래]",
            "달콤레나 - 고추잠자리(조용필) [풀버전]",
            "달콤레나 - 고추잠자리(조용필) #shorts",
            "달콤레나 - 고추잠자리(조용필) | 노래방",
            "달콤레나 - 고추잠자리(조용필) Live",
            "달콤레나 - 고추잠자리(조용필) (Prod. by Someone)",
            "달콤레나 - 고추잠자리(조용필) [26.09.06] #shorts | 노래방",
        )
        titles.forEach { title ->
            val read = cover(title, "레나의 노래 일기")
            assertEquals("고추잠자리", read.title, title)
            assertEquals("조용필", read.originalArtist, title)
            assertEquals("달콤레나", read.singer, title)
        }
    }

    @Test
    fun ordinaryArtistAndSongTitlesAreNotCovers() {
        listOf("IU - 밤편지", "NewJeans - Hype Boy", "봄날 (Spring Day)").forEach { title ->
            val song = song(title, "어떤 채널")
            assertNull(CoverTitles.read(song), title)
            assertFalse(CoverTitles.isCover(song), title)
        }
        // The translation bracket is searched as another title, not an artist.
        val spring = CoverTitles.lyricsQuery(song("봄날 (Spring Day)", "어떤 채널"))
        assertEquals("봄날", spring.title)
        assertEquals("어떤 채널", spring.artist)
        assertTrue(spring.alternatives.contains("Spring Day" to "어떤 채널"))
    }

    @Test
    fun fanClipOfTheSingersOwnSongWithAnEnglishSubtitleIsNotACover() {
        CoverTitles.knownSingers = { setOf("아카네 리제") }
        val fan = song("아카네 리제 - 청록(Blue Rock)", "피엔나야 클립따줘")
        assertNull(CoverTitles.read(fan))
        assertFalse(CoverTitles.isCover(fan))
        val query = CoverTitles.lyricsQuery(fan)
        assertEquals("청록", query.title)
        assertEquals("아카네 리제", query.artist)
        assertTrue(query.alternatives.contains("Blue Rock" to "아카네 리제"))
    }

    @Test
    fun officialUploadWithTheArtistAfterABarReadsSongAndSubtitle() {
        val official = song(
            "청록[Blue Rock] - it's | 아카네 리제(AKANE LIZE)",
            "아카네 리제 AKANE LIZE",
        )
        assertNull(CoverTitles.read(official))
        assertFalse(CoverTitles.isCover(official))
        val query = CoverTitles.lyricsQuery(official)
        assertEquals("청록", query.title)
        assertEquals("아카네 리제", query.artist)
        assertTrue(query.alternatives.contains("Blue Rock" to "아카네 리제"))
    }
}
