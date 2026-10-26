package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a shelf card's second line may and may not say. YouTube puts view
 * counts, subscriber tallies and durations there; Apple Music shows only the
 * kind and the credit, so anything that is a count must never survive as a
 * name.
 */
class AppleSubtitleEdgeTest {

    @Test
    fun everyTallyShapeIsHidden() {
        val tallies = listOf(
            "1.2M plays",
            "1,234 views",
            "12K likes",
            "3 songs",
            "12 tracks",
            "1 hour 4 minutes",
            "2:53",
            "1723만회 재생",
            "조회수 23만회",
            "구독자 423만명",
            "월간 시청자 6.19억명",
            "좋아요 1.2만개",
            "12곡",
            "1시간 4분",
            "1.2만명",
        )

        for (tally in tallies) {
            assertNull(appleSubtitleCredit(tally), "expected no credit for \"$tally\"")
        }
    }

    @Test
    fun aTallyBesideANameLeavesTheName() {
        assertEquals("Adele", appleSubtitleCredit("조회수 23만회 • Adele"))
        assertEquals("Adele", appleSubtitleCredit("1.2M subscribers • Adele"))
        assertEquals("Adele", appleSubtitleCredit("Adele • 1.2M subscribers"))
        assertEquals("Chelsea Wolfe", appleSubtitleCredit("3 songs • Chelsea Wolfe"))
    }

    @Test
    fun numbersThatArePartOfANameSurvive() {
        assertEquals("21 Savage", appleSubtitleCredit("21 Savage"))
        assertEquals("50 Cent", appleSubtitleCredit("50 Cent"))
        assertEquals("Blink-182", appleSubtitleCredit("Blink-182"))
        assertEquals("A, B", appleSubtitleCredit("Song • A, B"))
        assertEquals("A, B", appleSubtitleCredit("A, B • 2024"))
    }

    @Test
    fun everySeparatorAndKindWordIsIgnored() {
        assertEquals("Chelsea Wolfe", appleSubtitleCredit("Song • Chelsea Wolfe"))
        assertEquals("Chelsea Wolfe", appleSubtitleCredit("Song · Chelsea Wolfe"))
        assertEquals("Chelsea Wolfe", appleSubtitleCredit("Song | Chelsea Wolfe"))
        assertEquals("Chelsea Wolfe", appleSubtitleCredit("노래 • Chelsea Wolfe"))
        assertEquals("米津玄師", appleSubtitleCredit("アルバム • 米津玄師"))
        assertEquals("Bruno Mars", appleSubtitleCredit("앨범 • Bruno Mars • 2026년"))
    }

    @Test
    fun durationsAndYearsAreNotCredits() {
        assertNull(appleSubtitleCredit("3:45"))
        assertNull(appleSubtitleCredit("1997"))
        assertEquals("Radiohead", appleSubtitleCredit("Album • 1997 • Radiohead"))
        assertEquals("Taylor Swift", appleSubtitleCredit("Taylor Swift • 1989"))
    }

    @Test
    fun anHourLongDurationIsHidden() {
        assertNull(appleSubtitleCredit("1:23:45"))
    }

    @Test
    fun aKoreanYearIsNotACredit() {
        assertEquals("브루노 마스", appleSubtitleCredit("앨범 • 2026년 • 브루노 마스"))
    }

    @Test
    fun blankAndSeparatorOnlySubtitlesHaveNoCredit() {
        assertNull(appleSubtitleCredit(""))
        assertNull(appleSubtitleCredit("   "))
        assertNull(appleSubtitleCredit("•"))
        assertNull(appleSubtitleCredit("• •"))
        assertNull(appleSubtitleCredit(" | "))
    }

    @Test
    fun cardKindsFollowTheirIdFamilies() {
        assertNull(appleCardKind(null, null))
        assertNull(appleCardKind("", " "))
        assertEquals(AppleCardKind.SONG, appleCardKind("v", null))
        assertEquals(AppleCardKind.SONG, appleCardKind("v", "weird"))
        assertEquals(AppleCardKind.ARTIST, appleCardKind("v", "uc123"))
        assertEquals(AppleCardKind.ALBUM, appleCardKind(null, "mpre_abc"))
        assertEquals(AppleCardKind.ALBUM, appleCardKind(null, "olak5uy_abc"))
        assertEquals(AppleCardKind.PLAYLIST, appleCardKind(null, "vlplabc"))
        assertEquals(AppleCardKind.PLAYLIST, appleCardKind(null, "plabc"))
        assertEquals(AppleCardKind.PLAYLIST, appleCardKind(null, "rdabc"))
        assertEquals(AppleCardKind.PLAYLIST, appleCardKind(null, "lm"))
        assertEquals(AppleCardKind.PLAYLIST, appleCardKind(null, " VLPLabc "))
        assertEquals(AppleCardKind.ARTIST, appleCardKind(null, null, isArtist = true))
        assertEquals(AppleCardKind.ARTIST, appleCardKind("v", "weird", isArtist = true))
    }

    @Test
    fun declaredSearchTypesMapToTheSameKinds() {
        assertEquals(AppleCardKind.ALBUM, appleCardKind(BrowseType.ALBUM))
        assertEquals(AppleCardKind.ARTIST, appleCardKind(BrowseType.ARTIST))
        assertEquals(AppleCardKind.PLAYLIST, appleCardKind(BrowseType.PLAYLIST))
        assertNull(appleCardKind(BrowseType.OTHER))
    }
}
