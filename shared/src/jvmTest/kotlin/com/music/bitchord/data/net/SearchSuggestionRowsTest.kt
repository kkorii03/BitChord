package com.music.bitchord.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionRowsTest {

    @Test
    fun `the typed text stays first and is not repeated`() {
        val rows = suggestionRows("coldplay", listOf("coldplay", "coldplay yellow", "Coldplay live"))

        assertEquals(listOf("coldplay", "coldplay yellow", "Coldplay live"), rows)
    }

    @Test
    fun `duplicate completions collapse so lazy list keys stay unique`() {
        val rows = suggestionRows("ab", listOf("abc", "abc", "ABD", "abd"))

        assertEquals(listOf("ab", "abc", "ABD"), rows)
        assertEquals(rows.size, rows.distinctBy { it.lowercase() }.size)
    }

    @Test
    fun `a stale response is not current`() {
        assertTrue(isCurrentSearchResponse(7, 7))
        assertFalse(isCurrentSearchResponse(6, 7))
    }
}
