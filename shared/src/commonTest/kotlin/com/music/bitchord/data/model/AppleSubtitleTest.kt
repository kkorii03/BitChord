package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleSubtitleTest {

    @Test
    fun cardsAreClassifiedByTheirIds() {
        assertEquals(AppleCardKind.SONG, appleCardKind("dQw4w9WgXcQ", null))
        assertEquals(AppleCardKind.ALBUM, appleCardKind(null, "MPREb_abc"))
        assertEquals(AppleCardKind.ALBUM, appleCardKind(null, "OLAK5uy_abc"))
        assertEquals(AppleCardKind.PLAYLIST, appleCardKind(null, "VLPLabc"))
        assertEquals(AppleCardKind.PLAYLIST, appleCardKind(null, "VLLM"))
        assertEquals(AppleCardKind.ARTIST, appleCardKind(null, "UCabc"))
        assertEquals(AppleCardKind.ARTIST, appleCardKind(null, "weird", isArtist = true))
        assertNull(appleCardKind(null, "local:downloads"))
    }

    @Test
    fun declaredSearchTypesMapToTheSameKinds() {
        assertEquals(AppleCardKind.ALBUM, appleCardKind(BrowseType.ALBUM))
        assertEquals(AppleCardKind.PLAYLIST, appleCardKind(BrowseType.PLAYLIST))
        assertEquals(AppleCardKind.ARTIST, appleCardKind(BrowseType.ARTIST))
        assertNull(appleCardKind(BrowseType.OTHER))
    }

    @Test
    fun aSongKeepsItsArtistAndDropsTheTypeWord() {
        assertEquals("Chelsea Wolfe", appleSubtitleCredit("Song • Chelsea Wolfe"))
        assertEquals("My Chemical Romance", appleSubtitleCredit("노래 • My Chemical Romance"))
    }

    @Test
    fun anAlbumKeepsItsArtistAndDropsTheYear() {
        assertEquals("Radiohead", appleSubtitleCredit("Album • Radiohead • 1997"))
        assertEquals("Bruno Mars", appleSubtitleCredit("앨범 • Bruno Mars • 2026년"))
    }

    @Test
    fun aPlaylistKeepsItsMaker() {
        assertEquals("YouTube Music", appleSubtitleCredit("Playlist • YouTube Music"))
        assertNull(appleSubtitleCredit("재생목록 • 자동 생성"))
    }

    @Test
    fun youtubeTalliesAreNotCredits() {
        assertNull(appleSubtitleCredit("1.2M plays"))
        assertNull(appleSubtitleCredit("1.2M subscribers"))
        assertNull(appleSubtitleCredit("1723만회 재생"))
        assertNull(appleSubtitleCredit("조회수 23만회"))
        assertNull(appleSubtitleCredit("구독자 423만명"))
        assertNull(appleSubtitleCredit("월간 시청자 6.19억명"))
    }

    @Test
    fun aTallyBesideANameLeavesTheName() {
        assertEquals("Adele", appleSubtitleCredit("조회수 23만회 • Adele"))
        assertEquals("Adele", appleSubtitleCredit("1.2M subscribers • Adele"))
    }

    @Test
    fun aCoverKeepsItsSingerAfterTheOriginalArtist() {
        // vc79 home showed '야화 / 안예은' for the cover — the singer was gone.
        assertEquals("안예은 · 오화요 커버", appleSubtitleCredit("노래 · 안예은 · 오화요 커버"))
        assertEquals("안예은 · 오화요 커버", appleSubtitleCredit("안예은 · 오화요 커버"))
        assertEquals("Mrs. GREEN APPLE · 오화요 커버", appleSubtitleCredit("Mrs. GREEN APPLE · 오화요 커버"))
    }

    @Test
    fun aCommaIsPartOfANameNotASeparator() {
        assertEquals("A, B", appleSubtitleCredit("Song • A, B"))
    }

    @Test
    fun aSongRowNamesOnlyItsArtist() {
        assertEquals("aespa", appleSubtitleLine(AppleCardKind.SONG, "노래", "aespa"))
        assertEquals("Chelsea Wolfe", appleSubtitleLine(AppleCardKind.SONG, "Song", "Chelsea Wolfe"))
    }

    @Test
    fun everyOtherRowKeepsItsKind() {
        assertEquals("앨범 · aespa", appleSubtitleLine(AppleCardKind.ALBUM, "앨범", "aespa"))
        assertEquals("재생목록 · aespa", appleSubtitleLine(AppleCardKind.PLAYLIST, "재생목록", "aespa"))
        assertEquals("아티스트", appleSubtitleLine(AppleCardKind.ARTIST, "아티스트", "aespa"))
    }

    @Test
    fun aSongWithoutACreditKeepsItsKind() {
        assertEquals("노래", appleSubtitleLine(AppleCardKind.SONG, "노래", null))
        assertEquals("노래", appleSubtitleLine(AppleCardKind.SONG, "노래", "   "))
    }
}
