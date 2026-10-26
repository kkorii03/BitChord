package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.ArtistNameIndex
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reported cover and clip titles: who sings, whose song it is, and what
 * to search the lyrics under.
 */
class CoverClipTitleTest {

    @BeforeTest
    fun reset() {
        ArtistNameIndex.forget()
        CoverTitles.knownSingers = { emptySet() }
        CoverTitles.designatedChannels = { emptySet() }
        CoverTitles.knownOriginal = { null }
        CoverTitles.onSingerSeen = {}
    }

    @AfterTest
    fun clear() = reset()

    private fun song(title: String, artist: String, isVideo: Boolean = true) = Song(
        videoId = "$title|$artist",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    @Test
    fun `reported cover searches as the original and keeps the singer`() {
        val upload = song("달콤레나 - 고추잠자리(조용필) [26.09.06]", "레나의 노래 일기")

        val cover = assertNotNull(CoverTitles.read(upload))
        assertEquals("고추잠자리", cover.title)
        assertEquals("조용필", cover.originalArtist)
        assertEquals("달콤레나", cover.singer)
        // A Recents row that lost its video flag is still this cover.
        assertTrue(
            CoverTitles.isCover(
                song("달콤레나 - 고추잠자리(조용필)", "레나의 노래 일기", isVideo = false),
            ),
        )

        val query = CoverTitles.lyricsQuery(upload)
        assertEquals("고추잠자리", query.title)
        assertEquals("조용필", query.artist)
        assertTrue(query.isVideo)

        val name = LyricsQueryVariants.names(query.title, query.artist).first()
        assertEquals("고추잠자리", name.title)
        assertEquals("조용필", name.artist)

        val direct = LyricsQueryVariants.names(
            "달콤레나 - 고추잠자리(조용필) [26.09.06]",
            "레나의 노래 일기",
        ).first()
        assertEquals("고추잠자리", direct.title)
        assertEquals("조용필", direct.artist)
    }

    @Test
    fun `ordinary artist and song titles are not covers`() {
        listOf(
            song("IU - 밤편지", "IU"),
            song("NewJeans - Hype Boy", "NewJeans"),
            song("봄날 (Spring Day)", "BTS"),
        ).forEach { assertFalse(CoverTitles.isCover(it), it.title) }

        val letter = CoverTitles.lyricsQuery(song("IU - 밤편지", "IU"))
        assertEquals("밤편지", letter.title)
        assertEquals("IU", letter.artist)

        // "Spring Day" is the song's other-language title, not its artist.
        assertTrue(
            TrackMatcher.titleVariants("봄날 (Spring Day)", "BTS")
                .any { it.equals("Spring Day", ignoreCase = true) },
        )
        // "(Inst.)" is a take marker, never a subtitle to search as a title.
        assertFalse(
            TrackMatcher.titleVariants("사랑했나봐 (Inst.)", "김나영")
                .any { it.equals("Inst.", ignoreCase = true) },
        )
        assertFalse(CoverTitles.isCover(song("사랑했나봐 (Inst.)", "김나영")))
    }

    @Test
    fun `fan clip of the singer's own song is not a cover`() {
        val upload = song("아카네 리제 - 청록(Blue Rock)", "피엔나야 클립따줘")

        val query = CoverTitles.lyricsQuery(upload)
        assertEquals("청록", query.title)
        assertEquals("아카네 리제", query.artist)
        assertTrue(query.clipUpload)
        assertTrue(query.subtitles.any { it.equals("Blue Rock", ignoreCase = true) })
        assertTrue(
            query.alternatives.any {
                it.first.equals("Blue Rock", ignoreCase = true) && it.second == "아카네 리제"
            },
        )
        assertFalse(CoverTitles.isCover(upload))

        val variants = TrackMatcher.titleVariants("아카네 리제 - 청록(Blue Rock)", "피엔나야 클립따줘")
        assertEquals("청록", variants.first())
        assertTrue(variants.any { it.equals("Blue Rock", ignoreCase = true) })

        // Even when the app already knows her as a cover singer, the English
        // subtitle keeps this from being read as a B-shaped cover.
        val before = CoverTitles.knownSingers
        CoverTitles.knownSingers = { setOf("아카네 리제") }
        try {
            assertFalse(CoverTitles.isCover(upload))
            val still = CoverTitles.lyricsQuery(upload)
            assertEquals("청록", still.title)
            assertEquals("아카네 리제", still.artist)
        } finally {
            CoverTitles.knownSingers = before
        }

        val direct = LyricsQueryVariants.names(
            "아카네 리제 - 청록(Blue Rock)",
            "피엔나야 클립따줘",
        ).first()
        assertEquals("청록", direct.title)
        assertEquals("아카네 리제", direct.artist)
    }

    @Test
    fun `official upload puts the artist after the bar`() {
        val upload = song(
            "청록[Blue Rock] - it's | 아카네 리제(AKANE LIZE)",
            "아카네 리제 AKANE LIZE",
        )

        val query = CoverTitles.lyricsQuery(upload)
        assertEquals("청록", query.title)
        assertEquals("아카네 리제", query.artist)
        assertTrue(query.clipUpload)
        assertTrue(query.subtitles.any { it.equals("Blue Rock", ignoreCase = true) })
        assertTrue(
            query.alternatives.any {
                it.first.equals("Blue Rock", ignoreCase = true) && it.second == "아카네 리제"
            },
        )
        assertFalse(CoverTitles.isCover(upload))

        val before = CoverTitles.knownSingers
        CoverTitles.knownSingers = { setOf("아카네 리제") }
        try {
            assertFalse(CoverTitles.isCover(upload))
            val still = CoverTitles.lyricsQuery(upload)
            assertEquals("청록", still.title)
            assertEquals("아카네 리제", still.artist)
        } finally {
            CoverTitles.knownSingers = before
        }

        val direct = LyricsQueryVariants.names(
            "청록[Blue Rock] - it's | 아카네 리제(AKANE LIZE)",
            "아카네 리제 AKANE LIZE",
        ).first()
        assertEquals("청록", direct.title)
        assertEquals("아카네 리제", direct.artist)
    }

    @Test
    fun `cover title shapes all name the original and the singer`() {
        fun check(title: String, channel: String, songTitle: String, original: String?, singer: String) {
            val cover = assertNotNull(CoverTitles.read(title, channel, null), title)
            assertEquals(songTitle, cover.title, title)
            assertEquals(original, cover.originalArtist, title)
            assertEquals(singer, cover.singer, title)
        }

        check("달콤레나 - 고추잠자리(조용필) [26.09.06]", "레나의 노래 일기", "고추잠자리", "조용필", "달콤레나")
        check("달콤레나 - 고추잠자리(조용필)", "레나의 노래 일기", "고추잠자리", "조용필", "달콤레나")
        check("달콤레나 - 고추잠자리 (원곡: 조용필)", "레나의 노래 일기", "고추잠자리", "조용필", "달콤레나")
        check("[커버] 고추잠자리 - 조용필", "레나의 노래 일기", "고추잠자리", "조용필", "레나의 노래 일기")
        check("고추잠자리 (cover by 달콤레나)", "아무 채널", "고추잠자리", null, "달콤레나")
        check("고추잠자리 cover", "레나의 노래 일기", "고추잠자리", null, "레나의 노래 일기")
        check("고추잠자리 커버", "레나의 노래 일기", "고추잠자리", null, "레나의 노래 일기")
        check("【달콤레나】고추잠자리", "아무 채널", "고추잠자리", null, "달콤레나")

        val before = CoverTitles.knownSingers
        CoverTitles.knownSingers = { setOf("달콤레나") }
        try {
            check("달콤레나 - 고추잠자리 / 조용필", "레나의 노래 일기", "고추잠자리", "조용필", "달콤레나")
        } finally {
            CoverTitles.knownSingers = before
        }
    }

    @Test
    fun `trailing upload tags are packaging only`() {
        val tags = listOf(
            "[26.09.06]",
            "(2026.09.06)",
            "[노래]",
            "[풀버전]",
            "#shorts",
            "| 노래방",
            "Live",
            "(Prod. X)",
        )
        tags.forEach { tag ->
            val title = "달콤레나 - 고추잠자리(조용필) [26.09.06] $tag"
            val cover = assertNotNull(CoverTitles.read(title, "레나의 노래 일기", null), title)
            assertEquals("고추잠자리", cover.title, title)
            assertEquals("조용필", cover.originalArtist, title)
            assertEquals("달콤레나", cover.singer, title)
        }
    }
}
