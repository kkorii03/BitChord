package com.music.bitchord.ko.ui

import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.SearchSource

/**
 * Re-runs the search that is on screen after it failed.
 *
 * The retry has to follow the source that answered: a YouTube retry must
 * not silently switch an Addons or Library search over to YouTube, and a
 * Library retry has to re-read the folder rather than re-filter the list it
 * already had. It deliberately does not record the query again — the
 * listener is repairing a request, not making a new one.
 */
internal fun MainViewModel.koRetrySearch() {
    val q = _query.value.trim()
    if (!retrySearchAllowed(_searchSource.value, q)) return
    when (_searchSource.value) {
        SearchSource.LIBRARY -> loadLibrarySongs()
        SearchSource.ADDONS -> runAddonSearch(q, record = false)
        SearchSource.YOUTUBE -> {
            searchSubmitted = true
            _suggestions.value = emptyList()
            _typeaheadResults.value = emptyList()
            // A retry exists to replace the page that is on screen, so it
            // must not be answered from the cache: reusing a cached empty
            // page was why the "no results" retry did nothing at all.
            runSearch(anonymous = lastSearchAnonymous, forceNetwork = true)
        }
    }
}
