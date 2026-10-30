package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed row is the only suggestion available between a keystroke and the
 * debounced lookup. It has to be drawn as the query action; dropping element 0
 * left the results area as an empty spacer during a slow or failed lookup.
 */
class SearchSuggestionDisplayTest {

    @Test
    fun `the typed row is shown before any completion arrives`() {
        val rows = suggestionDisplayRows(listOf("coldplay"))

        assertEquals(listOf("coldplay"), rows.map { it.term })
        assertTrue(rows.single().isQueryAction)
    }

    @Test
    fun `three completions follow the typed query action`() {
        val rows = suggestionDisplayRows(
            listOf("coldplay", "coldplay yellow", "coldplay live", "coldplay hd", "coldplay extra"),
        )

        assertEquals(
            listOf("coldplay", "coldplay yellow", "coldplay live", "coldplay hd"),
            rows.map { it.term },
        )
        assertEquals(listOf(true, false, false, false), rows.map { it.isQueryAction })
    }
}
