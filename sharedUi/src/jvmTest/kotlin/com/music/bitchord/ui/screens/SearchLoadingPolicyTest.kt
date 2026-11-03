package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A loading deadline belongs to the list the page is showing.
 *
 * The results list and the device lists are Loading independently, and the
 * old deadline was armed for whichever one happened to be Loading even while
 * the other source was on screen — so switching sources inherited the elapsed
 * fifteen seconds and showed the retry before the new request had any time.
 */
class SearchLoadingPolicyTest {

    @Test
    fun theResultsTimerStaysOffWhileTheLibraryIsShowing() {
        val active = activeSearchList(searchingLibrary = true, searchingAddons = false)
        assertFalse(waitingOnSearchList(active, SearchListSource.RESULTS, UiState.Loading))
        assertTrue(waitingOnSearchList(active, SearchListSource.LIBRARY, UiState.Loading))
    }

    @Test
    fun theDeviceTimerStaysOffWhileYouTubeResultsAreShowing() {
        val active = activeSearchList(searchingLibrary = false, searchingAddons = false)
        assertFalse(waitingOnSearchList(active, SearchListSource.LIBRARY, UiState.Loading))
        assertFalse(waitingOnSearchList(active, SearchListSource.ADDONS, UiState.Loading))
        assertTrue(waitingOnSearchList(active, SearchListSource.RESULTS, UiState.Loading))
    }

    @Test
    fun addonsAndLibraryDoNotShareADeadline() {
        val library = activeSearchList(searchingLibrary = true, searchingAddons = false)
        val addons = activeSearchList(searchingLibrary = false, searchingAddons = true)
        assertFalse(waitingOnSearchList(library, SearchListSource.ADDONS, UiState.Loading))
        assertFalse(waitingOnSearchList(addons, SearchListSource.LIBRARY, UiState.Loading))
        assertTrue(waitingOnSearchList(addons, SearchListSource.ADDONS, UiState.Loading))
    }

    @Test
    fun aSettledListNeverArmsADeadline() {
        val active = activeSearchList(searchingLibrary = false, searchingAddons = false)
        assertFalse(waitingOnSearchList(active, SearchListSource.RESULTS, UiState.Success(emptyList<String>())))
        assertFalse(waitingOnSearchList(active, SearchListSource.RESULTS, null))
    }
}
