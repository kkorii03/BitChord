package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A completed search with no rows is an answer ("nothing matched"), not the
 * blank page a request still in flight draws. The screen used to fall through
 * both cases with no message at all.
 */
class SearchEmptyStateTest {

    @Test
    fun aCompletedSearchWithNoRowsNeedsAnEmptyState() {
        val empty: UiState<List<String>> = UiState.Success(emptyList())
        assertTrue(empty.hasNoResults())
    }

    @Test
    fun rowsResultsDoNotShowTheEmptyState() {
        val rows: UiState<List<String>> = UiState.Success(listOf("song"))
        assertFalse(rows.hasNoResults())
    }

    @Test
    fun askingAndFailingAreNotTheSameAsNothingMatched() {
        val loading: UiState<List<String>> = UiState.Loading
        val failed: UiState<List<String>> = UiState.Error("연결 실패")
        val neverAsked: UiState<List<String>>? = null

        assertFalse(loading.hasNoResults())
        assertFalse(failed.hasNoResults())
        assertFalse(neverAsked.hasNoResults())
    }
}
