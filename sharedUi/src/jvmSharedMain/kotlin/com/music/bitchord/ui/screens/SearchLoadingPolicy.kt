package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.UiState

/** Which of the Search page's three lists is on screen. */
internal enum class SearchListSource { RESULTS, LIBRARY, ADDONS }

internal fun activeSearchList(searchingLibrary: Boolean, searchingAddons: Boolean): SearchListSource =
    when {
        searchingLibrary -> SearchListSource.LIBRARY
        searchingAddons -> SearchListSource.ADDONS
        else -> SearchListSource.RESULTS
    }

/**
 * Whether [list]'s loading deadline is the one that may run.
 *
 * The results list stays `Loading` from its last request while the Library or
 * Addons source is showing, and the device lists keep their own state while
 * YouTube is showing. Arming a timer for the list that is not on screen made
 * the other source's page inherit an already-elapsed deadline and its retry.
 */
internal fun waitingOnSearchList(
    active: SearchListSource,
    list: SearchListSource,
    state: UiState<*>?,
): Boolean = active == list && state is UiState.Loading
