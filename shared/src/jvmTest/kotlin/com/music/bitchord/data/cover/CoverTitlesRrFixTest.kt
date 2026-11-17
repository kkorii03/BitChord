package com.music.bitchord.data.cover

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review RR (11-17): official uploads read as covers. F1 a leading clip tag
 * with no cover word taken for a singer; F2 a release-packaging bracket taken
 * for the original's credit. The real cover shapes must still read.
 */
class CoverTitlesRrFixTest {

    private val singers = CoverTitles.knownSingers
    private val seen = CoverTitles.onSingerSeen

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = singers
        CoverTitles.onSingerSeen = seen
    }

    private fun cover(title: String, channel: String) =
        assertNotNull(CoverTitles.read(title, channel, null), title)

    // ---- F1 ---------------------------------------------------------------------------

    @Test
    fun aLeadingClipTagWithoutACoverWordIsNotASinger() {
        CoverTitles.knownSingers = { emptySet() }
        val told = mutableListOf<String>()
        CoverTitles.onSingerSeen = { told += it }
        listOf(
            "[Special Clip] 아이유 - 밤편지" to "1theK (원더케이)",
            "[STATION] 태연 - 그대라는 시" to "SMTOWN",
            "[Performance Video] 아이유 - 밤편지" to "1theK (원더케이)",
            "[Dance Practice] 아이유 - 밤편지" to "1theK (원더케이)",
            "[ENG SUB] 아이유 - 밤편지" to "1theK (원더케이)",
            "[뮤뱅] 아이유 - 좋은날 20120831" to "KBS Kpop",
        ).forEach { (title, channel) ->
            assertNull(CoverTitles.read(title, channel, null), title)
            assertNull(CoverTitles.read(title, channel, null, unambiguousOnly = true), title)
        }
        assertTrue(told.isEmpty(), told.toString())
    }

    @Test
    fun aFanClipBracketSingerIsStillACover() {
        CoverTitles.knownSingers = { emptySet() }
        val fan = cover("【달콤레나】고추잠자리", "피엔나야 클립따줘")
        assertEquals("고추잠자리", fan.title)
        assertEquals("달콤레나", fan.singer)
    }

    @Test
    fun aKnownSingerInALeadingBracketIsStillACover() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val known = cover("[오화요] 밤편지", "아무 채널")
        assertEquals("밤편지", known.title)
        assertEquals("오화요", known.singer)
    }

    @Test
    fun coverWordsStillMakeCovers() {
        CoverTitles.knownSingers = { emptySet() }
        val tagged = cover("[Cover] 밤편지 - 아이유", "홍길동")
        assertEquals("밤편지", tagged.title)
        assertEquals("아이유", tagged.originalArtist)
        assertEquals("홍길동", tagged.singer)

        val by = cover("밤편지 (cover by 홍길동)", "아무 채널")
        assertEquals("밤편지", by.title)
        assertEquals("홍길동", by.singer)

        val bracketed = cover("[오화요] Ed Sheeran - Shape of You Cover", "레몬크림소스")
        assertEquals("Shape of You", bracketed.title)
        assertEquals("Ed Sheeran", bracketed.originalArtist)
        assertEquals("오화요", bracketed.singer)
    }

    // ---- F2 ---------------------------------------------------------------------------

    @Test
    fun aReleasePackagingBracketIsNotTheOriginalsCredit() {
        CoverTitles.knownSingers = { emptySet() }
        listOf(
            "Idina Menzel - Let It Go (From \"Frozen\")" to "DisneyMusicVEVO",
            "Queen - Bohemian Rhapsody (2011 Remaster)" to "Classic Rock Hits",
            "Taylor Swift - Mr. Perfectly Fine (Taylor's Version) (From The Vault)" to "Pop Lyrics",
            "아이유 - 밤편지 (Performance Video)" to "1theK",
        ).forEach { (title, channel) ->
            assertNull(CoverTitles.read(title, channel, null), title)
        }
    }

    @Test
    fun aNameInTheBracketIsStillTheOriginalsCredit() {
        CoverTitles.knownSingers = { emptySet() }
        assertEquals("IU", cover("가수 - 밤편지(IU)", "1theK").originalArtist)
        assertEquals("Mr. Big", cover("Singer - Song (Mr. Big)", "channel").originalArtist)
        val lena = cover("달콤레나 - 고추잠자리(조용필)", "레나의 노래 일기")
        assertEquals("고추잠자리", lena.title)
        assertEquals("조용필", lena.originalArtist)
        assertEquals("달콤레나", lena.singer)
    }
}
