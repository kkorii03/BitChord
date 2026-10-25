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
    fun aCommaIsPartOfANameNotASeparator() {
        assertEquals("A, B", appleSubtitleCredit("Song • A, B"))
    }
}
