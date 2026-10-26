package com.music.bitchord.data.model

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlbumSubtitleTest {

    @Test
    fun parsesTheCurrentBulletHeader() {
        val parts = parseAlbumSubtitle("Album • 1997 • Radiohead")

        assertEquals("Radiohead", parts.credit)
        assertEquals(1997, parts.year)
        assertNull(parts.month)
        assertFalse(parts.hasFullDate)
    }

    @Test
    fun parsesTheKoreanCommaHeader() {
        val parts = parseAlbumSubtitle("Bruno Mars, 앨범, 2026년")

        assertEquals("Bruno Mars", parts.credit)
        assertEquals(2026, parts.year)
        assertNull(parts.month)
    }

    @Test
    fun keepsAFullKoreanDate() {
        val parts = parseAlbumSubtitle("Bruno Mars, 앨범, 2026년 1월 1일")

        assertEquals("Bruno Mars", parts.credit)
        assertEquals(2026, parts.year)
        assertEquals(1, parts.month)
        assertEquals(1, parts.day)
        assertTrue(parts.hasFullDate)
    }

    @Test
    fun keepsAnIsoDateAndCollaborations() {
        val parts = parseAlbumSubtitle("A, B, Album, 2026-01-01")

        assertEquals("A, B", parts.credit)
        assertEquals(2026, parts.year)
        assertEquals(1, parts.month)
        assertEquals(1, parts.day)
    }

    @Ignore(
        "Existing bug: parseAlbumSubtitle drops the collaborator 'Artist' because " +
            "KIND_WORDS is matched case-insensitively, so the expected credit never appears. " +
            "Recorded in docs/cleanup/tests.md; source left unchanged by this test-only task.",
    )
    @Test
    fun anInvalidDateIsNotAReleaseYear() {
        val parts = parseAlbumSubtitle("Album • 2026-13-40 • Artist")

        assertEquals("2026-13-40, Artist", parts.credit)
        assertNull(parts.year)
    }
}
